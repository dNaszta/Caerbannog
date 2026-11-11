package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelStatusReader

/** M8-10 STUB: lists the shovels with `GET /api/shovels` over the held connection (the status mapping is shared with `ManagementShovelGateway`). Answers a failure until built. */
internal class ManagementShovelStatusReader(private val http: ManagementHttp, private val requests: ManagementRequests) : ShovelStatusReader {
    override suspend fun list(): ShovelListOutcome = ShovelListOutcome.Failed("M8-10 STUB") // M8-10 STUB
}
