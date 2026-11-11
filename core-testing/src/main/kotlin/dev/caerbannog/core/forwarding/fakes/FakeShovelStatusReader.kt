package dev.caerbannog.core.forwarding.fakes

import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelStatusReader

/** M8-10: scripted [ShovelStatusReader]; [listCalls] counts the reads (there is no other operation). */
class FakeShovelStatusReader(var outcome: ShovelListOutcome = ShovelListOutcome.Listed(emptyList())) : ShovelStatusReader {
    var listCalls: Int = 0
        private set

    override suspend fun list(): ShovelListOutcome {
        listCalls++
        return outcome
    }
}
