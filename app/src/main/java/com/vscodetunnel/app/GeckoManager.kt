package com.vscodetunnel.app

import android.content.Context
// Using FileLogger instead of android.util.Log for crash debugging
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension

object GeckoManager {
    private const val TAG = "GeckoManager"

    private var runtime: GeckoRuntime? = null
    private var overlayExtension: WebExtension? = null

    @Volatile
    var extensionReady = false
        private set

    // Chrome UA for Android — vscode.dev expects a modern browser
    const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun getRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val settings = GeckoRuntimeSettings.Builder()
                .consoleOutput(true)
                .remoteDebuggingEnabled(true)
                .build()
            runtime = GeckoRuntime.create(context.applicationContext, settings)
            FileLogger.d(TAG, "GeckoRuntime created")
        }
        return runtime!!
    }

    fun createTunnelSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentOverride(CHROME_USER_AGENT)
            .usePrivateMode(false)
            .build()
        return GeckoSession(settings)
    }

    fun installOverlayExtension(runtime: GeckoRuntime) {
        if (extensionReady) return

        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/overlay-extension/", "overlay@vscodetunnel.app")
            .accept({ extension ->
                if (extension != null) {
                    // Enable in all browsing modes
                    runtime.webExtensionController
                        .setAllowedInPrivateBrowsing(extension, true)
                        .accept({ updatedExt ->
                            overlayExtension = updatedExt
                            extensionReady = true
                            FileLogger.d(TAG, "Overlay extension ready: ${updatedExt?.id}")
                        }) { throwable ->
                            // Still usable even if this fails
                            overlayExtension = extension
                            extensionReady = true
                            FileLogger.w(TAG, "setAllowedInPrivateBrowsing failed, extension still usable", throwable)
                        }
                } else {
                    FileLogger.w(TAG, "ensureBuiltIn returned null extension")
                }
            }) { throwable ->
                FileLogger.e(TAG, "Failed to install overlay extension", throwable)
            }
    }
}
