package com.vscodetunnel.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

class OverlayManager(
    private val geckoView: SuppressableGeckoView,
    private val webView: WebView,
    private val cursorView: View,
    private val floatingToggle: View,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val onBackToMenu: () -> Unit = {},
    /**
     * Reload the page the overlay is driving. Exposed on the keyboard toolbar because a wedged
     * VS Code is exactly when the user needs it, and the keyboard is what is on screen then.
     */
    private val onReloadPage: () -> Unit = {}
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    enum class InputTarget { VSCODE, SSH_TERMINAL }

    /** Send input to whichever terminal session is active (SSH or Mosh) */
    private fun sendToTerminal(data: String) {
        sshSessionManager?.sendInput(data) ?: moshSessionManager?.sendInput(data)
    }

    private var port: WebExtension.Port? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var cachedEffectiveDensity = 0f
    var isVisible = false
        private set

    // SSH/Mosh terminal routing
    var inputTarget = InputTarget.VSCODE
    var sshSessionManager: SshSessionManager? = null
    var moshSessionManager: MoshSessionManager? = null
    var sshTerminalWebView: WebView? = null
        set(value) { field = value; sshCursorX = -1f; sshCursorY = -1f } // reset cursor on new terminal
    // When true, content script keeps inputmode="none" even when overlay is hidden
    var alwaysSuppressInput = false
    // Cursor position for SSH terminal (in CSS px, initialized lazily to center)
    private var sshCursorX = -1f
    private var sshCursorY = -1f

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.setInitialScale(100)
        webView.setBackgroundColor(0xFF1E1E1E.toInt())
        webView.addJavascriptInterface(JSInterface(), "Android")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                // After page load, push key height settings and trigger resize
                val prefs = geckoView.context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                val compactH = prefs.getInt("compact_key_height", 82)
                val wideH = prefs.getInt("wide_key_height", 72)
                val tpSens = prefs.getInt("tp_sensitivity", 150)
                val tpScroll = prefs.getInt("tp_scroll_speed", 20)
                val tpInvert = prefs.getBoolean("tp_invert_scroll", true)
                webView.postDelayed({
                    webView.evaluateJavascript("if(typeof updateKeyHeight==='function')updateKeyHeight($compactH,$wideH)", null)
                    webView.evaluateJavascript("if(typeof updateTouchpad==='function')updateTouchpad($tpSens,$tpScroll,$tpInvert)", null)
                    webView.evaluateJavascript("if(typeof notifyHeight==='function')notifyHeight()", null)
                }, 300)
            }
        }
        webView.loadUrl("file:///android_asset/overlay-ui/overlay.html")
        // Cache effective density for cursor coordinate conversion
        val prefs = geckoView.context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val zoomPercent = prefs.getInt("vscode_zoom_percent", 100)
        cachedEffectiveDensity = geckoView.resources.displayMetrics.density * (zoomPercent / 100f)
    }

    fun show() {
        if (isVisible) return
        isVisible = true
        webView.visibility = View.VISIBLE
        if (inputTarget == InputTarget.VSCODE) {
            cursorView.visibility = View.VISIBLE
        }
        floatingToggle.visibility = View.GONE
        onVisibilityChanged(true)
        if (inputTarget == InputTarget.VSCODE) {
            sendToContentScript("overlayActive", JSONObject().put("active", true))
        }
        // Push current invert state to the overlay UI (in case page just loaded)
        val invertEnabled = geckoView.context.getSharedPreferences(
            "app_settings", android.content.Context.MODE_PRIVATE
        ).getBoolean("vscode_color_invert", false)
        applyInvertToOverlay(invertEnabled)
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        webView.visibility = View.GONE
        cursorView.visibility = View.GONE
        floatingToggle.visibility = View.VISIBLE
        onVisibilityChanged(false)
        if (inputTarget == InputTarget.VSCODE) {
            // Keep inputmode="none" in content script if always-suppress is on
            val keepActive = alwaysSuppressInput
            sendToContentScript("overlayActive", JSONObject().put("active", keepActive))
        }
    }

    /** Show only the cursor dot (for floating touchpad mode — no overlay keyboard) */
    fun showCursorOnly() {
        if (inputTarget == InputTarget.VSCODE) {
            // Center cursor on first show so it's not hidden at (0,0)
            if (cursorX == 0f && cursorY == 0f) {
                val parent = cursorView.parent as? View
                if (parent != null && parent.width > 0) {
                    cursorX = parent.width / 2f
                    cursorY = parent.height / 2f
                    cursorView.translationX = cursorX
                    cursorView.translationY = cursorY
                }
            }
            cursorView.visibility = View.VISIBLE
            sendToContentScript("overlayActive", JSONObject().put("active", true))
        }
        floatingToggle.visibility = View.GONE
    }

    /** Hide cursor dot when floating touchpad closes */
    fun hideCursorOnly() {
        cursorView.visibility = View.GONE
        floatingToggle.visibility = View.VISIBLE
        if (inputTarget == InputTarget.VSCODE) {
            val keepActive = alwaysSuppressInput
            sendToContentScript("overlayActive", JSONObject().put("active", keepActive))
        }
    }

    fun setPort(p: WebExtension.Port) {
        port = p
        FileLogger.d(TAG, "Content script port connected")
        p.setDelegate(object : WebExtension.PortDelegate {
            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                try {
                    val json = message as? org.json.JSONObject ?: return
                    when (json.optString("type")) {
                        "cursorType" -> {
                            val cursor = json.optString("cursor", "default")
                            geckoView.post { updateCursorType(cursor) }
                        }
                        // The page describing its own state. Logged as one line: with the tunnel
                        // confirmed healthy from a desktop, this is the only evidence that
                        // distinguishes "workbench never rendered" from "rendered then died".
                        "diag" -> FileLogger.d(TAG, "page diag: " +
                            "reason=${json.optString("reason")} " +
                            "readyState=${json.optString("readyState")} " +
                            // href matters: without it there is no way to tell a snapshot of the
                            // real page from one of some other document the script attached to.
                            "href=${json.optString("href")} " +
                            "online=${json.opt("online")} vis=${json.optString("visibility")} " +
                            "workbench=${json.opt("workbench")}/${json.opt("workbenchChildren")} " +
                            "bodyChildren=${json.opt("bodyChildren")} " +
                            "sw=${json.opt("swController")} " +
                            "dpr=${json.opt("dpr")} inner=${json.optString("inner")} " +
                            // A healthy load ends in DIV.vs-dark (the workbench) after DIV.loading
                            // (the painted splash); stopping at the splash is what the screenshots
                            // show. hosts names the request that never came back.
                            "bodyKids=${json.opt("bodyKids")} " +
                            "pw=${json.opt("pw")} hosts=${json.opt("hosts")} res=${json.opt("res")} " +
                            "text=\"${json.optString("text")}\" " +
                            "caches=${json.opt("caches")} " +
                            // idb=timeout is the one to look for: an open request that never
                            // settles is what microsoft/vscode#145647 blames for a blank workbench.
                            "idb=${json.opt("idb")} idbNames=${json.opt("idbNames")} " +
                            // auth/hosts/res are measured in the page's own world; pw=false means that
                            // answer never arrived, so they are unknown rather than zero.
                            "auth=${json.opt("auth")} " +
                            "texts=${json.optJSONArray("texts")} " +
                            "errors=${json.optJSONArray("errors")}")
                    }
                } catch (_: Exception) {}
            }
            override fun onDisconnect(port: WebExtension.Port) {
                if (this@OverlayManager.port === port) {
                    this@OverlayManager.port = null
                    FileLogger.d(TAG, "Content script port disconnected")
                }
            }
        })
        // Sync input suppression state when content script connects
        if (inputTarget == InputTarget.VSCODE && (isVisible || alwaysSuppressInput)) {
            sendToContentScript("overlayActive", JSONObject().put("active", true))
        }
        // Sync keepalive config so reconnected sessions respect current settings
        syncKeepalive()
        syncColorInvert()
    }

    /** Sync inputmode suppression state to content script (call after changing alwaysSuppressInput) */
    fun syncInputSuppression() {
        if (inputTarget != InputTarget.VSCODE) return
        val active = isVisible || alwaysSuppressInput
        sendToContentScript("overlayActive", JSONObject().put("active", active))
    }

    /** Send tunnel keepalive interval to content script (0 = disabled) */
    fun syncKeepalive() {
        val seconds = geckoView.context.getSharedPreferences(
            "app_settings", android.content.Context.MODE_PRIVATE
        ).getInt("tunnel_keepalive_interval", 30)
        sendToContentScript("keepalive", JSONObject().put("seconds", seconds))
    }

    /** Send current color-invert state to content script AND overlay UI */
    fun syncColorInvert() {
        val enabled = geckoView.context.getSharedPreferences(
            "app_settings", android.content.Context.MODE_PRIVATE
        ).getBoolean("vscode_color_invert", false)
        sendToContentScript("colorInvert", JSONObject().put("enabled", enabled))
        applyInvertToOverlay(enabled)
    }

    /** Toggle color inversion (for sunlight readability). Persists across sessions. */
    fun toggleColorInvert() {
        val prefs = geckoView.context.getSharedPreferences(
            "app_settings", android.content.Context.MODE_PRIVATE
        )
        val newState = !prefs.getBoolean("vscode_color_invert", false)
        prefs.edit().putBoolean("vscode_color_invert", newState).apply()
        sendToContentScript("colorInvert", JSONObject().put("enabled", newState))
        applyInvertToOverlay(newState)
    }

    private fun applyInvertToOverlay(enabled: Boolean) {
        webView.post {
            webView.evaluateJavascript(
                "if(typeof applyInvert==='function')applyInvert($enabled)", null
            )
        }
    }

    private fun injectTerminalMouse(type: String, x: Float, y: Float, button: Int = 0) {
        val wv = sshTerminalWebView ?: return
        wv.post {
            wv.evaluateJavascript("if(typeof injectMouse==='function')injectMouse('$type',$x,$y,$button,0)", null)
        }
    }

    private fun injectTerminalDragSelect(x: Float, y: Float) {
        val wv = sshTerminalWebView ?: return
        wv.post {
            wv.evaluateJavascript("if(typeof injectDragSelect==='function')injectDragSelect($x,$y)", null)
        }
    }

    private fun injectTerminalWheel(x: Float, y: Float, deltaY: Float) {
        val wv = sshTerminalWebView ?: return
        wv.post {
            wv.evaluateJavascript("if(typeof injectWheel==='function')injectWheel($x,$y,$deltaY)", null)
        }
    }

    fun sendToContentScript(type: String, data: JSONObject) {
        try {
            data.put("type", type)
            port?.postMessage(data)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to send to content script: $e")
        }
    }

    /**
     * Ask the page to describe itself; the answer arrives asynchronously and is logged as
     * `page diag:`.
     *
     * Worth calling immediately *before* a recovery reload — that snapshot is taken while the
     * session is still broken, which is the only moment the evidence exists. After the reload the
     * page is fresh and tells us nothing about what went wrong.
     *
     * A no-op when the port is down, which is itself diagnostic: a content script that never
     * connected means the page never got far enough to run one.
     */
    fun requestDiag(reason: String) {
        if (port == null) {
            FileLogger.d(TAG, "diag requested ($reason) but no content script port — page never loaded one")
            return
        }
        sendToContentScript("diagRequest", JSONObject().put("reason", reason))
    }

    private fun updateCursor(dx: Float, dy: Float) {
        val parent = cursorView.parent as? View ?: return
        cursorX = (cursorX + dx).coerceIn(0f, parent.width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, parent.height.toFloat())
        if (currentCursorType == "default" || currentCursorType == "pointer") {
            // Arrow/pointer: hotspot at top-left
            cursorView.translationX = cursorX
            cursorView.translationY = cursorY
        } else {
            // Resize/text: hotspot at center
            cursorView.translationX = cursorX - cursorView.width / 2f
            cursorView.translationY = cursorY - cursorView.height / 2f
        }
    }

    private var currentCursorType = "default"

    private fun updateCursorType(type: String) {
        if (type == currentCursorType) return
        currentCursorType = type
        val resId = when (type) {
            "col-resize" -> R.drawable.cursor_col_resize
            "row-resize" -> R.drawable.cursor_row_resize
            "text" -> R.drawable.cursor_text
            "pointer" -> R.drawable.cursor_pointer
            else -> R.drawable.cursor_dot
        }
        (cursorView as? android.widget.ImageView)?.setImageResource(resId)
    }

    private fun cursorCssPx(): Pair<Float, Float> {
        val d = if (cachedEffectiveDensity > 0f) cachedEffectiveDensity
                else geckoView.resources.displayMetrics.density
        return Pair(cursorX / d, cursorY / d)
    }

    // ======================== NATIVE MOUSE INJECTION ========================
    // Inject MotionEvents through GeckoView's PanZoomController.
    // These produce isTrusted:true JS events (unlike synthetic content script events).
    private var nativeMouseDown = false
    private var nativeDownTime = 0L

    private fun makeMouseProps() = MotionEvent.PointerProperties().apply {
        id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
    }
    private fun makeMouseCoords(x: Float, y: Float, pressure: Float = 1f) =
        MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 1f }

    private fun injectMouseEvent(action: Int, x: Float, y: Float, buttonState: Int) {
        val session = geckoView.session ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (action == MotionEvent.ACTION_DOWN) now else nativeDownTime
        if (action == MotionEvent.ACTION_DOWN) nativeDownTime = now
        val event = MotionEvent.obtain(dt, now, action, 1,
            arrayOf(makeMouseProps()), arrayOf(makeMouseCoords(x, y, if (action == MotionEvent.ACTION_UP) 0f else 1f)),
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        session.panZoomController.onTouchEvent(event)
        event.recycle()
    }

    private fun injectHoverMove(x: Float, y: Float) {
        val session = geckoView.session ?: return
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1,
            arrayOf(makeMouseProps()), arrayOf(makeMouseCoords(x, y, 0f)),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        session.panZoomController.onMotionEvent(event)
        event.recycle()
    }

    private fun injectScroll(x: Float, y: Float, deltaY: Float) {
        val session = geckoView.session ?: return
        val now = SystemClock.uptimeMillis()
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -deltaY)
        }
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
            arrayOf(makeMouseProps()), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        session.panZoomController.onMotionEvent(event)
        event.recycle()
    }

    // Map overlay key names to terminal escape sequences
    private fun keyToTerminalSequence(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String? {
        val base = when (key) {
            "Enter" -> "\r"
            "Bksp" -> "\u007F"
            "Tab" -> "\t"
            "Esc" -> "\u001B"
            "Space" -> " "
            "Up" -> "\u001B[A"
            "Down" -> "\u001B[B"
            "Right" -> "\u001B[C"
            "Left" -> "\u001B[D"
            "Home" -> "\u001B[H"
            "End" -> "\u001B[F"
            "PgUp" -> "\u001B[5~"
            "PgDn" -> "\u001B[6~"
            "Del" -> "\u001B[3~"
            "Ins" -> "\u001B[2~"
            "F1" -> "\u001BOP"
            "F2" -> "\u001BOQ"
            "F3" -> "\u001BOR"
            "F4" -> "\u001BOS"
            "F5" -> "\u001B[15~"
            "F6" -> "\u001B[17~"
            "F7" -> "\u001B[18~"
            "F8" -> "\u001B[19~"
            "F9" -> "\u001B[20~"
            "F10" -> "\u001B[21~"
            "F11" -> "\u001B[23~"
            "F12" -> "\u001B[24~"
            else -> {
                if (key.length == 1) key else return null
            }
        }

        // Ctrl modifier: convert letter to control character
        if (ctrl && base.length == 1) {
            val ch = base[0]
            if (ch in 'a'..'z') return (ch - 'a' + 1).toChar().toString()
            if (ch in 'A'..'Z') return (ch - 'A' + 1).toChar().toString()
        }

        // Alt modifier: prepend ESC
        if (alt && base.length == 1) {
            return "\u001B$base"
        }

        return base
    }

    // ======================== PUBLIC CURSOR/MOUSE API ========================
    // Shared by JSInterface (overlay keyboard) and FloatingTouchpad

    private fun androidButton(button: Int): Int = when (button) {
        0 -> MotionEvent.BUTTON_PRIMARY
        1 -> MotionEvent.BUTTON_TERTIARY
        2 -> MotionEvent.BUTTON_SECONDARY
        else -> MotionEvent.BUTTON_PRIMARY
    }

    fun moveCursor(dx: Float, dy: Float) {
        FileLogger.d(TAG, "moveCursor dx=$dx dy=$dy target=$inputTarget")
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            val wv = sshTerminalWebView ?: return
            if (sshCursorX < 0) {
                val density = wv.resources.displayMetrics.density
                sshCursorX = wv.width / density / 2f
                sshCursorY = wv.height / density / 2f
            }
            val maxX = wv.width / wv.resources.displayMetrics.density.toFloat()
            val maxY = wv.height / wv.resources.displayMetrics.density.toFloat()
            sshCursorX = (sshCursorX + dx).coerceIn(0f, maxX)
            sshCursorY = (sshCursorY + dy).coerceIn(0f, maxY)
            injectTerminalMouse("mousemove", sshCursorX, sshCursorY)
            return
        }
        val density = geckoView.resources.displayMetrics.density
        geckoView.post {
            updateCursor(dx * density, dy * density)
            if (nativeMouseDown) {
                injectMouseEvent(MotionEvent.ACTION_MOVE, cursorX, cursorY, MotionEvent.BUTTON_PRIMARY)
            } else {
                injectHoverMove(cursorX, cursorY)
            }
        }
    }

    fun performClick(button: Int) {
        FileLogger.d(TAG, "performClick button=$button cursor=($cursorX,$cursorY)")
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            injectTerminalMouse("mousedown", sshCursorX, sshCursorY, button)
            injectTerminalMouse("mouseup", sshCursorX, sshCursorY, button)
            injectTerminalMouse("click", sshCursorX, sshCursorY, button)
            return
        }
        val btn = androidButton(button)
        geckoView.post {
            injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY, btn)
            injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        }
    }

    fun performDoubleClick() {
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            injectTerminalMouse("mousedown", sshCursorX, sshCursorY)
            injectTerminalMouse("mouseup", sshCursorX, sshCursorY)
            injectTerminalMouse("click", sshCursorX, sshCursorY)
            injectTerminalMouse("mousedown", sshCursorX, sshCursorY)
            injectTerminalMouse("mouseup", sshCursorX, sshCursorY)
            injectTerminalMouse("click", sshCursorX, sshCursorY)
            injectTerminalMouse("dblclick", sshCursorX, sshCursorY)
            return
        }
        geckoView.post {
            val btn = MotionEvent.BUTTON_PRIMARY
            injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY, btn)
            injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0)
            injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY, btn)
            injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        }
    }

    fun performMouseDown(button: Int) {
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            injectTerminalMouse("mousedown", sshCursorX, sshCursorY, button)
            return
        }
        geckoView.post {
            nativeMouseDown = true
            injectMouseEvent(MotionEvent.ACTION_DOWN, cursorX, cursorY, androidButton(button))
        }
    }

    fun performMouseUp(button: Int) {
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            injectTerminalMouse("mouseup", sshCursorX, sshCursorY, button)
            return
        }
        geckoView.post {
            nativeMouseDown = false
            injectMouseEvent(MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        }
    }

    fun performScroll(deltaY: Float) {
        if (inputTarget == InputTarget.SSH_TERMINAL) {
            injectTerminalWheel(sshCursorX, sshCursorY, deltaY)
            return
        }
        geckoView.post {
            injectScroll(cursorX, cursorY, deltaY)
        }
    }

    @Suppress("unused")
    inner class JSInterface {
        @JavascriptInterface
        fun sendChar(ch: String) {
            if (inputTarget == InputTarget.SSH_TERMINAL) {
                sendToTerminal(ch)
            } else {
                sendToContentScript("char", JSONObject().put("char", ch))
            }
        }

        @JavascriptInterface
        fun sendKey(json: String) {
            try {
                val obj = JSONObject(json)
                if (inputTarget == InputTarget.SSH_TERMINAL) {
                    val seq = keyToTerminalSequence(
                        obj.getString("key"),
                        obj.optBoolean("ctrl"),
                        obj.optBoolean("alt"),
                        obj.optBoolean("shift")
                    )
                    if (seq != null) sendToTerminal(seq)
                } else {
                    obj.put("type", "key")
                    port?.postMessage(obj)
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "sendKey error: $e")
            }
        }

        @JavascriptInterface
        fun pointerMove(dx: Float, dy: Float) = moveCursor(dx, dy)

        @JavascriptInterface
        fun mouseDown(button: Int) = performMouseDown(button)

        @JavascriptInterface
        fun mouseUp(button: Int) = performMouseUp(button)

        @JavascriptInterface
        fun click(button: Int) = performClick(button)

        @JavascriptInterface
        fun doubleClick() = performDoubleClick()

        @JavascriptInterface
        fun scroll(deltaY: Float) = performScroll(deltaY)

        @JavascriptInterface
        fun hideOverlay() {
            geckoView.post { hide() }
        }

        @JavascriptInterface
        fun toggleInvert() {
            geckoView.post { toggleColorInvert() }
        }

        @JavascriptInterface
        fun backToMenu() {
            geckoView.post { hide(); onBackToMenu() }
        }

        @JavascriptInterface
        fun reloadPage() {
            // Keep the overlay up: a reload is a recovery step, and hiding the keyboard would make
            // the user re-open it to carry on working.
            geckoView.post { onReloadPage() }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val cm = geckoView.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }

        @JavascriptInterface
        fun requestResize(cssPxHeight: Int) {
            webView.post {
                val density = webView.resources.displayMetrics.density
                // cssPxHeight from JS: actual measured content height in CSS pixels
                // If 0 or invalid, use WebView's contentHeight as fallback
                val cssH = if (cssPxHeight > 0) cssPxHeight else webView.contentHeight
                if (cssH <= 0) return@post
                val heightPx = (cssH * density).toInt()
                val lp = webView.layoutParams ?: return@post
                if (lp.height != heightPx) {
                    lp.height = heightPx
                    webView.layoutParams = lp
                }
            }
        }

        @JavascriptInterface
        fun haptic() {
            val ctx = geckoView.context
            // Check setting
            val prefs = ctx.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            if (!prefs.getBoolean("haptic_feedback", false)) return

            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(5)
            }
        }
    }
}
