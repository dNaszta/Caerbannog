package dev.caerbannog.core.forwarding

/** The result of listing the shovels of a 4.x connection. No field holds an address, a URI or a credential. */
sealed interface ShovelListOutcome {
    data class Listed(val shovels: List<ShovelStatus>) : ShovelListOutcome
    data object NotPermitted : ShovelListOutcome
    data object PluginsOff : ShovelListOutcome
    data object Unreachable : ShovelListOutcome
    data class Failed(val reason: String) : ShovelListOutcome
}

/**
 * M8-10: read-only status of the shovels, `GET /api/shovels` only. A SEPARATE interface from [ShovelGateway] (create, remove): the MCP server gets this one and never the gateway.
 */
interface ShovelStatusReader {
    suspend fun list(): ShovelListOutcome
}
