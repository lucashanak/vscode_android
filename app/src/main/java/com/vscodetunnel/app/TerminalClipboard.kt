package com.vscodetunnel.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Single entry point for terminal clipboard access.
 *
 * Both terminal bridges (SshSessionManager.TerminalBridge and the inline mosh bridge in
 * MainActivity) route through here so clipboard behaviour can't drift between them.
 */
object TerminalClipboard {
    private const val TAG = "TerminalClipboard"
    private const val LABEL = "terminal"

    /** API 33+ constant; the literal key is honoured on older releases too. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Write [text] to the system clipboard.
     *
     * Always posts to the main thread: ClipboardManager.setPrimaryClip() silently no-ops on
     * other threads, and @JavascriptInterface callbacks arrive on the WebView JavaBridge thread.
     *
     * Set [sensitive] for secrets (private keys, passwords) so the system omits the content from
     * the copy-preview overlay and clipboard history.
     */
    fun copy(context: Context, text: String, sensitive: Boolean = false) {
        mainHandler.post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(LABEL, text)
                if (sensitive) {
                    val extras = clip.description.extras ?: PersistableBundle()
                    extras.putBoolean(EXTRA_IS_SENSITIVE, true)
                    clip.description.extras = extras
                }
                cm.setPrimaryClip(clip)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Clipboard write failed: $e")
            }
        }
    }

    /**
     * Read the system clipboard. Safe to call from the JavaBridge thread — getPrimaryClip() has
     * no main-thread requirement.
     *
     * Returns "" when the clipboard is empty or unreadable. Note that since API 29 only the
     * focused app may read the clipboard, so this returns "" when called while backgrounded.
     */
    fun read(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) {
            FileLogger.w(TAG, "Clipboard read failed: $e")
            ""
        }
    }
}
