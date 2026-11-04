package dev.caerbannog.core.streams

private val SECONDS_TEXT = Regex("""(\d+)s""")
private const val MINUTE = 60L
private const val HOUR = 3600L
private const val DAY = 86_400L

/**
 * D9: the retention age as people read it. A seconds text (`604800s`) becomes the largest whole unit among D, h, m, s
 * (`7D`); a unit text (`7D`, `90m`, `1Y`) and anything not understood stay as the broker sent them. Seconds are never
 * turned into M or Y (their length is the broker's business). Blank when no age is set. This is also the copied text.
 */
fun Retention.displayMaxAge(): String {
    val text = maxAge ?: return ""
    val seconds = SECONDS_TEXT.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull() ?: return text
    return when {
        seconds == 0L -> "0s"
        seconds % DAY == 0L -> "${seconds / DAY}D"
        seconds % HOUR == 0L -> "${seconds / HOUR}h"
        seconds % MINUTE == 0L -> "${seconds / MINUTE}m"
        else -> "${seconds}s"
    }
}
