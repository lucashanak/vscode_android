package com.vscodetunnel.app

import android.content.Context
// Using FileLogger instead of android.util.Log for crash debugging
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError

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

    fun createTunnelSession(label: String = "tunnel"): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .userAgentOverride(CHROME_USER_AGENT)
            .usePrivateMode(false)
            .build()
        val session = GeckoSession(settings)
        session.progressDelegate = loadProgressLogger(label)
        return session
    }

    /**
     * Reports the document load lifecycle, which is otherwise entirely invisible in the log.
     *
     * The distinction this exists to capture: a page that never fires `onPageStop` at all, versus one
     * that stops with `success=false`, versus one that loads clean and only *then* goes blank. Those
     * are three different bugs and the log could not previously tell them apart — see
     * docs/vscode-cache.md, where the root cause turned out to be in-page state after a successful
     * load, not the load itself.
     *
     * `progressDelegate` is a free slot: nothing else in the app assigns it, so taking it here
     * cannot clobber a delegate someone else wanted. Progress is logged per 25% bucket rather than
     * per callback — a hung load must leave a trail of how far it got without becoming a spammer.
     */
    private fun loadProgressLogger(label: String) = object : GeckoSession.ProgressDelegate {
        private var startedAt = 0L
        private var maxProgress = 0
        private var lastBucket = -1

        override fun onPageStart(session: GeckoSession, url: String) {
            startedAt = System.currentTimeMillis()
            maxProgress = 0
            lastBucket = -1
            FileLogger.d(TAG, "[$label] pageStart $url")
            // So the Gecko-side messages reported at this load's snapshot belong to this load, and
            // not to launch-time chatter that arrived first and filled the buffer.
            LogcatBridge.onNavigation()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            val ms = if (startedAt == 0L) -1 else System.currentTimeMillis() - startedAt
            FileLogger.d(TAG, "[$label] pageStop success=$success after=${ms}ms maxProgress=$maxProgress")
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (progress > maxProgress) maxProgress = progress
            val bucket = progress / 25
            if (bucket != lastBucket) {
                lastBucket = bucket
                FileLogger.d(TAG, "[$label] progress=$progress%")
            }
        }

        override fun onSecurityChange(
            session: GeckoSession,
            info: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            // Blocked mixed *active* content is a plausible cause of a workbench that renders empty,
            // and it produces no other log evidence at all.
            FileLogger.d(TAG, "[$label] security origin=${info.origin} secure=${info.isSecure} " +
                "mode=${info.securityMode} mixedActive=${info.mixedModeActive} " +
                "mixedPassive=${info.mixedModePassive}")
        }
    }

    /**
     * Wraps an existing [GeckoSession.NavigationDelegate] so load errors get logged, forwarding every
     * other callback untouched.
     *
     * A delegate is a single slot and MainActivity legitimately owns the navigation one (onNewSession,
     * onLoadRequest, onLocationChange), so this cannot be assigned here — the caller has to opt in:
     *
     *     session.navigationDelegate = GeckoManager.withLoadErrorLogging(
     *         object : GeckoSession.NavigationDelegate { ... })
     *
     * `onSubframeLoadRequest` is deliberately forwarded without logging: vscode.dev drives webviews
     * and the auth flow through iframes, so logging those would bury the useful lines.
     */
    fun withLoadErrorLogging(
        delegate: GeckoSession.NavigationDelegate,
        label: String = "tunnel"
    ): GeckoSession.NavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            FileLogger.e(TAG, "[$label] loadError uri=$uri " +
                "category=${errorCategoryName(error.category)}(${error.category}) code=${error.code}")
            return delegate.onLoadError(session, uri, error)
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) = delegate.onLocationChange(session, url, perms, hasUserGesture)

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) =
            delegate.onCanGoBack(session, canGoBack)

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) =
            delegate.onCanGoForward(session, canGoForward)

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = delegate.onLoadRequest(session, request)

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? = delegate.onSubframeLoadRequest(session, request)

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? =
            delegate.onNewSession(session, uri)
    }

    private fun errorCategoryName(category: Int) = when (category) {
        WebRequestError.ERROR_CATEGORY_SECURITY -> "security"
        WebRequestError.ERROR_CATEGORY_NETWORK -> "network"
        WebRequestError.ERROR_CATEGORY_CONTENT -> "content"
        WebRequestError.ERROR_CATEGORY_URI -> "uri"
        WebRequestError.ERROR_CATEGORY_PROXY -> "proxy"
        WebRequestError.ERROR_CATEGORY_SAFEBROWSING -> "safebrowsing"
        else -> "unknown"
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
