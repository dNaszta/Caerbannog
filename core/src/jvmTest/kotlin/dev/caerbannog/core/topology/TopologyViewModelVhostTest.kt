package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.CatalogResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-08b step 2: the virtual host choice (decision T1, rulings R2, R4). Scenarios: "The topology screen starts on the connection's virtual
 * host", "Another virtual host can be chosen", "Only the virtual hosts the user may see are offered", "A virtual host the user cannot see says so".
 * One selected vhost; only that vhost's data loads each tick, next to the vhost list; switching drops the old rows, cancels the running
 * load and loads now; the choice is per connection and not saved. Contract: [TopologyHarness].
 */
class TopologyViewModelVhostTest {

    private fun TopologyHarness.billing(): TopologyHarness = apply {
        topology.addQueue(quorum("invoices", vhost = "billing"))
        quorumQueues(2)
    }

    @Test
    fun `the screen starts on the connection's virtual host`() = runTest {
        val s = TopologyHarness(this, vhost = "/").billing()
        s.open()
        assertEquals("/", s.state.vhost)
        assertEquals(listOf("q-00", "q-01"), s.names())
    }

    @Test
    fun `a profile on another vhost starts there`() = runTest {
        val s = TopologyHarness(this, vhost = "billing")
        s.topology.addQueue(quorum("invoices", vhost = "billing"))
        s.topology.addQueue(quorum("root-q", vhost = "/"))
        s.open()
        assertEquals("billing", s.state.vhost)
        assertEquals(listOf("invoices"), s.names())
    }

    @Test
    fun `the offered vhosts are the brokers list, the connection's first, then alphabetical`() = runTest {
        val s = TopologyHarness(this, vhost = "/")
        listOf("many", "billing", "a-team").forEach { s.topology.declareVhost(it) }
        s.open()
        assertEquals(listOf("/", "a-team", "billing", "many"), s.state.vhosts)
        assertTrue(s.state.showVhostSwitch)
    }

    @Test
    fun `a connection on billing lists billing first`() = runTest {
        val s = TopologyHarness(this, vhost = "billing")
        s.topology.declareVhost("many")
        s.open()
        assertEquals(listOf("billing", "/", "many"), s.state.vhosts)
    }

    @Test
    fun `only the vhosts the user may see are offered, a vhost it has no permission on is not`() = runTest {
        val s = TopologyHarness(this)
        s.topology.declareVhost("billing")
        s.topology.declareVhost("secret")
        s.topology.permittedVhosts = setOf("/", "billing")
        s.open()
        assertEquals(listOf("/", "billing"), s.state.vhosts)
    }

    @Test
    fun `choosing another vhost shows only its queues and none of the first`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.vm.selectVhost("billing")
        s.settle()
        assertEquals("billing", s.state.vhost)
        assertEquals(listOf("invoices"), s.names())
        assertEquals("1 to 1 of 1", s.state.rangeText)
    }

    @Test
    fun `after a switch only the selected vhost is asked, every tick, next to the vhost list`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.vm.selectVhost("billing")
        s.settle()
        s.forgetRequests()

        s.pass(5)

        assertEquals(listOf("vhosts", "queues billing|1"), s.requests())
    }

    @Test
    fun `the vhost list is read on open and on every tick`() = runTest {
        val s = TopologyHarness(this)
        s.open()
        assertEquals(1, s.count("vhosts"))
        s.pass(5)
        s.pass(5)
        assertEquals(3, s.count("vhosts"))
    }

    @Test
    fun `a vhost created while the screen is shown is offered at the next tick`() = runTest {
        val s = TopologyHarness(this)
        s.open()
        s.topology.declareVhost("newcomer")
        s.pass(5)
        assertTrue("newcomer" in s.state.vhosts)
    }

    @Test
    fun `switching drops the rows at once, the old vhost's rows never show under the new one`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        val gate = s.topology.holdNext("queues")

        s.vm.selectVhost("billing")
        s.settle()

        assertEquals("billing", s.state.vhost)
        assertEquals(emptyList(), s.state.queues, "rows of / must not show under billing")
        assertIs<TopologyScreen.Loading>(s.state.screen)
        gate.release()
        s.settle()
        assertEquals(listOf("invoices"), s.names())
    }

    @Test
    fun `switching cancels the running load, a late answer of the old vhost is dropped`() = runTest {
        val s = TopologyHarness(this).billing()
        val late = s.topology.holdNext("queues", ignoreCancellation = true)
        s.open()
        assertIs<TopologyScreen.Loading>(s.state.screen)

        s.vm.selectVhost("billing")
        s.settle()
        assertEquals(listOf("invoices"), s.names(), "the new vhost loaded now, without waiting for the old load")
        late.release()
        s.settle()

        assertEquals("billing", s.state.vhost)
        assertEquals(listOf("invoices"), s.names(), "the late rows of / are dropped")
    }

    @Test
    fun `switching loads at once and restarts the timer`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.pass(3)
        s.forgetRequests()

        s.vm.selectVhost("billing")
        s.settle()
        assertEquals(1, s.count("queues"))

        s.pass(4)
        assertEquals(1, s.count("queues"), "4 s after the switch load: no tick yet")
        s.pass(1)
        assertEquals(2, s.count("queues"))
    }

    @Test
    fun `choosing the vhost already chosen changes nothing and asks nothing`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.forgetRequests()
        s.vm.selectVhost("/")
        s.settle()
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `a switch closes the queue detail and starts the new vhost on page 1 with a clean search`() = runTest {
        val s = TopologyHarness(this, pageSize = 1).billing()
        s.topology.addQueue(quorum("inv-2", vhost = "billing"))
        s.open()
        s.vm.selectQueue("q-00")
        s.vm.nextPage()
        s.settle()

        s.vm.selectVhost("billing")
        s.settle()

        assertEquals(null, s.state.selectedQueue)
        assertEquals(1, s.state.page)
    }

    @Test
    fun `the choice resets to the connection's vhost when the connection changes`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.vm.selectVhost("billing")
        s.settle()
        assertEquals("billing", s.state.vhost)

        s.h.active.disconnect()
        s.settle()
        s.connect()

        assertEquals("/", s.state.vhost)
        assertEquals(listOf("q-00", "q-01"), s.names())
    }

    @Test
    fun `a Retry after a lost connection is a new connection and starts on the connection's vhost again`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.vm.selectVhost("billing")
        s.settle()
        s.topology.unreachable = true
        s.pass(5)
        s.topology.unreachable = false
        s.vm.retry()
        s.settle()
        assertEquals("/", s.state.vhost)
    }

    // --- R2 ---

    @Test
    fun `a listed vhost the broker answers 404 for says so and keeps the switch usable`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.topology.declareVhost("secret")
        s.topology.permittedVhosts = setOf("/")
        s.topology.listsEveryVhost = true // a monitoring user is listed every vhost (spike S-E)
        s.open()
        assertEquals(listOf("/", "secret"), s.state.vhosts)

        s.vm.selectVhost("secret")
        s.settle()

        assertEquals(TopologyScreen.VhostUnavailable("secret"), s.state.screen)
        assertEquals(TopologyTexts.vhostUnavailable("secret"), s.state.message)
        assertEquals(emptyList(), s.state.queues)
        assertTrue(s.state.showVhostSwitch)
        assertEquals(listOf("/", "secret"), s.state.vhosts)

        s.vm.selectVhost("/")
        s.settle()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(listOf("q-00", "q-01"), s.names())
    }

    @Test
    fun `the unavailable vhost is asked again at every tick and recovers when the permission comes back`() = runTest {
        val s = TopologyHarness(this)
        s.topology.declareVhost("secret")
        s.topology.addQueue(quorum("hidden", vhost = "secret"))
        s.topology.permittedVhosts = setOf("/")
        s.topology.listsEveryVhost = true
        s.open()
        s.vm.selectVhost("secret")
        s.settle()
        assertIs<TopologyScreen.VhostUnavailable>(s.state.screen)

        s.topology.permittedVhosts = null
        s.pass(5)

        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(listOf("hidden"), s.names())
    }

    // --- failures of the vhost list ---

    @Test
    fun `a vhost list the broker answers badly does not fail the queues`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.topology.failCall("vhosts", CatalogResult.Unexpected)
        s.open()
        assertIs<TopologyScreen.Ready>(s.state.screen)
        assertEquals(2, s.state.queues.size)
    }

    @Test
    fun `an unreachable broker on the vhost list is a lost connection like any other call`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        s.topology.failCall("vhosts", CatalogResult.Unreachable)
        s.pass(5)
        assertEquals(dev.caerbannog.core.connections.LinkState.Failed("uat-eu", "Connection lost"), s.h.active.state.value)
        assertTrue(s.state.stale)
        assertEquals(2, s.state.queues.size)
    }

    @Test
    fun `an unknown vhost name is ignored, the app cannot offer it`() = runTest {
        val s = TopologyHarness(this)
        s.quorumQueues(2)
        s.open()
        s.forgetRequests()
        s.vm.selectVhost("never-offered")
        s.settle()
        assertEquals("/", s.state.vhost)
        assertEquals(emptyList(), s.requests())
        assertFalse(s.state.screen is TopologyScreen.VhostUnavailable)
    }

    // Architect follow-up of 08b step 5: the search texts belong to the vhost they were typed on.
    @Test
    fun `a switch clears the search text of every tab`() = runTest {
        val s = TopologyHarness(this).billing()
        s.open()
        s.vm.setSearch("q-0")
        s.vm.setTab(TopologyTab.Exchanges)
        s.vm.setSearch("ev")
        s.settle()
        s.vm.selectVhost("billing")
        s.settle()
        assertEquals("", s.state.search, "the Exchanges tab text is gone")
        s.vm.setTab(TopologyTab.Queues)
        s.settle()
        assertEquals("", s.state.search, "and so is the Queues tab text")
    }
}
