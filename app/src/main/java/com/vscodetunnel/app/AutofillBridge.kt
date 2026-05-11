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
 * Without an attached delegate, GeckoSession does not notify the platform on
 * field focus changes — autofill providers stay silent.
 */
class AutofillBridge(private val view: View) : Autofill.Delegate {
    private val TAG = "AutofillBridge"
    private val mgr: AutofillManager? =
        view.context.getSystemService(AutofillManager::class.java)

    private fun rectOf(node: Autofill.Node): Rect {
        val r = node.screenRect
        return if (r != null && r.width() > 0 && r.height() > 0) r
            else Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
    }

    override fun onSessionStart(session: GeckoSession) {
        // No node info yet; AutofillManager will be notified on first node focus.
    }

    override fun onSessionCommit(
        session: GeckoSession,
        node: Autofill.Node,
        data: Autofill.NodeData
    ) {
        try { mgr?.commit() } catch (t: Throwable) { FileLogger.w(TAG, "commit failed", t) }
    }

    override fun onSessionCancel(session: GeckoSession) {
        try { mgr?.cancel() } catch (t: Throwable) { FileLogger.w(TAG, "cancel failed", t) }
    }

    override fun onNodeFocus(
        session: GeckoSession,
        focused: Autofill.Node,
        data: Autofill.NodeData
    ) {
        val m = mgr ?: return
        if (!m.isEnabled) return
        try {
            m.notifyViewEntered(view, data.id, rectOf(focused))
        } catch (t: Throwable) {
            FileLogger.w(TAG, "notifyViewEntered failed", t)
        }
    }

    override fun onNodeBlur(
        session: GeckoSession,
        prev: Autofill.Node,
        data: Autofill.NodeData
    ) {
        val m = mgr ?: return
        if (!m.isEnabled) return
        try {
            m.notifyViewExited(view, data.id)
        } catch (t: Throwable) {
            FileLogger.w(TAG, "notifyViewExited failed", t)
        }
    }
}
