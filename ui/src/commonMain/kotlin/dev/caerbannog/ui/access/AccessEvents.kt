package dev.caerbannog.ui.access

/**
 * M10-02: events out of the Access screen. Every one is asynchronous in the view model; read the result from the state. There is no method that
 * changes anything on the broker, on purpose: the Access view only reads (a new one needs the architect).
 */
interface AccessEvents {
    /** The screen is on show: true when it enters the composition, false when it leaves (D3: the view model reads only while shown). */
    fun setShown(shown: Boolean)

    fun refreshNow()

    fun retry()
}
