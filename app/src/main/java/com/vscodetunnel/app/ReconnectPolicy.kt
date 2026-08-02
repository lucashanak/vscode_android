package com.vscodetunnel.app

import kotlin.random.Random

/**
 * Backoff curves for SSH reconnection.
 *
 * Two deliberately different curves:
 *
 *  - **Foreground**: the user is watching, so the first retry is immediate and the cap is lower.
 *  - **Background**: retries are unbounded. A fixed attempt cap is the wrong shape here — once the
 *    cap is exhausted the session stays dead forever, which is exactly what happens today after
 *    three tries over ~6 seconds (typically before a wifi→cellular handover has even produced a
 *    route).
 *
 * The base curves are pure so they can be reasoned about and tested directly; jitter is applied
 * separately by [delayMs].
 */
object ReconnectPolicy {
    private const val FOREGROUND_CAP_MS = 30_000L
    private const val BACKGROUND_CAP_MS = 60_000L
    private const val BASE_MS = 2_000L
    private const val MAX_SHIFT = 5
    private const val JITTER_MS = 500

    /** Attempt 1 is immediate; then 2s, 4s, 8s, … capped at 30s. */
    fun foregroundBaseDelayMs(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val shift = (attempt - 2).coerceIn(0, MAX_SHIFT)
        return (BASE_MS shl shift).coerceAtMost(FOREGROUND_CAP_MS)
    }

    /** 2s, 4s, 8s, … capped at 60s. Never gives up. */
    fun backgroundBaseDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, MAX_SHIFT)
        return (BASE_MS shl shift).coerceAtMost(BACKGROUND_CAP_MS)
    }

    /**
     * Delay before [attempt], with jitter so that several sessions waking on the same network
     * event don't reconnect in lockstep.
     *
     * @param floorMs optional lower bound, used to brake a connection that keeps dying instantly.
     */
    fun delayMs(attempt: Int, foreground: Boolean, floorMs: Long = 0L): Long {
        val base = if (foreground) foregroundBaseDelayMs(attempt) else backgroundBaseDelayMs(attempt)
        val jittered = base + Random.nextInt(JITTER_MS)
        return maxOf(jittered, floorMs)
    }

    /**
     * Whether another attempt should be made.
     *
     * In the background this is always true — see the class note. In the foreground the user's
     * configured attempt limit applies, so they retain a way to make it stop.
     */
    fun shouldRetry(attempt: Int, foreground: Boolean, maxForegroundAttempts: Int): Boolean =
        if (foreground) attempt <= maxForegroundAttempts else true
}
