package com.vscodetunnel.app

import android.content.Context
// Using FileLogger instead of android.util.Log for crash debugging
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
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
        "(KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    fun getRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val builder = GeckoRuntimeSettings.Builder()
                .consoleOutput(true)
                .remoteDebuggingEnabled(true)

            // Custom DPI: lower density = more CSS pixels = more content visible
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val zoomPercent = prefs.getInt("vscode_zoom_percent", 100)
            if (zoomPercent != 100) {
                val systemDensity = context.resources.displayMetrics.density
                val newDensity = systemDensity * (zoomPercent / 100f)
                builder.displayDensityOverride(newDensity)
                FileLogger.d(TAG, "VSCode zoom: ${zoomPercent}%, density: $systemDensity → $newDensity")
            }

            val settings = builder.build()
            runtime = GeckoRuntime.create(context.applicationContext, settings)
            FileLogger.d(TAG, "GeckoRuntime created")
        }
        return runtime!!
    }

    fun getOverlayExtension(): WebExtension? = overlayExtension

    fun createTunnelSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentOverride(CHROME_USER_AGENT)
            .usePrivateMode(false)
            .build()
        return GeckoSession(settings)
    }

    fun clearBrowsingData(context: Context, onDone: (() -> Unit)? = null) {
        val rt = runtime ?: getRuntime(context)
        // Clear stale data but keep cookies (GitHub auth) and passwords
        val flags = StorageController.ClearFlags.CACHE or
            StorageController.ClearFlags.SERVICE_WORKERS or
            StorageController.ClearFlags.DOM_STORAGE or
            StorageController.ClearFlags.INDEXED_DB or
            StorageController.ClearFlags.OFFLINE_CACHE or
            StorageController.ClearFlags.SITE_SETTINGS
        rt.storageController.clearData(flags).accept({
            FileLogger.d(TAG, "GeckoView browsing data cleared")
            onDone?.invoke()
        }) { throwable ->
            FileLogger.e(TAG, "Failed to clear browsing data", throwable)
            onDone?.invoke()
        }
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
