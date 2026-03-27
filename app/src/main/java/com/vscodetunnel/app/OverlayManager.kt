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
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "OverlayManager"
    }

    private var port: WebExtension.Port? = null
    private var cursorX = 0f
    private var cursorY = 0f
    var isVisible = false
        private set

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
        cursorView.visibility = View.VISIBLE
        floatingToggle.visibility = View.GONE
        onVisibilityChanged(true)
        sendToContentScript("overlayActive", JSONObject().put("active", true))
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        webView.visibility = View.GONE
        cursorView.visibility = View.GONE
        floatingToggle.visibility = View.VISIBLE
        onVisibilityChanged(false)
        sendToContentScript("overlayActive", JSONObject().put("active", false))
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
        // Sync current state
        if (isVisible) {
            sendToContentScript("overlayActive", JSONObject().put("active", true))
        }
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

    // Convert cursor position from native px to CSS px for content script
    private fun cursorCssPx(): Pair<Float, Float> {
        val density = geckoView.resources.displayMetrics.density
        return Pair(cursorX / density, cursorY / density)
    }

    @Suppress("unused")
    inner class JSInterface {
        @JavascriptInterface
        fun sendChar(ch: String) {
            sendToContentScript("char", JSONObject().put("char", ch))
        }

        @JavascriptInterface
        fun sendKey(json: String) {
            try {
                val obj = JSONObject(json)
                obj.put("type", "key")
                port?.postMessage(obj)
            } catch (e: Exception) {
                FileLogger.w(TAG, "sendKey error: $e")
            }
        }

        @JavascriptInterface
        fun pointerMove(dx: Float, dy: Float) {
            val density = geckoView.resources.displayMetrics.density
            geckoView.post {
                updateCursor(dx * density, dy * density)
                val (cx, cy) = cursorCssPx()
                sendToContentScript("pointerMove", JSONObject().put("x", cx).put("y", cy))
            }
        }

        @JavascriptInterface
        fun click(button: Int) {
            val (cx, cy) = cursorCssPx()
            sendToContentScript("click", JSONObject().put("button", button).put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun doubleClick() {
            val (cx, cy) = cursorCssPx()
            sendToContentScript("doubleClick", JSONObject().put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun scroll(deltaY: Float) {
            val (cx, cy) = cursorCssPx()
            sendToContentScript("scroll", JSONObject().put("deltaY", deltaY).put("x", cx).put("y", cy))
        }

        @JavascriptInterface
        fun hideOverlay() {
            geckoView.post { hide() }
        }
    }
}
