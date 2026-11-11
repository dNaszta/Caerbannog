package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.migrate3x.AnalysisHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val FORWARDING_POLL_MILLIS: Long = 2_000

/**
 * M8-09 STUB: state in, events out for the forwarding view. [context] is the effective level and the configure permission of the held 4.x connection,
 * read at every call. Every event does nothing until built.
 */
class ForwardingViewModel(
    private val service: ForwardingService,
    private val held: AnalysisHolder,
    private val destination: () -> DestinationConnection?,
    private val profiles: (String) -> ConnectionProfile?,
    private val context: () -> ForwardingContext,
    private val scope: CoroutineScope,
    private val pollMillis: Long = FORWARDING_POLL_MILLIS,
) {
    private val mutable = MutableStateFlow<ForwardingScreenState>(ForwardingScreenState.Closed)
    val state: StateFlow<ForwardingScreenState> = mutable

    fun open() = Unit // M8-09 STUB
    fun close() = Unit // M8-09 STUB
    fun setQuery(query: String) = Unit // M8-09 STUB
    fun pick(row: QueueRow) = Unit // M8-09 STUB
    fun setAmqpHost(host: String) = Unit // M8-09 STUB
    fun setAmqpPort(port: Int) = Unit // M8-09 STUB
    fun setAnotherUser(on: Boolean, user: String, password: String) = Unit // M8-09 STUB
    fun setKeepRunning(on: Boolean) = Unit // M8-09 STUB
    fun setTyped(text: String) = Unit // M8-09 STUB
    fun confirm() = Unit // M8-09 STUB
    fun cancel() = Unit // M8-09 STUB
    fun remove() = Unit // M8-09 STUB
}
