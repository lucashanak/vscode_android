package com.vscodetunnel.app

import android.content.Context
// Using FileLogger instead of android.util.Log for crash debugging
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
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

    /** Where the commit-pinned workbench assets live — a different base domain from the editor. */
    private const val CDN_BASE_DOMAIN = "vscode-cdn.net"

    /**
     * Set once by MainActivity, which is the only place with the Activity a selection delegate needs.
     * Held here because every tunnel session has to receive it, and this is where they are made.
     */
    @Volatile
    var selectionBridge: SelectionBridge? = null

    private var runtime: GeckoRuntime? = null
    private var overlayExtension: WebExtension? = null

    @Volatile
    var extensionReady = false
        private set

    // Chrome UA for Android — vscode.dev expects a modern browser
    const val CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    /**
     * Gecko preferences, written out for `configFilePath` since GeckoView exposes no direct pref API.
     *
     * Currently empty, and deliberately so. Two prefs have occupied it and both were disproved on the
     * device, which is worth recording where the next hypothesis will be typed:
     *
     *   dom.script_loader.bytecode_cache.enabled=false — for the theory that a 17.7 MB script
     *     overruns the cache entry's alternate-data limit (bug 1448476) and leaves it corrupt. The
     *     workbench still wedged, and a streamed byte count of the cached bundle came back identical
     *     to a fresh copy: 17 874 565 both ways, the same figure a healthy desktop downloads.
     *
     *   dom.security.trusted_types.enabled=false — for the one difference then measured between this
     *     device and every environment where the workbench mounts. It worked exactly as intended:
     *     `_VSCODE_WEB_PACKAGE_TTP` disappeared, so vscode.dev took the same bootstrap branch as a
     *     working desktop. It still wedged.
     *
     * Neither is worth keeping — one costs script start-up time, the other is a security feature — so
     * both are gone rather than left behind as sediment.
     */
    private fun writeGeckoConfig(context: Context): java.io.File {
        val file = java.io.File(context.filesDir, "gecko-config.yaml")
        val yaml = """
            prefs:
              # Intentionally empty. Two prefs have been tried here and both were disproved:
              # dom.script_loader.bytecode_cache.enabled=false (the wedge persisted, and the cached
              # bundle measured byte-identical to a fresh copy) and
              # dom.security.trusted_types.enabled=false (_VSCODE_WEB_PACKAGE_TTP duly disappeared,
              # so the page took the same bootstrap branch as a working desktop, and it still wedged).
              # Neither is worth keeping: one costs script start-up time, the other is a security
              # feature. The file stays so the next hypothesis has somewhere to go.
        """.trimIndent() + "\n"
        return try {
            if (!file.exists() || file.readText() != yaml) file.writeText(yaml)
            FileLogger.d(TAG, "Gecko config written: no pref overrides (both hypotheses disproved)")
            file
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Failed to write Gecko config", t)
            file
        }
    }

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

            builder.configFilePath(writeGeckoConfig(context).absolutePath)

            val settings = builder.build()
            runtime = GeckoRuntime.create(context.applicationContext, settings)
            FileLogger.d(TAG, "GeckoRuntime created")

            val cb = settings.contentBlocking
            FileLogger.w(TAG, "ContentBlocking: etpLevel=${cb.enhancedTrackingProtectionLevel} " +
                "etpCategory=${cb.enhancedTrackingProtectionCategory} " +
                "antiTracking=${cb.antiTrackingCategories} " +
                "safeBrowsing=${cb.safeBrowsingCategories} " +
                "strictSocial=${cb.strictSocialTrackingProtection} " +
                "cookieBehavior=${cb.cookieBehavior}")
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
        session.contentBlockingDelegate = blockingLogger(label)
        // Adds the native long-press selection menu, which this app previously had none of, and gives
        // the paste path a trusted route to the system clipboard.
        selectionBridge?.let { session.selectionActionDelegate = it }
        return session
    }

    /**
     * Reports anything the browser refuses to load, and why.
     *
     * This is where the evidence now points. A `<script type="module">` pointing at the workbench
     * bundle fails on vscode.dev in 4-5 ms, with a cache-busted URL failing identically, and `hosts`
     * never rises above three — so the load is rejected locally, before any request is attempted, and
     * it is not the module map remembering an earlier failure. No CSP violation is reported either,
     * on a channel proven to work.
     *
     * Content blocking has exactly that shape: Enhanced Tracking Protection and Safe Browsing reject a
     * URI before the fetch and raise no CSP event. It would also account for what never fitted
     * before — an extension fetch of the same URL succeeding (extension requests are not filtered),
     * DiagServer's loopback copy loading faultlessly, desktop being fine, a reload changing nothing,
     * and a full clear helping. Most of all it accounts for the intermittency: blocklists update.
     *
     * The callback is native, so none of the routes that turned out to be closed — page console,
     * logcat, eval — are involved.
     */
    private fun blockingLogger(label: String) = object : ContentBlocking.Delegate {
        override fun onContentBlocked(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            FileLogger.w(TAG, "[$label] BLOCKED ${event.uri.take(140)} " +
                "antiTracking=${event.antiTrackingCategory} " +
                "safeBrowsing=${event.safeBrowsingCategory} " +
                "cookie=${event.cookieBehaviorCategory} blocking=${event.isBlocking}")
        }

        override fun onContentLoaded(session: GeckoSession, event: ContentBlocking.BlockEvent) {
            // Fires for content that matched a list but was allowed through. Logged because "matched
            // and allowed" versus "never matched" is the difference between a near miss and an
            // irrelevance, and this delegate is the only place either is visible.
            FileLogger.d(TAG, "[$label] blocking-allowed ${event.uri.take(100)} " +
                "antiTracking=${event.antiTrackingCategory}")
        }
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
    fun clearTunnelDocumentCache(context: Context, url: String? = null, onDone: (() -> Unit)? = null) {
        val rt = runtime ?: getRuntime(context)
        val flags = StorageController.ClearFlags.NETWORK_CACHE or
            StorageController.ClearFlags.IMAGE_CACHE
        // Scoped to whatever host is actually being opened. It used to be hardcoded to vscode.dev,
        // which quietly became wrong the moment a self-hosted editor on the user's own domain became
        // openable: the refresh would clear a domain that had nothing to do with the page.
        val domain = baseDomainOf(url) ?: TUNNEL_BASE_DOMAIN
        rt.storageController.clearDataFromBaseDomain(domain, flags).accept({
            FileLogger.d(TAG, "Cleared $domain document cache (sign-in and CDN assets kept)")
            onDone?.invoke()
        }) { throwable ->
            FileLogger.e(TAG, "Failed to clear $domain document cache", throwable)
            onDone?.invoke()
        }
    }

    /**
     * Registrable domain for a URL, good enough for GeckoView's base-domain scoping.
     *
     * Takes the last two labels, which is right for hanaktech.org and vscode.dev alike and wrong for
     * multi-label suffixes like .co.uk. Returning null on anything unparseable leaves the caller on
     * its previous behaviour rather than clearing something unintended — the failure mode that matters
     * here is clearing too much, not too little.
     */
    private fun baseDomainOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val host = java.net.URI(url).host ?: return null
            if (host.isEmpty() || host[0].isDigit()) return null   // an IP address has no base domain
            val parts = host.split('.')
            if (parts.size < 2) null else parts.takeLast(2).joinToString(".")
        } catch (_: Throwable) { null }
    }

    /**
     * Drops the CDN's cached assets — the multi-megabyte workbench bundle above all.
     *
     * The deliberate counterpart to [clearTunnelDocumentCache], which scopes itself to `vscode.dev`
     * precisely so these stay put. That was the right trade while the bundle was assumed healthy, but
     * it also means no automatic path has ever replaced this copy: reloads reuse it, the cold-start
     * clear cannot see it, and only a full reset — which costs the sign-in — has ever cleared it.
     *
     * So this is the missing middle rung. It costs a ~4.5 MB re-download and keeps DOM storage, the
     * sign-in and the auth keys untouched.
     */
    fun clearCdnAssetCache(context: Context, onDone: (() -> Unit)? = null) {
        val rt = runtime ?: getRuntime(context)
        val flags = StorageController.ClearFlags.NETWORK_CACHE or
            StorageController.ClearFlags.IMAGE_CACHE
        rt.storageController.clearDataFromBaseDomain(CDN_BASE_DOMAIN, flags).accept({
            FileLogger.w(TAG, "Cleared $CDN_BASE_DOMAIN asset cache (sign-in kept)")
            onDone?.invoke()
        }) { throwable ->
            FileLogger.e(TAG, "Failed to clear $CDN_BASE_DOMAIN asset cache", throwable)
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
