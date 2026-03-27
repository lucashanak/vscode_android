package com.vscodetunnel.app

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.mozilla.geckoview.GeckoView

class SuppressableGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GeckoView(context, attrs) {

    var suppressIME = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (suppressIME) return null
        return super.onCreateInputConnection(outAttrs)
    }

    override fun onCheckIsTextEditor(): Boolean {
        if (suppressIME) return false
        return super.onCheckIsTextEditor()
    }
}
