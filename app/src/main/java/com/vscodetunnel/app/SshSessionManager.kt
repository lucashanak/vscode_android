package com.vscodetunnel.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import com.vscodetunnel.app.AppSettings.terminalFontSize
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties

class SshSessionManager(
    private val context: Context,
    private val terminalWebView: WebView,
    private val onDisconnected: (reason: String) -> Unit,
    private val onHostKeyVerify: (host: String, fingerprint: String, callback: (Boolean) -> Unit) -> Unit
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isConnected = false; private set
    private var currentServer: SshServer? = null
    private var reconnectAttempts = 0

    // Port forwarding channels
    private val activeForwards = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun setupTerminal() {
        terminalWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        terminalWebView.setBackgroundColor(0xFF1E1E1E.toInt())
        terminalWebView.removeJavascriptInterface("Android")
        terminalWebView.addJavascriptInterface(TerminalBridge(), "Android")
        terminalWebView.webViewClient = object : WebViewClient() {}
        // Suppress Android's native context menu (we have our own selection toolbar)
        terminalWebView.setOnLongClickListener { true }
        terminalWebView.isLongClickable = false
        terminalWebView.isHapticFeedbackEnabled = false
        setupTwoFingerScroll(terminalWebView)
        terminalWebView.loadUrl("file:///android_asset/terminal/terminal.html")
    }

    companion object {
        private const val TAG = "SshSession"
        private const val MAX_RECONNECT_ATTEMPTS = 3

        /**
         * Handle all touch gestures on terminal WebView:
         * - 1-finger drag → scroll terminal (Termux-style)
         * - 1-finger long-press → select word
         * - 1-finger tap → pass through to xterm.js (focus/click)
         * - 2-finger scroll/pinch → scroll or zoom
         *
         * 1-finger scroll is handled here (not in JS) because xterm.js's
         * own touchmove handlers fight with any JS-level interceptors.
         * Native Android OnTouchListener runs before WebView dispatches
         * events to JavaScript, so we can reliably consume them.
         */
        @SuppressLint("ClickableViewAccessibility")
        fun setupTwoFingerScroll(webView: WebView) {
            // 2-finger state
            var twoFingerActive = false
            var twoFingerStartY = 0f
            var twoFingerStartDist = 0f
            var startFontSize = 14
            var mode: String? = null // "scroll" | "pinch"
            var scrollAccum2f = 0f

            // 1-finger state
            var oneFingerFontSize = 14
            var oneFingerStartX = 0f
            var oneFingerStartY = 0f
            var oneFingerLastY = 0f
            var oneFingerScrolling = false
            var longPressHandled = false
            var scrollAccum1f = 0f
            var longPressRunnable: Runnable? = null
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            // Dead zone in dp — finger must move this far before scroll starts
            // (prevents jitter on taps, but we still consume all 1-finger moves
            // to block xterm.js from seeing touchmove)
            val deadZoneDp = 5f

            webView.setOnTouchListener { v, event ->
                val density = v.resources.displayMetrics.density
                val deadZone = deadZoneDp * density

                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        // 1-finger down: record start, cache font size, start long-press timer
                        oneFingerStartX = event.x
                        oneFingerStartY = event.y
                        oneFingerLastY = event.y
                        oneFingerScrolling = false
                        longPressHandled = false
                        scrollAccum1f = 0f
                        webView.evaluateJavascript(
                            "typeof term!=='undefined'?term.options.fontSize:14"
                        ) { r -> oneFingerFontSize = r?.toIntOrNull() ?: 14 }

                        // Long-press timer (500ms) → select word at CSS pixel coords
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        val cssX = event.x / density
                        val cssY = event.y / density
                        longPressRunnable = Runnable {
                            if (!oneFingerScrolling && !twoFingerActive) {
                                longPressHandled = true
                                webView.evaluateJavascript("selectWordAt($cssX,$cssY);if(A)A.haptic()", null)
                            }
                        }
                        handler.postDelayed(longPressRunnable!!, 500)

                        false // Let WebView/xterm see ACTION_DOWN for focus
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        // Second finger: cancel 1-finger state, start 2-finger
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        oneFingerScrolling = false

                        if (event.pointerCount == 2) {
                            twoFingerActive = true
                            val dx = event.getX(0) - event.getX(1)
                            val dy = event.getY(0) - event.getY(1)
                            twoFingerStartDist = kotlin.math.sqrt(dx * dx + dy * dy)
                            twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                            mode = null
                            scrollAccum2f = 0f
                            webView.evaluateJavascript(
                                "typeof term!=='undefined'?term.options.fontSize:14"
                            ) { r -> startFontSize = r?.toIntOrNull() ?: 14 }
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        // --- 2-finger move ---
                        if (twoFingerActive && event.pointerCount >= 2) {
                            val dx = event.getX(0) - event.getX(1)
                            val dy = event.getY(0) - event.getY(1)
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            val midY = (event.getY(0) + event.getY(1)) / 2f
                            val distChange = kotlin.math.abs(dist - twoFingerStartDist)
                            val yChange = kotlin.math.abs(midY - twoFingerStartY)

                            if (mode == null && (distChange > 20f || yChange > 15f)) {
                                mode = if (distChange > yChange * 2f) "pinch" else "scroll"
                            }

                            when (mode) {
                                "scroll" -> {
                                    val lineHeight = startFontSize * 1.2f * density
                                    scrollAccum2f += twoFingerStartY - midY
                                    val lines = (scrollAccum2f / lineHeight).toInt()
                                    if (lines != 0) {
                                        webView.evaluateJavascript("scrollTerminal($lines)", null)
                                        scrollAccum2f -= lines * lineHeight
                                    }
                                    twoFingerStartY = midY
                                    return@setOnTouchListener true
                                }
                                "pinch" -> {
                                    val scale = dist / twoFingerStartDist
                                    val newSize = (startFontSize * scale).toInt().coerceIn(8, 32)
                                    webView.evaluateJavascript(
                                        "if(term.options.fontSize!==$newSize){term.options.fontSize=$newSize;fitAddon.fit()}", null)
                                    return@setOnTouchListener true
                                }
                            }
                            return@setOnTouchListener false
                        }

                        // --- 1-finger move ---
                        // ALWAYS consume to prevent xterm.js from receiving touchmove
                        // (xterm.js uses touchmove for its own scroll/selection logic)
                        if (event.pointerCount == 1 && !twoFingerActive) {
                            val dx = event.x - oneFingerStartX
                            val dy = event.y - oneFingerStartY
                            if (!oneFingerScrolling && (dx * dx + dy * dy > deadZone * deadZone)) {
                                // Past dead zone: start scrolling, cancel long-press
                                oneFingerScrolling = true
                                longPressRunnable?.let { handler.removeCallbacks(it) }
                                webView.evaluateJavascript("term.clearSelection()", null)
                            }
                            if (oneFingerScrolling) {
                                val deltaY = oneFingerLastY - event.y
                                oneFingerLastY = event.y
                                val lineHeight = oneFingerFontSize * 1.2f * density
                                scrollAccum1f += deltaY
                                val lines = (scrollAccum1f / lineHeight).toInt()
                                if (lines != 0) {
                                    webView.evaluateJavascript("scrollTerminal($lines)", null)
                                    scrollAccum1f -= lines * lineHeight
                                }
                            }
                            return@setOnTouchListener true // Always consume 1-finger moves
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_POINTER_UP,
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        val was2f = twoFingerActive
                        val was1fScroll = oneFingerScrolling
                        val wasLongPress = longPressHandled
                        twoFingerActive = false
                        mode = null
                        oneFingerScrolling = false
                        // Consume UP after scroll/long-press to prevent browser
                        // from synthesizing click that would clear selection
                        if (was2f || was1fScroll || wasLongPress) return@setOnTouchListener true
                        false
                    }
                    else -> false
                }
            }
        }
    }

    fun connect(server: SshServer) {
        currentServer = server
        reconnectAttempts = 0
        doConnect(server)
    }

    private fun doConnect(server: SshServer) {
        scope.launch {
            try {
                // Reset xterm.js terminal state (exit alternate buffer, disable mouse mode)
                // so stale state from a previous session doesn't leak SGR sequences
                writeOutput("\u001b[?1049l\u001b[?1002l\u001b[?1003l\u001b[?1006l")
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

                // TOFU host key verification
                val hostKey = sess.hostKey
                if (hostKey != null) {
                    val fingerprint = fingerprintHex(hostKey.getFingerPrint(jsch))
                    val known = KnownHosts.getFingerprint(context, server.host, server.port)
                    if (known == null) {
                        // First time — save
                        KnownHosts.saveFingerprint(context, server.host, server.port, fingerprint)
                        writeInfo("Host key saved: $fingerprint\r\n")
                    } else if (known != fingerprint) {
                        // Changed — warn and ask
                        writeInfo("\r\n\u001B[31mWARNING: HOST KEY CHANGED!\u001B[0m\r\n")
                        writeInfo("Expected: $known\r\n")
                        writeInfo("Got:      $fingerprint\r\n")
                        val accepted = withContext(Dispatchers.Main) {
                            suspendCancellableCoroutine { cont ->
                                onHostKeyVerify(server.host, fingerprint) { result ->
                                    cont.resumeWith(Result.success(result))
                                }
                            }
                        }
                        if (!accepted) {
                            sess.disconnect()
                            writeInfo("Connection rejected by user.\r\n")
                            return@launch
                        }
                        KnownHosts.saveFingerprint(context, server.host, server.port, fingerprint)
                    }
                }

                session = sess

                // Setup port forwarding
                for (pf in server.portForwards) {
                    try {
                        if (pf.type == "local" && pf.localPort > 0 && pf.remotePort > 0) {
                            sess.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
                            activeForwards.add("L${pf.localPort}")
                            writeInfo("Port forward: L${pf.localPort} → ${pf.remoteHost}:${pf.remotePort}\r\n")
                        } else if (pf.type == "remote" && pf.localPort > 0 && pf.remotePort > 0) {
                            sess.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
                            activeForwards.add("R${pf.remotePort}")
                            writeInfo("Port forward: R${pf.remotePort} → ${pf.remoteHost}:${pf.localPort}\r\n")
                        }
                    } catch (e: Exception) {
                        writeInfo("\u001B[33mPort forward failed: ${pf}: ${e.message}\u001B[0m\r\n")
                    }
                }

                writeInfo("Connected. Opening shell...\r\n")

                val ch = sess.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color")
                inputStream = ch.inputStream
                outputStream = ch.outputStream
                ch.connect()
                channel = ch
                isConnected = true
                reconnectAttempts = 0

                FileLogger.d(TAG, "SSH connected to ${server.host}")

                // Apply color scheme
                if (server.colorScheme != "default") {
                    applyColorScheme(server.colorScheme)
                }

                // Send tmux or startup command
                val tmuxCmd = if (server.useTmux) {
                    val name = server.tmuxSessionName.ifBlank { server.name.ifBlank { "main" } }
                    TmuxManager.buildAttachCommand(name)
                } else null

                if (tmuxCmd != null || server.startupCommand.isNotBlank()) {
                    delay(500) // wait for shell prompt
                    if (tmuxCmd != null) {
                        sendInput(tmuxCmd + "\n")
                        if (server.startupCommand.isNotBlank()) {
                            delay(300) // wait for tmux to start
                            sendInput(server.startupCommand + "\n")
                        }
                    } else {
                        sendInput(server.startupCommand + "\n")
                    }
                }

                // Read loop
                readJob = scope.launch {
                    try {
                        val buf = ByteArray(8192)
                        while (isActive && ch.isConnected) {
                            val n = inputStream?.read(buf) ?: -1
                            if (n < 0) break
                            if (n == 0) { delay(50); if (ch.isClosed) break; continue }
                            val text = String(buf, 0, n)
                            writeOutput(text)
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (isConnected) {
                            isConnected = false
                            // Try auto-reconnect
                            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && currentServer != null) {
                                reconnectAttempts++
                                writeInfo("\r\n\u001B[33mConnection lost. Reconnecting (${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)...\u001B[0m\r\n")
                                delay(2000)
                                doConnect(currentServer!!)
                            } else {
                                withContext(Dispatchers.Main) {
                                    onDisconnected("Connection closed")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "SSH connection failed: $e")
                writeInfo("\r\nConnection failed: ${e.message}\r\n")
                if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && currentServer != null) {
                    reconnectAttempts++
                    writeInfo("\u001B[33mRetrying (${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)...\u001B[0m\r\n")
                    delay(3000)
                    doConnect(currentServer!!)
                } else {
                    withContext(Dispatchers.Main) {
                        onDisconnected("Connection failed: ${e.message}")
                    }
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
        try { channel?.setPtySize(cols, rows, cols * 8, rows * 16) } catch (_: Exception) {}
    }

    fun disconnect() {
        isConnected = false
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS // prevent auto-reconnect
        currentServer = null
        readJob?.cancel(); readJob = null
        activeForwards.clear()
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null; inputStream = null; outputStream = null
        FileLogger.d(TAG, "SSH disconnected")
    }

    fun destroy() { disconnect(); scope.cancel() }

    fun getScrollback(): String {
        // Will be called from JS bridge
        return "" // placeholder — actual export done via JS
    }

    private fun applyColorScheme(scheme: String) {
        terminalWebView.post {
            terminalWebView.evaluateJavascript("applyColorScheme('$scheme')", null)
        }
    }

    private fun writeOutput(text: String) {
        val safe = JSONObject.quote(text)
        terminalWebView.post { terminalWebView.evaluateJavascript("writeOutput($safe)", null) }
    }

    private fun writeInfo(text: String) {
        val safe = JSONObject.quote(text)
        terminalWebView.post { terminalWebView.evaluateJavascript("writeInfo($safe)", null) }
    }

    private fun fingerprintHex(fp: String): String = fp

    @Suppress("unused")
    inner class TerminalBridge {
        @JavascriptInterface
        fun onTerminalInput(data: String) { sendInput(data) }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            FileLogger.d(TAG, "Terminal ready: ${cols}x${rows}")
            val fontSize = AppSettings.run { context.terminalFontSize }
            if (fontSize != 14) {
                terminalWebView.post {
                    terminalWebView.evaluateJavascript("setFontSize($fontSize)", null)
                }
            }
            resize(cols, rows)
        }

        @JavascriptInterface
        fun onTerminalResize(cols: Int, rows: Int) { resize(cols, rows) }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            haptic()
        }

        @JavascriptInterface
        fun haptic() {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("haptic_feedback", false)) return
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(5)
            }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }

        @JavascriptInterface
        fun exportScrollback(content: String) {
            try {
                val dir = java.io.File(context.cacheDir, "exports")
                dir.mkdirs()
                val file = java.io.File(dir, "terminal_${System.currentTimeMillis()}.log")
                file.writeText(content)
                terminalWebView.post {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Export terminal log"))
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Export failed: $e")
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to open URL: $url")
            }
        }
    }
}
