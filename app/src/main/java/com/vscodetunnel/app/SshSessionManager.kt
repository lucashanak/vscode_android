package com.vscodetunnel.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.vscodetunnel.app.AppSettings.terminalFontSize
import com.vscodetunnel.app.AppSettings.terminalScrollback
import com.vscodetunnel.app.AppSettings.sshAutoReconnect
import com.vscodetunnel.app.AppSettings.sshReconnectAttempts
import com.vscodetunnel.app.AppSettings.sshOsc52ClipboardRead
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SshSessionManager(
    private val context: Context,
    private val terminalWebView: WebView,
    private val onDisconnected: (reason: String) -> Unit,
    private val onHostKeyVerify: (
        host: String,
        port: Int,
        fingerprint: String,
        changed: Boolean,
        oldFingerprint: String?,
        callback: (Boolean) -> Unit
    ) -> Unit,
    private val onPassphraseRequired: (
        server: SshServer,
        retry: Boolean,
        callback: (String?) -> Unit
    ) -> Unit
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isConnected = false; private set
    @Volatile private var currentServer: SshServer? = null
    /** Mutated from the binder thread (network callback), IO (read loop) and main (lifecycle). */
    private val reconnectAttempts = AtomicInteger(0)
    /** Set by disconnect(); suppresses every reconnect path until the next connect(). */
    @Volatile private var stopped = true
    /**
     * A failure that retrying cannot fix: wrong credentials, or a host key the user refused.
     * Retrying those re-sends the password to the server (fail2ban, lockout) or re-opens a dialog
     * the user already dismissed. Sticky until the next explicit connect().
     */
    @Volatile private var fatal = false
    /** The user tapped "reject" on the host key dialog — fatal, unlike a prompt that went unanswered. */
    @Volatile private var hostKeyDenied = false
    /** The dialog could not be shown or nobody answered it. Retryable: the user may still be back. */
    @Volatile private var hostKeyUnanswered = false
    /**
     * Passphrase for an encrypted private key, resolved once per connect() and reused by every
     * reconnect. Reconnects run unattended in the background, so they cannot put a dialog on
     * screen — asking again there would park the connect thread until the backstop timeout and
     * then fail. Never logged, and cleared on disconnect().
     */
    @Volatile private var keyPassphrase: String? = null
    /**
     * connect() is waiting on the passphrase dialog. Suppresses every other connect trigger until
     * it resolves — a network edge or an app resume reaching doConnect() first would connect
     * without the passphrase and latch [fatal] on the resulting "invalid privatekey".
     */
    @Volatile private var awaitingPassphrase = false
    @Volatile private var foreground = true
    @Volatile private var lastConnectedAt = 0L

    /** Last size xterm.js reported, replayed onto the PTY once a channel exists. */
    @Volatile private var lastCols = 0
    @Volatile private var lastRows = 0

    /** Only one connect attempt at a time — entry points include a binder thread. */
    private val connecting = AtomicBoolean(false)
    /** Bumped per connect so a stale read loop can't drive the current session. */
    private val sessionGeneration = AtomicInteger(0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @Suppress("DEPRECATION") // the file-URL setters are the only way to pin these off pre-30
    fun setupTerminal() {
        terminalWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The terminal is a local asset that talks to the app only through the JS bridge, so
            // it never needs the network or the filesystem. Pinned explicitly rather than relying
            // on the platform defaults, which is what the page's CSP assumes.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
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
        private const val READ_BUF_SIZE = 8192
        /** A session that died this fast will very likely die again — brake the retry. */
        private const val SHORT_SESSION_MS = 3_000L
        private const val SHORT_SESSION_FLOOR_MS = 2_000L
        /** A session that stayed up this long counts as healthy, so the attempt ladder resets. */
        private const val STABLE_SESSION_MS = 60_000L
        /** Typos happen; an unbounded retry loop on a key the user cannot open does not help. */
        private const val MAX_PASSPHRASE_PROMPTS = 3
        /** Backstop for UI that never answers at all, so a connect cannot hang forever. */
        private const val PASSPHRASE_PROMPT_TIMEOUT_MS = 110_000L

        /**
         * JSch failure messages that mean "this will fail exactly the same way next time".
         * Matched case-insensitively against the message chain.
         *
         * "Auth fail" covers a wrong password and a key the server refused alike; "USERAUTH fail"
         * and "invalid privatekey" are what a corrupt or wrongly-encrypted key produces.
         */
        private val FATAL_AUTH_MARKERS = arrayOf("auth fail", "userauth fail", "invalid privatekey")
        private val FATAL_HOST_KEY_MARKERS =
            arrayOf("hostkey has been changed", "reject hostkey", "unknownhostkey")

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
            // Double-tap state
            var lastTapTime = 0L
            var lastTapX = 0f
            var lastTapY = 0f
            val doubleTapTimeout = 300L
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

                        // Double-tap detection for word selection
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            !was2f && !was1fScroll && !wasLongPress) {
                            val now = System.currentTimeMillis()
                            val tapDx = event.x - lastTapX
                            val tapDy = event.y - lastTapY
                            val tapDist = tapDx * tapDx + tapDy * tapDy
                            if (now - lastTapTime < doubleTapTimeout && tapDist < deadZone * deadZone * 16) {
                                // Double-tap: select word at CSS pixel coords
                                val cssX = event.x / density
                                val cssY = event.y / density
                                webView.evaluateJavascript("selectWordAt($cssX,$cssY)", null)
                                lastTapTime = 0L
                                return@setOnTouchListener true
                            }
                            lastTapTime = now
                            lastTapX = event.x
                            lastTapY = event.y
                        }

                        false
                    }
                    else -> false
                }
            }
        }
    }

    fun connect(server: SshServer) {
        currentServer = server
        reconnectAttempts.set(0)
        stopped = false
        fatal = false
        keyPassphrase = null
        // Set before anything can schedule a connect. registerDefaultNetworkCallback() below
        // delivers onAvailable for the *current* network the moment it is registered, and
        // onAppResumed() fires if the user leaves to fetch the passphrase from a password
        // manager — either would otherwise reach doConnect() while the prompt is still on
        // screen, connect with a null passphrase, and have JSch's "invalid privatekey" latch
        // `fatal`, killing auto-reconnect for the rest of this manager's life.
        awaitingPassphrase = true
        // Resolve the passphrase before the first attempt, not per attempt: prompting has to
        // happen while the user is actually looking at the app, and it must not run on the main
        // thread. Everything after this — retries, network edges, resume — reuses the cached value.
        scope.launch {
            val ok = try {
                resolveKeyPassphrase(server)
            } finally {
                awaitingPassphrase = false
            }
            registerNetworkCallback()
            if (ok) doConnect(server)
        }
    }

    /**
     * Work out the passphrase for [server]'s private key, caching it in [keyPassphrase].
     *
     * Returns false when the key is encrypted and no usable passphrase was obtained; that is
     * fatal, not retryable — another attempt without a passphrase fails identically, and in the
     * background it would loop forever behind a dialog nobody can see.
     */
    private suspend fun resolveKeyPassphrase(server: SshServer): Boolean {
        if (server.authMethod != SshServer.AuthMethod.KEY || server.privateKey.isBlank()) return true

        val encrypted = JschFactory.isKeyEncrypted(server.privateKey)
        val stored = server.keyPassphrase
        // A remembered passphrase that no longer opens the key (key replaced, passphrase changed)
        // falls through to the prompt rather than dying as an opaque "invalid privatekey".
        if (stored.isNotBlank() &&
            (!encrypted || JschFactory.isPassphraseValid(server.privateKey, stored))
        ) {
            keyPassphrase = stored
            return true
        }
        if (!encrypted) return true

        for (attempt in 0 until MAX_PASSPHRASE_PROMPTS) {
            val answer = askPassphrase(server, retry = attempt > 0) ?: break // cancelled
            if (JschFactory.isPassphraseValid(server.privateKey, answer)) {
                keyPassphrase = answer
                return true
            }
        }

        fatal = true
        FileLogger.w(TAG, "Not retrying: ${FatalCause.PASSPHRASE.message}")
        writeInfo("\r\n\u001b[31m${FatalCause.PASSPHRASE.message}\u001b[0m\r\n")
        notifyDisconnected(FatalCause.PASSPHRASE.message)
        return false
    }

    /**
     * Ask the UI for the key passphrase and wait for the answer.
     *
     * Suspends rather than blocking a thread: the wait can run into minutes, and a manager torn
     * down meanwhile (destroy() cancels the scope) must not leave a parked IO thread behind. Must
     * never be called from the main thread — the dialog is posted there.
     *
     * Returns null when the user cancelled, the dialog could not be shown (a finishing Activity
     * gives BadTokenException), or nobody answered in time.
     */
    private suspend fun askPassphrase(server: SshServer, retry: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        mainHandler.post {
            try {
                // complete() is a no-op after the first call, so a UI that answers twice is safe.
                onPassphraseRequired(server, retry) { value -> answer.complete(value) }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "Passphrase dialog could not be shown", t)
                answer.complete(null)
            }
        }
        return withTimeoutOrNull(PASSPHRASE_PROMPT_TIMEOUT_MS) { answer.await() }
    }

    private fun doConnect(server: SshServer) {
        if (!connecting.compareAndSet(false, true)) {
            FileLogger.d(TAG, "Connect already in flight, ignoring duplicate request")
            return
        }
        // Claim the generation before tearing anything down, so the old read loop's EOF is
        // recognised as stale and doesn't schedule a reconnect competing with this one.
        val myGen = sessionGeneration.incrementAndGet()
        scope.launch {
            var failure: String? = null
            var cause: FatalCause? = null
            // Per-attempt: a prompt that went unanswered last time must not colour this attempt's
            // verdict.
            hostKeyDenied = false
            hostKeyUnanswered = false
            try {
                // Reset xterm.js terminal state (exit alternate buffer, disable mouse mode)
                // so stale state from a previous session doesn't leak SGR sequences
                writeOutput("\u001b[?1049l\u001b[?1002l\u001b[?1003l\u001b[?1006l")
                writeInfo("Connecting to ${server.host}:${server.port}...\r\n")

                readJob?.cancel()
                readJob = null
                // A previous Session keeps its connect thread, socket and any
                // setPortForwardingL server socket alive until finalization, so re-binding a
                // fixed local forward below would fail "address already in use".
                dropTransport()

                if (server.useCloudflareProxy) writeInfo("Using Cloudflare Tunnel proxy...\r\n")

                // Host key is checked by JschFactory's HostKeyRepository *during* KEX, i.e.
                // before any credential leaves the device.
                val sess = JschFactory.newSession(
                    context, server, JschFactory.HostKeyPrompt(::promptHostKey), keyPassphrase
                )
                sess.connect()
                session = sess

                // Setup port forwarding
                for (pf in server.portForwards) {
                    try {
                        if (pf.type == "local" && pf.localPort > 0 && pf.remotePort > 0) {
                            sess.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
                            writeInfo("Port forward: L${pf.localPort} → ${pf.remoteHost}:${pf.remotePort}\r\n")
                        } else if (pf.type == "remote" && pf.localPort > 0 && pf.remotePort > 0) {
                            sess.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
                            writeInfo("Port forward: R${pf.remotePort} → ${pf.remoteHost}:${pf.localPort}\r\n")
                        }
                    } catch (e: Exception) {
                        writeInfo("\u001b[33mPort forward failed: ${pf}: ${e.message}\u001b[0m\r\n")
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
                lastConnectedAt = System.currentTimeMillis()

                // xterm.js reported its size long before this handshake finished; without the
                // replay the PTY stays at the default 80x24 and every redraw is mangled.
                if (lastCols > 0 && lastRows > 0) applyPtySize(lastCols, lastRows)

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
                    val stream = inputStream
                    // One decoder for the whole session. Decoding each 8K read standalone
                    // turned any multi-byte character straddling the read boundary into
                    // U+FFFD for good - diacritics, box drawing, CJK, emoji.
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    // Slack for the <=3 carry-over bytes of a split sequence. UTF-8 never
                    // yields more chars than input bytes, so this size can't overflow.
                    val pending = ByteBuffer.allocate(READ_BUF_SIZE + 8)
                    val chars = CharBuffer.allocate(READ_BUF_SIZE + 8)
                    try {
                        val buf = ByteArray(READ_BUF_SIZE)
                        while (isActive && ch.isConnected) {
                            val n = stream?.read(buf) ?: -1
                            if (n < 0) break
                            if (n == 0) { delay(50); if (ch.isClosed) break; continue }
                            pending.put(buf, 0, n)
                            pending.flip()
                            chars.clear()
                            decoder.decode(pending, chars, false)
                            pending.compact() // retains the incomplete tail for the next read
                            chars.flip()
                            if (chars.hasRemaining()) writeOutput(chars.toString())
                        }
                    } catch (e: CancellationException) {
                        throw e // cancellation is not a session failure to be logged and swallowed
                    } catch (e: Exception) {
                        FileLogger.d(TAG, "Read loop ended: $e")
                    } finally {
                        handleSessionEnded("Connection lost", myGen)
                    }
                }
            } catch (e: CancellationException) {
                throw e // a cancelled connect is a teardown, not a failure to report and retry
            } catch (e: Exception) {
                FileLogger.e(TAG, "SSH connection failed: $e")
                cause = classifyFatal(e)
                if (cause != null) {
                    fatal = true
                    FileLogger.w(TAG, "Not retrying: ${cause.message}")
                }
                failure = e.message ?: e.toString()
            } finally {
                connecting.set(false)
            }
            // Outside the guard: handleSessionEnded() may start the next connect immediately. The
            // generation test here only suppresses a stale terminal message; the teardown itself is
            // guarded inside handleSessionEnded().
            if (failure != null && sessionGeneration.get() == myGen) {
                val fatalCause = cause
                if (fatalCause != null) {
                    // Name the cause. Retrying these used to be silent - no notifyDisconnected ever
                    // ran - so a wrong password looked exactly like a flaky network forever.
                    writeInfo("\r\n\u001b[31m${fatalCause.message}\u001b[0m\r\n($failure)\r\n")
                    handleSessionEnded(fatalCause.message, myGen)
                } else {
                    writeInfo("\r\nConnection failed: $failure\r\n")
                    handleSessionEnded("Connection failed: $failure", myGen)
                }
            }
        }
    }

    /**
     * Blocking bridge to the confirmation dialog. The shared helper owns the main-thread hop, the
     * backstop timeout, and the case where showing the dialog throws (a finishing Activity gives
     * BadTokenException) — so the JSch connect thread can never wedge here.
     */
    private val hostKeyDialog = JschFactory.blockingPrompt { host, port, fingerprint, changed, old, respond ->
        onHostKeyVerify(host, port, fingerprint, changed, old) { ok ->
            // Record the *explicit* answer: the helper reports a timeout as false too, and only a
            // real rejection may be treated as fatal.
            hostKeyDenied = !ok
            respond(ok)
        }
    }

    /**
     * Host-key confirmation, called by JSch on its connect thread during key exchange.
     *
     * Must block: returning before the user decides would let the auth loop run - and the
     * password be sent - against an unverified host.
     */
    private fun promptHostKey(
        host: String,
        port: Int,
        fingerprint: String,
        changed: Boolean,
        oldFingerprint: String?
    ): Boolean {
        if (changed) {
            writeInfo("\r\n\u001b[31mWARNING: HOST KEY CHANGED!\u001b[0m\r\n")
            writeInfo("Expected: ${oldFingerprint ?: "(unknown)"}\r\n")
            writeInfo("Got:      $fingerprint\r\n")
        }
        val accepted = hostKeyDialog.confirm(host, port, fingerprint, changed, oldFingerprint)
        if (accepted) {
            writeInfo(if (changed) "Host key updated: $fingerprint\r\n" else "Host key saved: $fingerprint\r\n")
        } else if (hostKeyDenied) {
            fatal = true
            writeInfo("Connection rejected: host key not accepted.\r\n")
        } else {
            // Nobody answered. Keep this retryable, so that coming back to the app and tapping
            // "Trust & Connect" still works instead of every reconnect path being dead.
            hostKeyUnanswered = true
            writeInfo("Host key not confirmed in time — will retry.\r\n")
        }
        return accepted
    }

    /**
     * A failure no further attempt can fix. [message] is shown to the user: retrying these used to
     * be invisible, so a wrong password was indistinguishable from a flaky network.
     */
    private enum class FatalCause(val message: String) {
        AUTH("Authentication failed - check the username, password or key"),
        NO_AUTH_METHOD("Authentication failed - the server accepted none of the offered methods"),
        UNKNOWN_HOST("Host not found - check the hostname"),
        HOST_KEY("Host key not accepted"),
        PASSPHRASE("Passphrase required - the private key is encrypted")
    }

    /**
     * Classify [e], or null when another attempt might genuinely succeed. Retrying an auth failure
     * re-sends the credentials, which earns a fail2ban ban or an account lockout.
     */
    private fun classifyFatal(e: Throwable): FatalCause? {
        val text = StringBuilder()
        var cause: Throwable? = e
        var depth = 0
        var unknownHost = false
        while (cause != null && depth++ < 5) {
            if (cause is java.net.UnknownHostException) unknownHost = true
            // The class name as well as the message: JSch always sets a message, so a marker meant
            // to match a *type* (JSchUnknownHostKeyException) would never fire on the message alone.
            text.append(cause::class.java.simpleName).append('|')
            text.append(cause.message ?: "").append('|')
            cause = cause.cause
        }
        val msg = text.toString().lowercase()
        if (FATAL_AUTH_MARKERS.any { msg.contains(it) }) return FatalCause.AUTH
        // JSch's "Auth cancel" means it ran out of methods to try - no password for a password
        // server, no usable key - rather than anything the user cancelled.
        if (msg.contains("auth cancel")) return FatalCause.NO_AUTH_METHOD
        if (unknownHost || msg.contains("unknownhostexception")) {
            // Android throws the same exception when the resolver has no network to ask at all,
            // and *that* is transient. Only a name that failed to resolve over a working route
            // says anything about the name itself.
            return if (hasUsableNetwork()) FatalCause.UNKNOWN_HOST else null
        }
        // JSch reports "reject HostKey" both for a key the user refused and for one we never got to
        // ask about; only the former may stop the retries.
        if (!hostKeyUnanswered && FATAL_HOST_KEY_MARKERS.any { msg.contains(it) }) {
            return FatalCause.HOST_KEY
        }
        return null
    }

    /** False when we cannot tell, so an ambiguous failure stays retryable rather than fatal. */
    private fun hasUsableNetwork(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) {
        // Called from inside a catch block, so a throw here would escape the connect coroutine.
        FileLogger.w(TAG, "Could not read network state: $e")
        false
    }

    /** Close the transport without giving up the session - the reconnect path still owns it. */
    private fun dropTransport() {
        isConnected = false
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null; inputStream = null; outputStream = null
    }

    /**
     * Single funnel for "the session is gone": reconnect, or tell the UI it's over.
     *
     * [gen] is the generation of the session that ended. Claiming it here rather than testing it at
     * the call site is what makes the teardown safe: a stale read loop that passed a plain check and
     * was then preempted by a fresh connect would resume and disconnect the *new* session's channel
     * and transport. The CAS also makes this exactly-once per session, so the read loop's EOF and a
     * connect failure cannot both drive it.
     */
    private fun handleSessionEnded(reason: String, gen: Int) {
        if (!sessionGeneration.compareAndSet(gen, gen + 1)) return
        // Nothing else drops the transport on this path, and the connect coroutine can land here
        // with a live channel and session (a throw anywhere after isConnected = true), which would
        // otherwise leave a fully live shell behind with isConnected == false. Idempotent.
        dropTransport()
        val server = currentServer
        if (stopped || server == null) return
        if (fatal) { notifyDisconnected(reason); return }
        if (!context.sshAutoReconnect) { notifyDisconnected(reason); return }

        val fg = foreground
        // Consume the timestamp: a failure to *re-establish* must not keep re-reading the old
        // session's start time and grow an "uptime" that looks healthy.
        val connectedAt = lastConnectedAt
        lastConnectedAt = 0L
        val uptime = if (connectedAt == 0L) 0L else System.currentTimeMillis() - connectedAt
        // Only a session that actually stayed up clears the ladder. Resetting on every successful
        // connect made the attempt cap unreachable for a server whose shell exits immediately
        // (nologin, a failing ForceCommand): connect → reset → shell closes → retry, forever.
        if (uptime >= STABLE_SESSION_MS) reconnectAttempts.set(0)
        val attempt = reconnectAttempts.incrementAndGet()
        if (!ReconnectPolicy.shouldRetry(attempt, fg, context.sshReconnectAttempts)) {
            notifyDisconnected(reason)
            return
        }
        // Brake a session that came up and died again straight away.
        val floor = if (connectedAt != 0L && uptime < SHORT_SESSION_MS) SHORT_SESSION_FLOOR_MS else 0L
        val wait = ReconnectPolicy.delayMs(attempt, fg, floor)
        writeInfo("\r\n\u001b[33m$reason. Reconnecting (attempt $attempt)...\u001b[0m\r\n")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            doConnect(server)
        }
    }

    /** Reconnect now, dropping any pending backoff. Used by the network/lifecycle edges. */
    private fun reconnectNow(why: String) {
        val server = currentServer ?: return
        if (stopped || fatal || isConnected) return
        // connect() is still waiting on the passphrase dialog; connecting now would use a null
        // passphrase and poison `fatal` for good.
        if (awaitingPassphrase) return
        if (!context.sshAutoReconnect) return
        FileLogger.d(TAG, "Reconnecting now ($why)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch { doConnect(server) }
    }

    private fun notifyDisconnected(reason: String) {
        mainHandler.post { onDisconnected(reason) }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A usable route just appeared, so any backoff in flight is stale - reset the
                // ladder instead of waiting out a delay that was sized for a dead network.
                reconnectAttempts.set(0)
                if (!isConnected) reconnectNow("network available")
            }

            override fun onLost(network: Network) {
                // Kill the transport now. The read is parked in a blocking recv() that would
                // otherwise hang for the whole keepalive window (interval x 3) with the
                // terminal frozen; closing it EOFs the read and hands over to the reconnect.
                if (isConnected) {
                    FileLogger.d(TAG, "Network lost, dropping transport")
                    // Off this thread: disconnect() writes SSH close packets, and a write to a
                    // socket whose network just vanished can block for minutes (session.timeout is
                    // SO_TIMEOUT, which does not bound writes). Without a Handler these callbacks
                    // run on ConnectivityManager's process-wide shared thread, so blocking here
                    // would stall every connectivity callback in the process — including the
                    // onAvailable(cellular) edge this whole handover depends on.
                    //
                    // Pin the generation: if this lambda is delayed past a full reconnect it must
                    // not tear down the session that replaced the one it was queued for.
                    val gen = sessionGeneration.get()
                    scope.launch { if (sessionGeneration.get() == gen) dropTransport() }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            // Missing ACCESS_NETWORK_STATE or an OEM quirk: reconnect still works via
            // onAppResumed() and the backoff ladder, just without the network edge.
            FileLogger.w(TAG, "registerDefaultNetworkCallback failed: $e")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            FileLogger.w(TAG, "unregisterNetworkCallback failed: $e")
        }
    }

    /**
     * Called from Activity.onResume. Needed on top of the network callback, which only fires on
     * an edge: after a long stint in the background connectivity is usually already up, so no
     * edge ever arrives and a dead session would sit there forever.
     */
    fun onAppResumed() {
        foreground = true
        if (stopped || currentServer == null || isConnected) return
        reconnectAttempts.set(0)
        reconnectNow("app resumed")
    }

    fun onAppPaused() { foreground = false }

    fun sendInput(data: String) {
        scope.launch {
            val out = outputStream
            if (out == null) {
                // Reconnect window: the keystrokes are simply lost. Logged so it's diagnosable.
                FileLogger.d(TAG, "Dropped ${data.length} chars of input: no channel")
                return@launch
            }
            try {
                out.write(data.toByteArray())
                out.flush()
            } catch (e: Exception) {
                FileLogger.w(TAG, "Send failed: $e")
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) { lastCols = cols; lastRows = rows }
        applyPtySize(cols, rows)
    }

    private fun applyPtySize(cols: Int, rows: Int) {
        val ch = channel ?: return
        if (cols <= 0 || rows <= 0) return
        try {
            // xterm.js doesn't hand us its cell metrics, so use the WebView's own size once
            // laid out and fall back to a nominal 8x16 cell. Only cols/rows drive layout;
            // the pixel fields are advisory (a plain int field read is racy but harmless).
            val widthPx = terminalWebView.width.takeIf { it > 0 } ?: (cols * 8)
            val heightPx = terminalWebView.height.takeIf { it > 0 } ?: (rows * 16)
            ch.setPtySize(cols, rows, widthPx, heightPx)
        } catch (e: Exception) {
            // Swallowing this silently is what hid the 80x24 bug for so long.
            FileLogger.w(TAG, "setPtySize(${cols}x${rows}) failed: $e")
        }
    }

    fun disconnect() {
        stopped = true // prevent auto-reconnect
        currentServer = null
        // Nothing may reconnect after this, so the passphrase has no reason to stay in memory.
        // destroy() routes through here too.
        keyPassphrase = null
        sessionGeneration.incrementAndGet() // orphan the current read loop
        reconnectJob?.cancel(); reconnectJob = null
        readJob?.cancel(); readJob = null
        dropTransport()
        FileLogger.d(TAG, "SSH disconnected")
    }

    fun destroy() { unregisterNetworkCallback(); disconnect(); scope.cancel() }

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

    @Suppress("unused")
    inner class TerminalBridge {
        @JavascriptInterface
        fun onTerminalInput(data: String) { sendInput(data) }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            FileLogger.d(TAG, "Terminal ready: ${cols}x${rows}")
            val fontSize = context.terminalFontSize
            val scrollback = context.terminalScrollback
            if (fontSize != 14 || scrollback != 10000) {
                terminalWebView.post {
                    if (fontSize != 14) terminalWebView.evaluateJavascript("setFontSize($fontSize)", null)
                    if (scrollback != 10000) terminalWebView.evaluateJavascript("setScrollback($scrollback)", null)
                }
            }
            resize(cols, rows)
        }

        @JavascriptInterface
        fun onTerminalResize(cols: Int, rows: Int) { resize(cols, rows) }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            // No haptic here: OSC 52 writes reach this same method, so a remote host printing
            // escape sequences could buzz the device at will. The JS side vibrates explicitly on
            // the two user-initiated copy paths instead.
            TerminalClipboard.copy(context, text)
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
        fun getClipboard(): String = TerminalClipboard.read(context)

        /**
         * Gate for the OSC 52 *query* path: a remote host asking to read our clipboard.
         * Writes are harmless, reads let a compromised host exfiltrate the last thing copied.
         */
        @JavascriptInterface
        fun osc52ReadAllowed(): Boolean = context.sshOsc52ClipboardRead

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
