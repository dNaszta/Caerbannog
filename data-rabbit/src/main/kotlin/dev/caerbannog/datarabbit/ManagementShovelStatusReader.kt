package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelStatusReader

/**
 * Lists the shovels of the held 4.x connection with ONE `GET /api/shovels` (M8-10): the same mapping as `ManagementShovelGateway`, never an address,
 * the reason scrubbed. Read only: it has no create or remove and does not know the shovel gateway. Every failure is a fixed outcome.
 */
internal class ManagementShovelStatusReader(private val http: ManagementHttp, private val requests: ManagementRequests) : ShovelStatusReader {
    override suspend fun list(): ShovelListOutcome {
        val reply = http.get(requests.absolute(ShovelPathAllowList.checked("GET", "/api/shovels"))) as? ManagementReply.Response
            ?: return ShovelListOutcome.Unreachable
        return when (reply.status) {
            200 -> ShovelStatusMapping.parseList(reply.body)?.let { entries -> ShovelListOutcome.Listed(entries.map(ShovelStatusMapping::statusOf)) }
                ?: ShovelListOutcome.Failed(FAILED)
            401, 403 -> ShovelListOutcome.NotPermitted
            404 -> ShovelListOutcome.PluginsOff
            else -> ShovelListOutcome.Failed(FAILED)
        }
    }

    private companion object {
        const val FAILED = "The broker did not list the shovels"
    }
}
