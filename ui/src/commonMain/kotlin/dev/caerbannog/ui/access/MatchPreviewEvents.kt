package dev.caerbannog.ui.access

import dev.caerbannog.core.access.MatchAction

/**
 * M10-04 part B: events out of the match preview form on the Permissions tab (contract by the test developer). Every one is asynchronous in the view model;
 * read the result from `MatchPreviewState`. [check] asks the pure matcher only: there is no method that sends anything to the broker, on purpose.
 */
interface MatchPreviewEvents {
    fun chooseAction(action: MatchAction)

    fun chooseVhost(vhost: String)

    fun setName(text: String)

    fun setRoutingKey(text: String)

    fun check()
}

/** The events of a screen that has no match preview (the default). */
object NoMatchPreviewEvents : MatchPreviewEvents {
    override fun chooseAction(action: MatchAction) = Unit

    override fun chooseVhost(vhost: String) = Unit

    override fun setName(text: String) = Unit

    override fun setRoutingKey(text: String) = Unit

    override fun check() = Unit
}
