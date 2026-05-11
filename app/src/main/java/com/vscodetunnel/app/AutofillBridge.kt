package com.vscodetunnel.app

import android.graphics.Rect
import android.view.View
import android.view.autofill.AutofillManager
import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.GeckoSession

/**
 * Bridges GeckoView's per-session autofill events into Android's AutofillManager
 * so external autofill services (Bitwarden, 1Password, etc.) see <input> fields
 * inside the tunnel page and can offer credential suggestions.
 *
 * Without this, GeckoSession.Autofill.Delegate is null and no notifyViewEntered/
 * notifyViewExited reaches the platform — autofill providers stay silent.
 */
class AutofillBridge(private val view: View) : Autofill.Delegate {
    private val TAG = "AutofillBridge"
    private val mgr: AutofillManager? =
        view.context.getSystemService(AutofillManager::class.java)

    private fun nodeRect(node: Autofill.Node): Rect {
        return try {
            val r = node.dimensions
            if (r.width() > 0 && r.height() > 0) r
            else Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        } catch (_: Throwable) {
            Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        }
    }

    override fun onAutofill(session: GeckoSession, notification: Int, node: Autofill.Node?) {
        val m = mgr ?: return
        if (!m.isEnabled) return
        try {
            when (notification) {
                Autofill.Notify.SESSION_STARTED -> {
                    if (node != null) m.requestAutofill(view, node.id, nodeRect(node))
                }
                Autofill.Notify.SESSION_COMMITTED -> m.commit()
                Autofill.Notify.SESSION_CANCELED -> m.cancel()
                Autofill.Notify.NODE_FOCUSED -> {
                    if (node != null) m.notifyViewEntered(view, node.id, nodeRect(node))
                }
                Autofill.Notify.NODE_BLURRED -> {
                    if (node != null) m.notifyViewExited(view, node.id)
                }
                Autofill.Notify.NODE_ADDED,
                Autofill.Notify.NODE_REMOVED,
                Autofill.Notify.NODE_UPDATED -> { /* structure changes handled via virtual structure */ }
            }
        } catch (t: Throwable) {
            FileLogger.w(TAG, "Autofill notify $notification failed", t)
        }
    }
}
