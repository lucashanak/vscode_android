package com.vscodetunnel.app

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class SshSessionManager(
    private val terminalWebView: WebView,
    private val onDisconnected: (reason: String) -> Unit
) {
    companion object {
        private const val TAG = "SshSession"
    }

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    var isConnected = false
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun setupTerminal() {
        terminalWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        terminalWebView.setBackgroundColor(0xFF1E1E1E.toInt())
        terminalWebView.addJavascriptInterface(TerminalBridge(), "Android")
        terminalWebView.webViewClient = object : WebViewClient() {}
        terminalWebView.loadUrl("file:///android_asset/terminal/terminal.html")
    }

    fun connect(server: SshServer) {
        scope.launch {
            try {
                writeInfo("Connecting to ${server.host}:${server.port}...\r\n")

                val jsch = JSch()
                if (server.authMethod == SshServer.AuthMethod.KEY && server.privateKey.isNotBlank()) {
                    jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                }

                val sess = jsch.getSession(server.username, server.host, server.port)

                if (server.authMethod == SshServer.AuthMethod.PASSWORD && server.password.isNotBlank()) {
                    sess.setPassword(server.password)
                }

                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                sess.setConfig(config)
                sess.timeout = 15000

                sess.connect()
                session = sess

                writeInfo("Connected. Opening shell...\r\n")

                val ch = sess.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color")
                inputStream = ch.inputStream
                outputStream = ch.outputStream
                ch.connect()
                channel = ch
                isConnected = true

                FileLogger.d(TAG, "SSH connected to ${server.host}")

                // Read loop: SSH → terminal
                readJob = scope.launch {
                    try {
                        val buf = ByteArray(8192)
                        while (isActive && ch.isConnected) {
                            val n = inputStream?.read(buf) ?: -1
                            if (n < 0) break // EOF
                            if (n == 0) {
                                delay(50)
                                if (ch.isClosed) break
                                continue
                            }
                            val text = String(buf, 0, n)
                            writeOutput(text)
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (isConnected) {
                            isConnected = false
                            withContext(Dispatchers.Main) {
                                onDisconnected("Connection closed")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "SSH connection failed: $e")
                writeInfo("\r\nConnection failed: ${e.message}\r\n")
                withContext(Dispatchers.Main) {
                    onDisconnected("Connection failed: ${e.message}")
                }
            }
        }
    }

    fun sendInput(data: String) {
        scope.launch {
            try {
                outputStream?.write(data.toByteArray())
                outputStream?.flush()
            } catch (e: Exception) {
                FileLogger.w(TAG, "Send failed: $e")
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        isConnected = false
        readJob?.cancel()
        readJob = null
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        inputStream = null
        outputStream = null
        FileLogger.d(TAG, "SSH disconnected")
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private fun writeOutput(text: String) {
        val safe = JSONObject.quote(text) // returns "..."-wrapped, JSON-safe string
        terminalWebView.post {
            terminalWebView.evaluateJavascript("writeOutput($safe)", null)
        }
    }

    private fun writeInfo(text: String) {
        val safe = JSONObject.quote(text)
        terminalWebView.post {
            terminalWebView.evaluateJavascript("writeInfo($safe)", null)
        }
    }

    @Suppress("unused")
    inner class TerminalBridge {
        @JavascriptInterface
        fun onTerminalInput(data: String) {
            sendInput(data)
        }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            FileLogger.d(TAG, "Terminal ready: ${cols}x${rows}")
            resize(cols, rows)
        }

        @JavascriptInterface
        fun onTerminalResize(cols: Int, rows: Int) {
            resize(cols, rows)
        }
    }
}
