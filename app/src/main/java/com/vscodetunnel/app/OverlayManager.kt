package com.vscodetunnel.app

import android.annotation.SuppressLint
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
    private val onBackToMenu: () -> Unit = {}
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    enum class InputTarget { VSCODE, SSH_TERMINAL }

    private var port: WebExtension.Port? = null
    private var cursorX = 0f
    private var cursorY = 0f
    var isVisible = false
        private set

    // SSH terminal routing
    var inputTarget = InputTarget.VSCODE
    var sshSessionManager: SshSessionManager? = null
    // When true, content script keeps inputmode="none" even when overlay is hidden
    var alwaysSuppressInput = false

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.setBackgroundColor(0xFF1E1E1E.toInt())
        webView.addJavascriptInterface(JSInterface(), "Android")
        webView.loadUrl("file:///android_asset/overlay-ui/overlay.html")
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

    fun setPort(p: WebExtension.Port) {
        port = p
        FileLogger.d(TAG, "Content script port connected")
        p.setDelegate(object : WebExtension.PortDelegate {
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
    }

    /** Sync inputmode suppression state to content script (call after changing alwaysSuppressInput) */
    fun syncInputSuppression() {
        if (inputTarget != InputTarget.VSCODE) return
        val active = isVisible || alwaysSuppressInput
        sendToContentScript("overlayActive", JSONObject().put("active", active))
    }

    private fun sendToContentScript(type: String, data: JSONObject) {
        try {
            data.put("type", type)
            port?.postMessage(data)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to send to content script: $e")
        }
    }

    private fun updateCursor(dx: Float, dy: Float) {
        val parent = cursorView.parent as? View ?: return
        cursorX = (cursorX + dx).coerceIn(0f, parent.width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, parent.height.toFloat())
        cursorView.translationX = cursorX - cursorView.width / 2f
        cursorView.translationY = cursorY - cursorView.height / 2f
    }

    private fun cursorCssPx(): Pair<Float, Float> {
        val density = geckoView.resources.displayMetrics.density
        return Pair(cursorX / density, cursorY / density)
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

    @Suppress("unused")
    inner class JSInterface {
        @JavascriptInterface
        fun sendChar(ch: String) {
            if (inputTarget == InputTarget.SSH_TERMINAL) {
                sshSessionManager?.sendInput(ch)
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
                    if (seq != null) sshSessionManager?.sendInput(seq)
                } else {
                    obj.put("type", "key")
                    port?.postMessage(obj)
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "sendKey error: $e")
            }
        }

        @JavascriptInterface
        fun pointerMove(dx: Float, dy: Float) {
            if (inputTarget == InputTarget.SSH_TERMINAL) return
            val density = geckoView.resources.displayMetrics.density
            geckoView.post {
                updateCursor(dx * density, dy * density)
                val (cx, cy) = cursorCssPx()
                sendToContentScript("pointerMove", JSONObject().put("x", cx).put("y", cy))
            }
        }

        @JavascriptInterface
        fun click(button: Int) {
            if (inputTarget == InputTarget.SSH_TERMINAL) return
            val (cx, cy) = cursorCssPx()
            sendToContentScript("click", JSONObject().put("button", button).put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun doubleClick() {
            if (inputTarget == InputTarget.SSH_TERMINAL) return
            val (cx, cy) = cursorCssPx()
            sendToContentScript("doubleClick", JSONObject().put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun scroll(deltaY: Float) {
            if (inputTarget == InputTarget.SSH_TERMINAL) return
            val (cx, cy) = cursorCssPx()
            sendToContentScript("scroll", JSONObject().put("deltaY", deltaY).put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun hideOverlay() {
            geckoView.post { hide() }
        }

        @JavascriptInterface
        fun backToMenu() {
            geckoView.post { hide(); onBackToMenu() }
        }
    }
}
