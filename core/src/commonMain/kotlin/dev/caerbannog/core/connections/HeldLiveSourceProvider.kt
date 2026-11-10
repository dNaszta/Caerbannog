package dev.caerbannog.core.connections

import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.LiveSourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// M8-06c STUB: always "no readable 3.x connection".

/** Connected link + held StreamSource + session status Done(MigrationSource) -> that source's definitionsSource, else null. */
class HeldLiveSourceProvider(active: ActiveConnection, session: ConnectionSession, scope: CoroutineScope) : LiveSourceProvider {
    override val current: StateFlow<DefinitionsSource?> = MutableStateFlow(null)
}

/** The pure rule of [HeldLiveSourceProvider]. */
internal fun liveSourceOf(link: LinkState, source: StreamSource?, sessionState: SessionState): DefinitionsSource? = null
