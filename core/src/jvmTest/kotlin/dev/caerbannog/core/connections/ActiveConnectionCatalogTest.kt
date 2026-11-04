package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PluginStatus
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.Visibility
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * M2-03 part 2: what the catalog and the offsets of a held connection do NOT do to Connect (spec "Connection
 * lifecycle": a management 401 "Not management user", a missing stream plugin and a silent stream port are not
 * failures). `ActiveConnection` stays ignorant of them; the real answers come from the gateway, so these pin
 * that nothing in `ActiveConnection` starts treating a catalog or offsets problem as a failed Connect.
 * Contract: `HeldConnection` gets `val catalog: StreamCatalog` and `val offsets: StreamOffsets`.
 */
class ActiveConnectionCatalogTest {

    @Test
    fun `a user without the management tag is Connected with visibility none`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.noManagementTag("uat-eu")

        h.active.connect(h.uat)

        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        val held = h.gateway.heldNow.single()
        assertEquals(
            CatalogResult.Success(Capabilities(PluginStatus.UNKNOWN, Visibility.NONE)), held.catalog.capabilities(),
        )
        assertEquals(CatalogResult.NotAuthorised, held.catalog.listStreams(StreamListRequest()))
    }

    @Test
    fun `a missing stream plugin does not fail Connect`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.catalogOf("uat-eu").pluginPresent = false

        h.active.connect(h.uat)

        assertIs<LinkState.Connected>(h.active.state.value)
        assertEquals(CatalogResult.PluginMissing, h.gateway.heldNow.single().catalog.publishers("/", "s"))
    }

    @Test
    fun `a silent stream port does not fail Connect and offsets read Unavailable`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.streamPortSilent("uat-eu")

        h.active.connect(h.uat)

        assertIs<LinkState.Connected>(h.active.state.value)
        assertEquals(FirstOffset.Unavailable, h.gateway.heldNow.single().offsets.firstOffset("/", "orders-events"))
    }

    @Test
    fun `Connect and Disconnect never call the catalog or the offsets`() = runBlocking<Unit> {
        val h = Harness()

        h.active.connect(h.uat)
        h.active.disconnect()

        assertEquals(emptyList(), h.gateway.catalogOf("uat-eu").calls, "listing is the view model's job, not Connect's")
        assertEquals(emptyList(), h.gateway.offsetsOf("uat-eu").calls, "first offsets are lazy: nothing on Connect")
    }
}
