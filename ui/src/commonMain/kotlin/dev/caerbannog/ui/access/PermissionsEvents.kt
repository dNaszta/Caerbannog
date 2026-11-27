package dev.caerbannog.ui.access

/**
 * M10-03: events out of the Permissions tab (contract by the test developer). Every one is asynchronous in the view model; read the result from the state.
 * There is no method that changes anything on the broker, on purpose. [setShown] is true when the tab enters the composition (it is selected) and false when it
 * leaves (another tab is selected or the screen is left): the view model reads only while the tab is shown (D3).
 */
interface PermissionsEvents {
    fun setShown(shown: Boolean)

    fun refreshNow()

    fun retry()

    /** The key of a [dev.caerbannog.core.access.UserOption] (a hidden option is disabled and never sends this). */
    fun chooseUser(key: String)

    fun setUserFilter(text: String)

    fun setVhostFilter(text: String)
}

/** The events of a screen that has no Permissions view model (the default; a test of the Who am I tab alone). */
object NoPermissionsEvents : PermissionsEvents {
    override fun setShown(shown: Boolean) = Unit

    override fun refreshNow() = Unit

    override fun retry() = Unit

    override fun chooseUser(key: String) = Unit

    override fun setUserFilter(text: String) = Unit

    override fun setVhostFilter(text: String) = Unit
}
