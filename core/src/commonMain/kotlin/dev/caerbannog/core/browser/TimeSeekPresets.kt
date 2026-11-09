package dev.caerbannog.core.browser

/**
 * Time-mode start points of the message browser, as pure functions of "now" so they can be tested with a fake clock
 * (M7-02: the Time button starts one hour back; the presets are 5 minutes, 1 hour and 24 hours back).
 */
object TimeSeekPresets {
    const val FIVE_MINUTES_MS: Long = 5 * 60_000L
    const val ONE_HOUR_MS: Long = 3_600_000L
    const val ONE_DAY_MS: Long = 24 * 3_600_000L

    /** Where the Time start mode begins when the user switches to it. */
    const val DEFAULT_LOOKBACK_MS: Long = ONE_HOUR_MS

    /** The epoch millisecond [lookbackMs] before [nowMillis]. */
    fun since(nowMillis: Long, lookbackMs: Long): Long = nowMillis - lookbackMs

    fun defaultStart(nowMillis: Long): Long = since(nowMillis, DEFAULT_LOOKBACK_MS)
}
