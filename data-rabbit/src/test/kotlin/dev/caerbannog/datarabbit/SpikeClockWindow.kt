package dev.caerbannog.datarabbit

/**
 * Window for the broker's `first_timestamp` of a stream (ms epoch), used by the live spike Q3.
 *
 * Why a helper: the old check took "now" after the 1,000-message publish and the stats wait, so a busy host (the wait
 * can take minutes) pushed the first message out of the window and Q3 failed for a reason that has nothing to do with
 * the broker. The window is now anchored on a clock reading taken before publishing; the assert-time clock is not used.
 */
internal object SpikeClockWindow {
    /** The first message is published after [publishedAt], and at most the 600 s the test may take later. */
    fun firstTimestampWindow(publishedAt: Long): LongRange = (publishedAt - 5_000)..(publishedAt + 600_000)
}
