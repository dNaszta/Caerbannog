package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.binding
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.exchange
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-08b step 2: the Exchanges and Bindings tabs, the queue detail, and WHAT each tab loads (brief Design notes "What loads when": the queue
 * list and the vhost list every tick; exchanges only while the Exchanges tab is shown; bindings while the Bindings tab is shown or a queue
 * is selected; no per-queue call). Scenarios: "Queue details", "See exchanges and bindings", "The default exchange and its bindings are hidden
 * until asked for", "A binding to an exchange is shown as such", "A super stream exchange is marked". Contract: [TopologyHarness].
 */
class TopologyViewModelTabsTest {

    private fun TopologyHarness.wired() = apply {
        topology.addQueue(quorum("orders", ready = 1240, unacked = 12, consumers = 3, policy = "quorum-default", members = listOf("rabbit@node-1", "rabbit@node-2", "rabbit@node-3")))
        topology.addExchange(exchange("events", "topic"))
        topology.addBinding(binding("events", "orders", routingKey = "order.created"))
    }

    // --- what loads when ---

    @Test
    fun `the Queues tab asks for the vhost list and the queues only`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        assertEquals(listOf("vhosts", "queues /|1"), s.requests())
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1"), s.requests())
    }

    @Test
    fun `the Exchanges tab adds the exchanges and never the bindings`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.forgetRequests()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        assertTrue("exchanges /" in s.requests(), "a tab switch loads what the tab needs at once: ${s.requests()}")
        assertFalse(s.requests().any { it.startsWith("bindings") })
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1", "exchanges /"), s.requests())
    }

    @Test
    fun `the Bindings tab adds the bindings and never the exchanges`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.forgetRequests()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertTrue("bindings /" in s.requests(), s.requests().toString())
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1", "bindings /"), s.requests())
    }

    @Test
    fun `going back to the Queues tab stops asking for exchanges and bindings`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        s.vm.setTab(TopologyTab.Queues)
        s.settle()
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1"), s.requests())
    }

    @Test
    fun `selecting a queue loads the bindings, closing it stops`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.forgetRequests()
        s.vm.selectQueue("orders")
        s.settle()
        assertTrue("bindings /" in s.requests())
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1", "bindings /"), s.requests())

        s.vm.selectQueue(null)
        s.settle()
        s.forgetRequests()
        s.pass(5)
        assertEquals(listOf("vhosts", "queues /|1"), s.requests())
    }

    @Test
    fun `no tab or selection ever asks for a single queue, the list row has everything`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        for (tab in TopologyTab.entries) {
            s.vm.setTab(tab)
            s.vm.selectQueue("orders")
            s.settle()
        }
        s.pass(30)
        assertEquals(0, s.count("queueDetail"))
    }

    // --- queue detail ---

    @Test
    fun `selecting a queue shows its leader, members, counts, consumers, policy and bindings`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.vm.selectQueue("orders")
        s.settle()
        assertEquals(
            QueueDetailView(
                name = "orders",
                typeText = "Quorum queue",
                leader = "rabbit@node-1",
                members = "node-1, node-2, node-3",
                membersOnline = "3 of 3",
                readyUnacked = "1,240 / 12",
                consumers = "3",
                policy = "quorum-default",
                bindings = listOf(BindingRowView("events", "orders", "queue", "order.created", "{}")),
                memberOffline = false,
            ),
            s.state.selectedQueue,
        )
    }

    @Test
    fun `the detail's bindings are the explicit ones, never the implicit default exchange binding`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.vm.selectQueue("orders")
        s.settle()
        assertEquals(listOf("events"), s.state.selectedQueue!!.bindings.map { it.source })
    }

    @Test
    fun `a queue with no policy reads a dash, a classic queue is typed Classic queue`() = runTest {
        val s = TopologyHarness(this)
        s.topology.addQueue(quorum("plain"))
        s.topology.addQueue(classic("legacy"))
        s.open()
        s.vm.selectQueue("plain")
        s.settle()
        assertEquals("–", s.state.selectedQueue!!.policy)
        s.vm.setQueueFilter(QueueTypeFilter.OtherTypes)
        s.vm.selectQueue("legacy")
        s.settle()
        assertEquals("Classic queue", s.state.selectedQueue!!.typeText)
    }

    @Test
    fun `a selected queue with a member offline says so in the detail`() = runTest {
        val s = TopologyHarness(this)
        val three = listOf("rabbit@a", "rabbit@b", "rabbit@c")
        s.topology.addQueue(quorum("q", members = three, online = three.take(2)))
        s.open()
        s.vm.selectQueue("q")
        s.settle()
        assertTrue(s.state.selectedQueue!!.memberOffline)
        assertEquals("2 of 3", s.state.selectedQueue!!.membersOnline)
    }

    @Test
    fun `the detail follows a refresh, a queue that disappears closes it, an unknown name is ignored`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.vm.selectQueue("orders")
        s.settle()
        s.topology.addQueue(quorum("orders", ready = 5, unacked = 0, consumers = 0))
        s.pass(5)
        assertEquals("5 / 0", s.state.selectedQueue!!.readyUnacked)

        s.topology.deleteQueue("orders")
        s.pass(5)
        assertNull(s.state.selectedQueue)

        s.vm.selectQueue("never-loaded")
        s.settle()
        assertNull(s.state.selectedQueue)
    }

    @Test
    fun `a queue can be selected from any page and any tab`() = runTest {
        val s = TopologyHarness(this, pageSize = 2).wired()
        s.quorumQueues(5, prefix = "x-")
        s.open()
        s.vm.setTab(TopologyTab.Exchanges)
        s.vm.selectQueue("x-04")
        s.settle()
        assertEquals("x-04", s.state.selectedQueue?.name)
    }

    // --- exchanges ---

    @Test
    fun `the Exchanges tab lists the default exchange and the declared ones with their flags`() = runTest {
        val s = TopologyHarness(this, pageSize = 50).wired()
        s.open()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        assertEquals(
            listOf(
                ExchangeRowView("(default exchange)", "direct", "yes", "no", "no", superStream = false, isDefault = true),
                ExchangeRowView("events", "topic", "yes", "no", "no", superStream = false, isDefault = false),
            ),
            s.state.exchanges,
        )
        assertEquals(emptyList(), s.state.queues, "only the current tab's list is filled")
        assertEquals("1 to 2 of 2", s.state.rangeText)
    }

    @Test
    fun `an exchange can be internal, auto-delete or not durable and says so`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        s.topology.addExchange(dev.caerbannog.core.topology.ExchangeRow("amq.trace", "/", "topic", durable = false, autoDelete = true, internal = true))
        s.open()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        assertEquals(ExchangeRowView("amq.trace", "topic", "no", "yes", "yes", superStream = false, isDefault = false), s.state.exchanges.last())
    }

    @Test
    fun `a direct exchange with the super stream marker is marked, a fanout with the marker is not`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        s.topology.addExchange(exchange("orders", "direct", arguments = mapOf("x-super-stream" to "true")))
        s.topology.addExchange(exchange("fan", "fanout", arguments = mapOf("x-super-stream" to "true")))
        s.topology.addExchange(exchange("plain", "direct"))
        s.topology.addExchange(exchange("off", "direct", arguments = mapOf("x-super-stream" to "false")))
        s.open()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        assertEquals(setOf("orders"), s.state.exchanges.filter { it.superStream }.map { it.name }.toSet())
    }

    @Test
    fun `search on the Exchanges tab filters exchanges and each tab keeps its own search text`() = runTest {
        val s = TopologyHarness(this, pageSize = 50).wired()
        s.topology.addExchange(exchange("audit", "fanout"))
        s.open()
        s.vm.setSearch("ord")
        s.vm.setTab(TopologyTab.Exchanges)
        s.vm.setSearch("aud")
        s.settle()
        assertEquals(listOf("audit"), s.state.exchanges.map { it.name })
        assertEquals("aud", s.state.search)

        s.vm.setTab(TopologyTab.Queues)
        s.settle()
        assertEquals("ord", s.state.search, "the Queues tab kept its own text")
        assertEquals(listOf("orders"), s.names())
    }

    // --- bindings ---

    @Test
    fun `the default exchange and its implicit bindings are hidden until asked for`() = runTest {
        val s = TopologyHarness(this, pageSize = 50).wired()
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertEquals(listOf(BindingRowView("events", "orders", "queue", "order.created", "{}")), s.state.bindings)
        assertFalse(s.state.showDefaultExchange)

        s.vm.setShowDefaultExchange(true)
        s.settle()
        assertTrue(s.state.showDefaultExchange)
        assertEquals(
            listOf(
                BindingRowView("(default exchange)", "orders", "queue", "orders", "{}"),
                BindingRowView("events", "orders", "queue", "order.created", "{}"),
            ),
            s.state.bindings,
        )
    }

    @Test
    fun `asking for the default exchange does not call the broker again, the bindings are already loaded`() = runTest {
        val s = TopologyHarness(this).wired()
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        val before = s.count("bindings")
        s.vm.setShowDefaultExchange(true)
        s.settle()
        assertEquals(before, s.count("bindings"))
    }

    @Test
    fun `a binding to an exchange says its destination is an exchange`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        s.topology.addExchange(exchange("events", "topic"))
        s.topology.addExchange(exchange("audit", "fanout"))
        s.topology.addBinding(binding("events", "audit", routingKey = "#", destinationType = BindingDestinationType.EXCHANGE))
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertEquals(listOf(BindingRowView("events", "audit", "exchange", "#", "{}")), s.state.bindings)
    }

    @Test
    fun `binding arguments read as a braced list, keys quoted, values as given`() = runTest {
        val s = TopologyHarness(this, pageSize = 50)
        s.topology.addExchange(exchange("orders", "direct", arguments = mapOf("x-super-stream" to "true")))
        s.topology.addQueue(quorum("orders-0"))
        s.topology.addBinding(binding("orders", "orders-0", routingKey = "0", arguments = mapOf("x-stream-partition-order" to "0")))
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertEquals("""{"x-stream-partition-order": 0}""", s.state.bindings.single().arguments)
    }

    @Test
    fun `search on the Bindings tab matches source, destination and routing key`() = runTest {
        val s = TopologyHarness(this, pageSize = 50).wired()
        s.topology.addExchange(exchange("audit", "fanout"))
        s.topology.addBinding(binding("audit", "orders", routingKey = "#"))
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.vm.setSearch("created")
        s.settle()
        assertEquals(listOf("events"), s.state.bindings.map { it.source })
        s.vm.setSearch("audit")
        s.settle()
        assertEquals(listOf("audit"), s.state.bindings.map { it.source })
    }

    @Test
    fun `the bindings are paged in memory like the other lists`() = runTest {
        val s = TopologyHarness(this, pageSize = 2)
        s.topology.addExchange(exchange("events", "topic"))
        repeat(5) {
            s.topology.addQueue(quorum("q-$it"))
            s.topology.addBinding(binding("events", "q-$it", routingKey = "k$it"))
        }
        s.open()
        s.vm.setTab(TopologyTab.Bindings)
        s.settle()
        assertEquals("1 to 2 of 5", s.state.rangeText)
        s.vm.nextPage()
        s.settle()
        assertEquals(listOf("q-2", "q-3"), s.state.bindings.map { it.destination })
        assertEquals(1, s.count("bindings"), "paging asked nothing")
    }

    @Test
    fun `a tab switch goes back to page 1 and keeps the vhost`() = runTest {
        val s = TopologyHarness(this, pageSize = 2).wired()
        s.quorumQueues(6, prefix = "x-")
        s.open()
        s.vm.nextPage()
        s.settle()
        s.vm.setTab(TopologyTab.Exchanges)
        s.settle()
        assertEquals(1, s.state.page)
        assertEquals("/", s.state.vhost)
    }
}
