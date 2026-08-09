package com.vscodetunnel.app

/**
 * Copies Gecko's own log lines out of logcat and into the app's log file.
 *
 * This exists because of a blind spot that took the blank-workbench investigation a long time to
 * find. The failing element is identified — an inline `<script type=module>` that imports the
 * workbench bundle fires an `error` event — but *why* its module graph fails is not visible to any
 * page-side listener. A module that fails to fetch, fails a CORS check or fails to parse raises no
 * window `error` event, no unhandled rejection and no CSP violation, and it is not reported through
 * the page's `console` object either: the browser logs it itself, exactly like a CSP violation. A
 * content script cannot reach that, however early it attaches.
 *
 * Gecko does emit those messages, to logcat, and [GeckoManager] has had `consoleOutput(true)` set
 * all along. Nothing was reading them. An app may read its own logs without any permission, and
 * GeckoView's content processes (`:tab*`, `:gpu`) share this app's UID, so one reader in the main
 * process sees them all.
 *
 * **Buffered, not streamed.** The first version wrote straight through to [FileLogger] under a
 * per-process budget, and produced nothing at all on its first real outing: the main process had
 * started long before the user cleared the log, so the budget was already spent and every trace of
 * the reader had been cleared along with it. Worse, that failure was indistinguishable from "Gecko
 * said nothing" — the exact ambiguity this whole investigation keeps tripping over. So lines are
 * held in bounded ring buffers and written out at each snapshot, with counters that make an empty
 * result interpretable: `seen` proves the reader is alive, `gecko` proves the tag filter matches.
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
     * Signal lines keep the *earliest* seen, general lines the *latest*.
     *
     * A first module failure explains more than the tenth, whereas routine chatter is only useful as
     * context for the snapshot being taken.
     */
    private const val MAX_SIGNAL = 80
    private const val MAX_GENERAL = 40
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
        "JschFactory", "ServerStorage", "AutofillBridge", "CRASH", TAG
    )

    private val lock = Any()
    private val signalBuf = ArrayDeque<String>()
    private val generalBuf = ArrayDeque<String>()
    private var seen = 0L
    private var gecko = 0L
    private var dropped = 0L
    private var lastError: String? = null

    @Volatile private var reader: Thread? = null

    /**
     * Starts the reader if it is not already running, in the main process only.
     *
     * Safe to call repeatedly — [dump] calls it, so a reader that died is picked up at the next
     * snapshot rather than leaving the log quietly empty for the rest of the session.
     */
    fun start() {
        synchronized(lock) {
            val current = reader
            if (current != null && current.isAlive) return
            reader = Thread({ pump() }, "gecko-logcat-bridge").apply { isDaemon = true; start() }
        }
    }

    /**
     * Discards anything buffered from before a navigation.
     *
     * Necessary because the signal buffer keeps the *earliest* lines: Gecko is talkative while it
     * starts up, and without this the 80 slots could be filled by launch-time chatter before the page
     * that matters even begins loading — leaving the one message worth having as a `dropped` count.
     * The cumulative `seen`/`gecko` counters are left alone; they are what proves the reader is alive.
     */
    fun onNavigation() {
        synchronized(lock) {
            signalBuf.clear()
            generalBuf.clear()
        }
    }

    /**
     * Writes what has been collected since the last call into the log file, and empties the buffers.
     *
     * Called when a page snapshot is taken, so the Gecko-side messages sit next to the page-side
     * report of the same moment.
     */
    fun dump(reason: String) {
        start()
        val signalLines: List<String>
        val generalLines: List<String>
        val header: String
        synchronized(lock) {
            signalLines = signalBuf.toList()
            generalLines = generalBuf.toList()
            signalBuf.clear()
            generalBuf.clear()
            header = "[$reason] seen=$seen gecko=$gecko signal=${signalLines.size} " +
                "general=${generalLines.size} dropped=$dropped alive=${reader?.isAlive == true}" +
                (lastError?.let { " error=$it" } ?: "")
        }
        // Always written, even with nothing to show: seen=0 means the reader never got any lines
        // (logcat refused), gecko=0 means it is reading but no tag matched, and a non-zero gecko with
        // no signal means Gecko really is quiet about this failure. Those are three different
        // findings and an empty log cannot tell them apart.
        FileLogger.d(TAG, header)
        signalLines.forEach { FileLogger.d(TAG, "* $it") }
        generalLines.forEach { FileLogger.d(TAG, "  $it") }
    }

    private fun pump() {
        try {
            // -v tag gives "E/Tag: message", the cheapest format to split. -T 1 starts at the tail
            // rather than replaying the whole buffer, so the log reflects this run.
            val proc = ProcessBuilder("logcat", "-v", "tag", "-T", "1")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    synchronized(lock) { seen++ }
                    val colon = line.indexOf(':')
                    if (line.length < 3 || line[1] != '/' || colon < 2) continue
                    val tag = line.substring(2, colon).trim()
                    if (tag in OWN_TAGS) continue
                    if (!tag.startsWith("Gecko") && tag != "Web Console" && tag != "Console") continue
                    val msg = line.substring(colon + 1).trim()
                    if (msg.isEmpty()) continue

                    val entry = "${line[0]} $tag: ${msg.take(MAX_MSG)}"
                    synchronized(lock) {
                        gecko++
                        if (SIGNAL.containsMatchIn(msg)) {
                            // Earliest wins: the first failure is the informative one.
                            if (signalBuf.size < MAX_SIGNAL) signalBuf.addLast(entry) else dropped++
                        } else {
                            // Latest wins: chatter matters only as context for the next snapshot.
                            if (generalBuf.size >= MAX_GENERAL) { generalBuf.removeFirst(); dropped++ }
                            generalBuf.addLast(entry)
                        }
                    }
                }
            }
            synchronized(lock) { lastError = "logcat stream ended" }
        } catch (t: Throwable) {
            // Reading own logs is normally permitted, but it is not worth a crash if a device or a
            // future platform version refuses. Recording why beats a silently empty buffer.
            synchronized(lock) { lastError = t.message ?: t.javaClass.simpleName }
        }
    }
}
