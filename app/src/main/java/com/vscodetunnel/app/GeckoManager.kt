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

    /**
     * Base domain of the editor itself. Note the workbench bundle lives on `main.vscode-cdn.net`,
     * a separate base domain, which is what lets a scoped clear here spare the expensive assets.
     */
    private const val TUNNEL_BASE_DOMAIN = "vscode.dev"

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

            val lang = prefs.getString("vscode_language", "") ?: ""
            if (lang.isNotEmpty()) {
                builder.locales(arrayOf(lang))
                FileLogger.d(TAG, "VSCode language: $lang")
            }

            val settings = builder.build()
            runtime = GeckoRuntime.create(context.applicationContext, settings)
            FileLogger.d(TAG, "GeckoRuntime created")
        }
        return runtime!!
    }

    fun setLocale(lang: String) {
        val locales = if (lang.isEmpty()) null else arrayOf(lang)
        runtime?.settings?.locales = locales
        FileLogger.d(TAG, "VSCode language updated: ${lang.ifEmpty { "auto" }}")
    }

    fun getOverlayExtension(): WebExtension? = overlayExtension

    fun createTunnelSession(): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentOverride(CHROME_USER_AGENT)
            .usePrivateMode(false)
            .build()
        return GeckoSession(settings)
    }

    /**
     * The big hammer, for the explicit "Clear VS Code cache" button only.
     *
     * Deliberately NOT used for automatic recovery. `DOM_STORAGES` maps to Gecko's
     * `CLEAR_DOM_QUOTA`, documented as "LocalStorage, IndexedDB, ServiceWorkers, DOM Cache and so
     * on" — so a single call also takes out the stored sign-in
     * (localStorage `stable.secrets.provider`), the Microsoft auth keys (`msal.db`), the service
     * worker's ~72 MB precache, and the workbench layout. It is a real reset, not a cache flush,
     * which is exactly why it belongs behind a button the user pressed on purpose.
     *
     * Cookies are preserved, but that does not save the sign-in on its own: the encrypted blob those
     * cookies unlock lives in localStorage, which this clears.
     */
    fun clearBrowsingData(context: Context, onDone: (() -> Unit)? = null) {
        val rt = runtime ?: getRuntime(context)
        val flags = StorageController.ClearFlags.ALL_CACHES or
            StorageController.ClearFlags.DOM_STORAGES or
            StorageController.ClearFlags.AUTH_SESSIONS
        FileLogger.w(TAG, "Full reset requested: clearing caches, DOM storage and auth sessions")
        rt.storageController.clearData(flags).accept({
            FileLogger.d(TAG, "GeckoView browsing data cleared")
            onDone?.invoke()
        }) { throwable ->
            FileLogger.e(TAG, "Failed to clear browsing data", throwable)
            onDone?.invoke()
        }
    }

    /**
     * Drops only vscode.dev's own HTTP/image cache — the 342 KB entry document and friends.
     *
     * Two things make this cheap where [clearBrowsingData] is expensive. It is scoped by base
     * domain, and the workbench bundle is served from `main.vscode-cdn.net`, a *different* base
     * domain, so the multi-megabyte commit-pinned assets are untouched. And it omits
     * `DOM_STORAGES`, so the sign-in, the auth keys and the service worker precache all survive.
     *
     * Used for the cold-start path, where there is no live page to reload.
     */
    fun clearTunnelDocumentCache(context: Context, onDone: (() -> Unit)? = null) {
        val rt = runtime ?: getRuntime(context)
        val flags = StorageController.ClearFlags.NETWORK_CACHE or
            StorageController.ClearFlags.IMAGE_CACHE
        rt.storageController.clearDataFromBaseDomain(TUNNEL_BASE_DOMAIN, flags).accept({
            FileLogger.d(TAG, "Cleared $TUNNEL_BASE_DOMAIN document cache (sign-in and CDN assets kept)")
            onDone?.invoke()
        }) { throwable ->
            FileLogger.e(TAG, "Failed to clear $TUNNEL_BASE_DOMAIN document cache", throwable)
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
