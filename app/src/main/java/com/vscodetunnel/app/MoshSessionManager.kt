package com.vscodetunnel.app

import android.content.Context
import android.webkit.WebView
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * Mosh session manager.
 *
 * Flow:
 * 1. SSH to server, run `mosh-server new`
 * 2. Parse response: MOSH CONNECT <port> <key>
 * 3. Launch native mosh-client binary as subprocess
 * 4. Bridge subprocess stdin/stdout to terminal WebView
 *
 * Requires: mosh-client binary at app files/bin/mosh-client (arm64)
 * The binary is extracted from assets on first run.
 */
class MoshSessionManager(
    private val context: Context,
    private val terminalWebView: WebView,
    private val onDisconnected: (reason: String) -> Unit
) {
    companion object {
        private const val TAG = "MoshSession"
        private const val MOSH_BINARY = "mosh-client"

        /** Check if mosh-client binary is available */
        fun isAvailable(context: Context): Boolean {
            val bin = File(context.filesDir, "bin/$MOSH_BINARY")
            return bin.exists() && bin.canExecute()
        }

        /** Extract mosh-client from assets if bundled */
        fun extractBinary(context: Context): Boolean {
            try {
                val binDir = File(context.filesDir, "bin")
                binDir.mkdirs()
                val dest = File(binDir, MOSH_BINARY)
                if (dest.exists()) return true

                // Try to extract from assets
                val input = context.assets.open("bin/$MOSH_BINARY")
                dest.outputStream().use { out -> input.copyTo(out) }
                input.close()
                dest.setExecutable(true)
                FileLogger.d(TAG, "Mosh binary extracted to ${dest.absolutePath}")
                return true
            } catch (e: Exception) {
                FileLogger.w(TAG, "Mosh binary not bundled in assets: $e")
                return false
            }
        }
    }

    private var sshSession: Session? = null
    private var moshProcess: Process? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isConnected = false; private set

    fun connect(server: SshServer) {
        scope.launch {
            try {
                writeInfo("Starting Mosh session to ${server.host}...\r\n")

                // Step 1: Check if mosh-client binary exists
                if (!isAvailable(context)) {
                    extractBinary(context)
                }
                if (!isAvailable(context)) {
                    writeInfo("\u001B[31mMosh client binary not found.\u001B[0m\r\n")
                    writeInfo("To use Mosh, place the arm64 mosh-client binary in:\r\n")
                    writeInfo("  ${context.filesDir}/bin/mosh-client\r\n")
                    writeInfo("\r\nFalling back to SSH...\r\n")
                    withContext(Dispatchers.Main) {
                        onDisconnected("Mosh binary not available")
                    }
                    return@launch
                }

                // Step 2: SSH to run mosh-server
                writeInfo("Connecting via SSH to start mosh-server...\r\n")
                val jsch = JSch()
                if (server.authMethod == SshServer.AuthMethod.KEY && server.privateKey.isNotBlank()) {
                    jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                }
                val sess = jsch.getSession(server.username, server.host, server.port)
                if (server.password.isNotBlank()) sess.setPassword(server.password)
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                sess.setConfig(config)
                sess.timeout = 15000
                sess.connect()
                sshSession = sess

                // Run mosh-server new
                val channel = sess.openChannel("exec") as ChannelExec
                channel.setCommand("LANG=en_US.UTF-8 mosh-server new")
                channel.inputStream = null
                val stdout = channel.inputStream
                val stderr = channel.errStream
                channel.connect()

                val output = stdout.bufferedReader().readText()
                val errOutput = stderr.bufferedReader().readText()
                channel.disconnect()

                // Parse: MOSH CONNECT <port> <key>
                val connectLine = (output + errOutput).lines()
                    .find { it.startsWith("MOSH CONNECT") }
                if (connectLine == null) {
                    writeInfo("\u001B[31mFailed to start mosh-server:\u001B[0m\r\n")
                    writeInfo(errOutput + "\r\n")
                    sess.disconnect()
                    withContext(Dispatchers.Main) { onDisconnected("mosh-server failed") }
                    return@launch
                }

                val parts = connectLine.trim().split(" ")
                val moshPort = parts[2]
                val moshKey = parts[3]
                writeInfo("Mosh server started on port $moshPort\r\n")

                // Close SSH (mosh doesn't need it anymore)
                sess.disconnect()
                sshSession = null

                // Step 3: Launch mosh-client binary
                val moshBin = File(context.filesDir, "bin/$MOSH_BINARY").absolutePath
                val env = arrayOf(
                    "MOSH_KEY=$moshKey",
                    "HOME=${context.filesDir}",
                    "TERM=xterm-256color"
                )
                val cmd = arrayOf(moshBin, server.host, moshPort)

                writeInfo("Launching mosh-client...\r\n")
                val process = Runtime.getRuntime().exec(cmd, env)
                moshProcess = process
                isConnected = true

                FileLogger.d(TAG, "Mosh process started: ${cmd.joinToString(" ")}")

                // Step 4: Bridge stdin/stdout
                readJob = scope.launch {
                    try {
                        val buf = ByteArray(8192)
                        val input = process.inputStream
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) { delay(20); continue }
                            writeOutput(String(buf, 0, n))
                        }
                    } catch (_: Exception) {
                    } finally {
                        isConnected = false
                        withContext(Dispatchers.Main) {
                            onDisconnected("Mosh session ended")
                        }
                    }
                }

                // Also read stderr
                scope.launch {
                    try {
                        val buf = ByteArray(4096)
                        val err = process.errorStream
                        while (isActive) {
                            val n = err.read(buf)
                            if (n < 0) break
                            if (n > 0) writeInfo(String(buf, 0, n))
                        }
                    } catch (_: Exception) {}
                }

            } catch (e: Exception) {
                FileLogger.e(TAG, "Mosh connection failed: $e")
                writeInfo("\r\n\u001B[31mMosh failed: ${e.message}\u001B[0m\r\n")
                withContext(Dispatchers.Main) { onDisconnected("Mosh failed: ${e.message}") }
            }
        }
    }

    fun sendInput(data: String) {
        scope.launch {
            try {
                moshProcess?.outputStream?.apply {
                    write(data.toByteArray())
                    flush()
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Send failed: $e")
            }
        }
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel(); readJob = null
        moshProcess?.destroy(); moshProcess = null
        sshSession?.disconnect(); sshSession = null
        FileLogger.d(TAG, "Mosh disconnected")
    }

    fun destroy() { disconnect(); scope.cancel() }

    private fun writeOutput(text: String) {
        val safe = JSONObject.quote(text)
        terminalWebView.post { terminalWebView.evaluateJavascript("writeOutput($safe)", null) }
    }

    private fun writeInfo(text: String) {
        val safe = JSONObject.quote(text)
        terminalWebView.post { terminalWebView.evaluateJavascript("writeInfo($safe)", null) }
    }
}
