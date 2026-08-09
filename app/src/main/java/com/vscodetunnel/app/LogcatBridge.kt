package com.vscodetunnel.app

/**
 * Copies Gecko's own log lines out of logcat and into the app's log file.
 *
 * This exists because of a blind spot that took the blank-workbench investigation a long time to
 * find. The failing element is now identified precisely — an inline `<script type=module>` that
 * imports the workbench bundle fires an `error` event — but *why* its module graph fails is not
 * visible to any page-side listener. A module that fails to fetch, fails a CORS check or fails to
 * parse raises no window `error` event, no unhandled rejection and no CSP violation, and it is not
 * reported through the page's `console` object either: the browser logs it itself, exactly like a
 * CSP violation. A content script cannot reach that, however early it attaches.
 *
 * Gecko does emit those messages, to logcat, and [GeckoManager] has had `consoleOutput(true)` set
 * all along. Nothing was reading them. An app may read its own logs without any permission, and
 * GeckoView's content processes (`:tab*`, `:gpu`) share this app's UID, so the reader in the main
 * process sees them too.
 *
 * Two constraints shape the filter. Our own tags must never be copied: [FileLogger] also writes
 * through `android.util.Log`, so echoing a tag we own would feed the file back into itself — and
 * `GeckoManager` starts with "Gecko", so a naive prefix match would do exactly that. And the volume
 * is capped, because this content ends up in a file the user hands to someone else: Gecko's console
 * relays page output, which on a third-party page is not ours to collect in bulk.
 */
object LogcatBridge {
    private const val TAG = "GeckoLog"

    /**
     * Two budgets rather than one.
     *
     * Gecko is talkative at startup, and a single cap would happily be spent on routine chatter
     * before the one message worth having arrives — reading an empty result and concluding the page
     * was silent is the exact mistake this investigation has already made several times. So lines
     * that look like a load or security failure draw on their own budget and cannot be crowded out.
     */
    private const val MAX_GENERAL = 160
    private const val MAX_SIGNAL = 120
    private const val MAX_MSG = 280

    /**
     * What a failed module load looks like in Gecko's own words. Deliberately broad: the point is to
     * not miss the message, and a few extra lines cost far less than another blank reading.
     */
    private val SIGNAL = Regex(
        "module|SyntaxError|Loading failed|NS_ERROR|NS_BINDING|CORS|Cross-Origin|" +
            "Content Security Policy|out of memory|OOM|script.*(blocked|refused|error)",
        RegexOption.IGNORE_CASE
    )

    /** Tags this app writes itself. Copying any of them would loop through FileLogger. */
    private val OWN_TAGS = setOf(
        "GeckoManager", "VSCodeTunnel", "OverlayManager", "KeepAlive", "FileLogger",
        "SshSessionManager", "MoshSessionManager", "SftpManager", "TmuxManager",
        "JschFactory", "ServerStorage", "AutofillBridge", TAG
    )

    @Volatile private var started = false

    /**
     * Starts the reader once, in the main process only.
     *
     * Child processes would each spawn their own reader and see the same UID-wide buffer, so one
     * reader in the main process captures everything without duplicating it.
     */
    fun start() {
        if (started) return
        started = true
        Thread({ pump() }, "gecko-logcat-bridge").apply { isDaemon = true }.start()
    }

    private fun pump() {
        var general = 0
        var signal = 0
        try {
            // -v tag gives "E/Tag: message", the cheapest format to split. -T 1 starts at the tail
            // rather than replaying the whole buffer, so the log reflects this run.
            val proc = ProcessBuilder("logcat", "-v", "tag", "-T", "1")
                .redirectErrorStream(true)
                .start()
            FileLogger.d(TAG, "Gecko console bridge attached")
            proc.inputStream.bufferedReader().use { reader ->
                while (general < MAX_GENERAL || signal < MAX_SIGNAL) {
                    val line = reader.readLine() ?: break
                    val colon = line.indexOf(':')
                    if (line.length < 3 || line[1] != '/' || colon < 2) continue
                    val tag = line.substring(2, colon).trim()
                    if (tag in OWN_TAGS) continue
                    if (!tag.startsWith("Gecko") && tag != "Web Console" && tag != "Console") continue
                    val msg = line.substring(colon + 1).trim()
                    if (msg.isEmpty()) continue

                    val isSignal = SIGNAL.containsMatchIn(msg)
                    if (isSignal) {
                        if (signal >= MAX_SIGNAL) continue
                        signal++
                    } else {
                        if (general >= MAX_GENERAL) continue
                        general++
                    }
                    FileLogger.d(TAG, "${line[0]} ${if (isSignal) "* " else ""}$tag: ${msg.take(MAX_MSG)}")
                }
            }
            // Saying which budget ran out matters: a full signal budget means there is more to see,
            // whereas a full general budget with signal=0 means the failure really is quiet.
            FileLogger.w(TAG, "Gecko console bridge stopped: general=$general/$MAX_GENERAL " +
                "signal=$signal/$MAX_SIGNAL")
            proc.destroy()
        } catch (t: Throwable) {
            // Reading own logs is normally permitted, but it is not worth a crash if a device or a
            // future platform version refuses. Saying so beats a silently empty log — that
            // ambiguity is what made the earlier readings so hard to interpret.
            FileLogger.w(TAG, "Gecko console bridge unavailable " +
                "(general=$general signal=$signal): ${t.message}")
        }
    }
}
