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
 * Requires: mosh-client binary bundled as jniLibs/arm64-v8a/libmosh.so
 * Android extracts it to nativeLibraryDir with execute permission.
 */
class MoshSessionManager(
    private val context: Context,
    private val terminalWebView: WebView,
    private val onDisconnected: (reason: String) -> Unit
) {
    companion object {
        private const val TAG = "MoshSession"
        private const val MOSH_LIB = "libmosh.so"

        /** Get mosh binary path from nativeLibraryDir */
        private fun moshBin(context: Context) =
            File(context.applicationInfo.nativeLibraryDir, MOSH_LIB)

        /** Check if mosh-client binary is available */
        fun isAvailable(context: Context): Boolean {
            val bin = moshBin(context)
            return bin.exists() && bin.canExecute()
        }

        /** Extract terminfo from assets to filesDir (needed for mosh-client) */
        private fun extractTerminfo(context: Context): String {
            val destDir = File(context.filesDir, "terminfo/x")
            val dest = File(destDir, "xterm-256color")
            if (dest.exists()) return destDir.parentFile!!.absolutePath
            destDir.mkdirs()
            try {
                context.assets.open("terminfo/x/xterm-256color").use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to extract terminfo: $e")
            }
            return destDir.parentFile!!.absolutePath
        }
    }

    private var sshSession: Session? = null
    private var moshProcess: PtyProcess? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isConnected = false; private set

    fun connect(server: SshServer) {
        scope.launch {
            try {
                writeInfo("Starting Mosh session to ${server.host}...\r\n")
                FileLogger.d(TAG, "connect() started for ${server.host}")

                // Step 1: Check if mosh-client binary exists
                val bin = moshBin(context)
                FileLogger.d(TAG, "Mosh binary: ${bin.absolutePath}, exists=${bin.exists()}, exec=${bin.canExecute()}")
                if (!isAvailable(context)) {
                    writeInfo("\u001B[31mMosh client binary not found.\u001B[0m\r\n")
                    writeInfo("Expected at: ${moshBin(context).absolutePath}\r\n")
                    writeInfo("\r\nFalling back to SSH...\r\n")
                    withContext(Dispatchers.Main) {
                        onDisconnected("Mosh binary not available")
                    }
                    return@launch
                }

                // Step 2: SSH to run mosh-server
                writeInfo("Connecting via SSH to start mosh-server...\r\n")
                FileLogger.d(TAG, "SSH connecting to ${server.host}:${server.port}")
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
                FileLogger.d(TAG, "SSH connected")

                // Detect available UTF-8 locale on server
                FileLogger.d(TAG, "Detecting server locale...")
                val localeChannel = sess.openChannel("exec") as ChannelExec
                localeChannel.setCommand("locale -a 2>/dev/null | grep -i 'utf-\\?8' | head -1")
                localeChannel.inputStream = null
                val localeStream = localeChannel.inputStream
                localeChannel.connect()
                val localeOut = localeStream.bufferedReader().readText().trim()
                localeChannel.disconnect()
                val utf8Locale = if (localeOut.isNotEmpty()) localeOut else "C.UTF-8"
                FileLogger.d(TAG, "Using server locale: $utf8Locale")

                // Run mosh-server new
                val channel = sess.openChannel("exec") as ChannelExec
                channel.setCommand("LANG=$utf8Locale mosh-server new")
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
                val moshBin = moshBin(context).absolutePath
                val terminfoDir = extractTerminfo(context)
                val env = arrayOf(
                    "MOSH_KEY=$moshKey",
                    "HOME=${context.filesDir}",
                    "TERM=xterm-256color",
                    "TERMINFO=$terminfoDir",
                    "LANG=C.UTF-8",
                    "PATH=/system/bin:/vendor/bin",
                    "TMPDIR=${context.cacheDir.absolutePath}"
                )
                val cmd = arrayOf(moshBin, server.host, moshPort)

                writeInfo("Launching mosh-client...\r\n")
                FileLogger.d(TAG, "Mosh cmd: ${cmd.joinToString(" ")}")
                FileLogger.d(TAG, "Mosh env: ${env.joinToString(", ") { it.substringBefore('=') }}")
                val process = PtyProcess.start(moshBin, cmd, env, rows = 24, cols = 80)
                moshProcess = process
                isConnected = true

                FileLogger.d(TAG, "Mosh PTY process started, pid=${process.pid}, alive=${process.isAlive}")

                // Send tmux command if enabled
                if (server.useTmux) {
                    delay(1000) // wait for mosh + shell to be ready
                    val tmuxName = server.tmuxSessionName.ifBlank { server.name.ifBlank { "main" } }
                    val tmuxCmd = TmuxManager.buildAttachCommand(tmuxName)
                    process.outputStream.write((tmuxCmd + "\n").toByteArray())
                    process.outputStream.flush()
                    FileLogger.d(TAG, "Sent tmux command: $tmuxCmd")
                    if (server.startupCommand.isNotBlank()) {
                        delay(300)
                        process.outputStream.write((server.startupCommand + "\n").toByteArray())
                        process.outputStream.flush()
                    }
                } else if (server.startupCommand.isNotBlank()) {
                    delay(500)
                    process.outputStream.write((server.startupCommand + "\n").toByteArray())
                    process.outputStream.flush()
                }

                // Step 4: Bridge PTY master fd to terminal WebView
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
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Mosh read error: $e")
                    } finally {
                        val exit = try { process.waitFor() } catch (_: Exception) { -1 }
                        FileLogger.d(TAG, "Mosh process exited with code: $exit")
                        isConnected = false
                        withContext(Dispatchers.Main) {
                            onDisconnected("Mosh session ended (exit $exit)")
                        }
                    }
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
                moshProcess?.let {
                    it.outputStream.write(data.toByteArray())
                    it.outputStream.flush()
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Send failed: $e")
            }
        }
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel(); readJob = null
        moshProcess?.destroy()
        moshProcess = null
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
