package dev.caerbannog.ui.migrate3x

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingViewModel
import dev.caerbannog.core.forwarding.QueueRow

/** The events of the forwarding view, one to one onto `ForwardingViewModel`. */
interface ForwardingEvents {
    fun setQuery(query: String)
    fun pick(row: QueueRow)
    fun setAmqpHost(host: String)
    fun setAmqpPort(port: Int)
    fun setAnotherUser(on: Boolean, user: String, password: String)
    fun setKeepRunning(on: Boolean)
    fun setTyped(text: String)
    fun confirm()
    fun cancel()
    fun remove()
    fun close()
}

fun ForwardingViewModel.events(): ForwardingEvents = object : ForwardingEvents {
    override fun setQuery(query: String) = this@events.setQuery(query)
    override fun pick(row: QueueRow) = this@events.pick(row)
    override fun setAmqpHost(host: String) = this@events.setAmqpHost(host)
    override fun setAmqpPort(port: Int) = this@events.setAmqpPort(port)
    override fun setAnotherUser(on: Boolean, user: String, password: String) = this@events.setAnotherUser(on, user, password)
    override fun setKeepRunning(on: Boolean) = this@events.setKeepRunning(on)
    override fun setTyped(text: String) = this@events.setTyped(text)
    override fun confirm() = this@events.confirm()
    override fun cancel() = this@events.cancel()
    override fun remove() = this@events.remove()
    override fun close() = this@events.close()
}

/**
 * M8-09 STUB: the forwarding view (list, plan, confirmation, progress). Test tags: `forward-find`, `forward-pick-<n>`, `forward-plan`, `forward-block-<n>`,
 * `forward-warning-<n>`, `forward-amqp-host`, `forward-amqp-port`, `forward-other-user`, `forward-user`, `forward-password`, `forward-keep-running`,
 * `forward-typed-name`, `forward-confirm`, `forward-cancel`, `forward-progress`, `forward-ended`, `forward-remove`, `forward-remove-reason`. Draws nothing until built.
 */
@Composable
fun ForwardingView(state: ForwardingScreenState, events: ForwardingEvents, modifier: Modifier = Modifier) {
    // M8-09 STUB
}
