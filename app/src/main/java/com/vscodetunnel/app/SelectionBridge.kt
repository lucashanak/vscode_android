package com.vscodetunnel.app

import android.app.Activity
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoSession

/**
 * Gives the page a trusted paste, and incidentally gives it a selection menu it never had.
 *
 * Pasting used to go through the content script, which typed the text in. That failed on GitHub's
 * device-code form for two reasons at once: the page never received a `paste` event, so it could not
 * split one paste across its per-character boxes, and its inputs are React-controlled, which ignores a
 * directly assigned value. One character reached the first box and nothing reached the rest.
 *
 * Dispatching a synthetic `ClipboardEvent` does not fix it, and that was worth finding out before
 * shipping: measured in Firefox, a page handler receives such an event and `preventDefault`s it, but
 * `clipboardData.getData('text/plain')` comes back **empty**. The page consumes the paste and gets
 * nothing, which would have looked like success from the content script's side — the exact silent
 * failure this app has produced too many times already.
 *
 * Only the browser can produce a trusted paste, and GeckoView exposes it: `Selection.pasteAsPlainText()`
 * reads the real system clipboard and delivers a real event. Reaching it needs a
 * `SelectionActionDelegate`, and [BasicSelectionActionDelegate] already implements one — including the
 * native long-press action bar, which this app was doing without entirely, since GeckoView installs no
 * delegate of its own. Subclassing therefore adds the selection menu rather than replacing it, and the
 * protected `mSelection` it maintains is exactly the handle the paste needs.
 */
class SelectionBridge(activity: Activity) : BasicSelectionActionDelegate(activity) {

    /**
     * The current selection or caret, if Gecko has reported one.
     *
     * Null when nothing is focused — no caret means nowhere to paste, and the caller falls back rather
     * than guessing.
     */
    val current: GeckoSession.SelectionActionDelegate.Selection?
        get() = mSelection

    /**
     * Pastes the system clipboard at the caret. Returns false if that was not possible.
     *
     * Prefers the plain-text action: what is being pasted here is a code or a password, and the HTML
     * flavour would let a page's paste handler receive markup that was never intended. A stale
     * Selection throws rather than misbehaving, so the throw is caught and reported as a plain false.
     */
    fun pasteAtCaret(): String {
        val sel = current ?: return "no-selection"
        return try {
            when {
                sel.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_PASTE_AS_PLAIN_TEXT) -> {
                    sel.pasteAsPlainText(); "native-plain"
                }
                sel.isActionAvailable(GeckoSession.SelectionActionDelegate.ACTION_PASTE) -> {
                    sel.paste(); "native"
                }
                else -> "paste-unavailable"
            }
        } catch (t: Throwable) {
            "native-failed:${t.javaClass.simpleName}"
        }
    }
}
