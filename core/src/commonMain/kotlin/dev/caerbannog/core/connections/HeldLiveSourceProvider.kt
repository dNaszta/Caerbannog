package dev.caerbannog.core.connections

import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.LiveSourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Connected link + held StreamSource + session status Done(MigrationSource) -> that source's definitionsSource, else null.
 * Problem solved: the Migrate from 3.x screen must follow the connection while it is open without knowing the link, the session or
 * the held source; it sees one read-only [DefinitionsSource] or nothing.
 */
class HeldLiveSourceProvider(active: ActiveConnection, session: ConnectionSession, scope: CoroutineScope) : LiveSourceProvider {
    override val current: StateFlow<DefinitionsSource?> =
        combine(active.state, active.sources, session.state) { link, source, sessionState -> liveSourceOf(link, source, sessionState) }
            .stateIn(scope, SharingStarted.Eagerly, liveSourceOf(active.state.value, active.source, session.state.value))
}

/** The pure rule of [HeldLiveSourceProvider]: the link and the session must name the same connection, and the session must have found a 3.x broker. */
internal fun liveSourceOf(link: LinkState, source: StreamSource?, sessionState: SessionState): DefinitionsSource? {
    if (link !is LinkState.Connected || source == null) return null
    val currentConnection = sessionState.current ?: return null
    if (currentConnection.name != link.name) return null
    return if (currentConnection.status.isMigrationSource()) source.definitionsSource else null
}
