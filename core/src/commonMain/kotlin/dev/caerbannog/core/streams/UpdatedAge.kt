package dev.caerbannog.core.streams

/** The "Updated N s ago" text of the app bar, one formatter for the stream list and the stream screen. */
object UpdatedAge {
    private const val MILLIS_PER_SECOND = 1000L

    /** Null when [updatedAtMillis] is null (nothing loaded yet); whole seconds (cut, not rounded), never negative. */
    fun text(nowMillis: Long, updatedAtMillis: Long?): String? {
        if (updatedAtMillis == null) return null
        val seconds = (nowMillis - updatedAtMillis).coerceAtLeast(0L) / MILLIS_PER_SECOND
        return "Updated $seconds s ago"
    }
}
