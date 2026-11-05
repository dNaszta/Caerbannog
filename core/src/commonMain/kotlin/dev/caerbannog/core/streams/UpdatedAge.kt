package dev.caerbannog.core.streams

/**
 * The "Updated N s ago" text of the app bar, one formatter for the stream list and the stream screen (M2-06 follow-up F-D).
 * COMPILE STUB (test developer): returns an empty string; the senior developer implements it (pinned by `UpdatedAgeTest`).
 */
object UpdatedAge {
    /** Null when [updatedAtMillis] is null (nothing loaded yet); whole seconds, never negative. */
    fun text(nowMillis: Long, updatedAtMillis: Long?): String? = ""
}
