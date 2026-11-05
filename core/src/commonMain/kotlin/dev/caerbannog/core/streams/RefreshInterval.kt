package dev.caerbannog.core.streams

/** How often the stream list reloads by itself (decision D3): a closed set, not a free number. */
enum class RefreshInterval(val seconds: Int) {
    Off(0),
    Every5(5),
    Every10(10),
    Every30(30),
    Every60(60),
    ;

    companion object {
        /** The interval for [seconds], or null when it is not one of the allowed values. */
        fun fromSeconds(seconds: Int): RefreshInterval? = entries.firstOrNull { it.seconds == seconds }
    }
}

/** The default interval. A trial (D3): the one place to change it. */
val DEFAULT_REFRESH: RefreshInterval = RefreshInterval.Every5

/** Per-app settings of the stream list. The real implementation is the `settings.json` file in `data-rabbit`; tests use an in-memory fake. */
interface AppSettings {
    var streamListRefresh: RefreshInterval
}

/** Time source of the refresh timer and "updated N s ago"; a test passes virtual time. */
fun interface RefreshClock {
    fun nowMillis(): Long
}

/** Default page size of the in-memory paging of the stream list. */
const val DEFAULT_PAGE_SIZE: Int = 50
