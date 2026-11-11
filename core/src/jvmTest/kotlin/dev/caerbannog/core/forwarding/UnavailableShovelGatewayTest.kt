package dev.caerbannog.core.forwarding

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-09 wiring (core part): a connection that is not a held 4.x one answers every shovel operation with the fixed text; it is the default of `StreamSource.shovels`. */
class UnavailableShovelGatewayTest {
    private val text = "No 4.x connection is held"

    @Test
    fun `every operation refuses with the fixed text and sends nothing`() = runTest {
        val g = UnavailableShovelGateway
        assertEquals(DryRunOutcome.Failed(text), g.dryRun(Fwd.request()))
        assertEquals(StartOutcome.Failed(text), g.start(Fwd.request()))
        assertEquals(StatusOutcome.Failed(text), g.status(ShovelRef("/", "x")))
        assertEquals(RemoveOutcome.Failed(text), g.remove(ShovelRef("/", "x")))
    }
}
