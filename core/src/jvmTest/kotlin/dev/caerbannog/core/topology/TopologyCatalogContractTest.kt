package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.binding
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.exchange
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import dev.caerbannog.core.streams.CatalogResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2-08a step 2: the contract of [TopologyCatalog] as the fake must keep it (the real catalog is held to the same facts in
 * `ManagementTopologyCatalogTest` and `RealBrokerTopologyTest`). Each test pins one measured fact of the management API
 * (brief M2-08, spikes S-A to S-C; fixtures `data-rabbit/src/test/resources/fixtures/m2-08/`). The fake is shared with the Gherkin steps of 08b.
 */
class TopologyCatalogContractTest {

    private fun page(catalog: FakeTopologyCatalog, vhost: String = "/", page: Int = 1): QueuePage {
        val result = runBlocking { catalog.queues(vhost, page) }
        assertIs<CatalogResult.Success<QueuePage>>(result, "expected a page, got $result")
        return result.value
    }

    // --- queues: vhost, streams, paging ---

    @Test
    fun `the queue list answers for the requested vhost only, counts included`() {
        val catalog = FakeTopologyCatalog().addQueue(quorum("here")).addQueue(quorum("elsewhere", vhost = "other")).addStream("s-other", vhost = "other")
        val here = page(catalog)
        assertEquals(listOf("here"), here.rows.map { it.name })
        assertEquals(1, here.totalCount)
        assertEquals(listOf("elsewhere"), page(catalog, "other").rows.map { it.name })
        assertEquals(2, page(catalog, "other").totalCount)
    }

    @Test
    fun `streams are never listed as queues but fill the broker's pages and count in the total`() {
        val catalog = FakeTopologyCatalog().addQueue(quorum("a-quorum")).addStream("b-stream").addQueue(classic("c-classic")).addStream("d-stream")
        val result = page(catalog)
        assertEquals(listOf("a-quorum", "c-classic"), result.rows.map { it.name })
        assertEquals(listOf("quorum", "classic"), result.rows.map { it.type })
        assertEquals(4, result.totalCount)
    }

    @Test
    fun `queues come name ascending and the page size is the brokers maximum of 500`() {
        val catalog = FakeTopologyCatalog()
        listOf("m", "b", "z", "a").forEach { catalog.addQueue(quorum(it)) }
        assertEquals(listOf("a", "b", "m", "z"), page(catalog).rows.map { it.name })
        assertEquals(500, TopologyCatalog.QUEUE_PAGE_SIZE)
        assertEquals(500, page(catalog).pageSize)
    }

    @Test
    fun `1200 queues are three pages of 500, 500 and 200 and the numbers are the brokers`() {
        val catalog = FakeTopologyCatalog()
        repeat(1200) { catalog.addQueue(quorum("q-%04d".format(it))) }
        val sizes = (1..3).map { page(catalog, page = it) }
        assertEquals(listOf(500, 500, 200), sizes.map { it.rows.size })
        assertEquals(listOf(3, 3, 3), sizes.map { it.pageCount })
        assertEquals(listOf(1200, 1200, 1200), sizes.map { it.totalCount })
        assertEquals("q-0000", sizes[0].rows.first().name)
        assertEquals("q-1199", sizes[2].rows.last().name)
    }

    @Test
    fun `a page of streams only holds no row but is a page`() {
        val catalog = FakeTopologyCatalog()
        repeat(600) { catalog.addStream("a-%03d".format(it)) }
        catalog.addQueue(quorum("z-last"))
        assertEquals(emptyList(), page(catalog, page = 1).rows)
        assertEquals(listOf("z-last"), page(catalog, page = 2).rows.map { it.name })
        assertEquals(601, page(catalog, page = 2).totalCount)
    }

    @Test
    fun `a vhost without queues is page 1 of 0, and page 2 is BadPaging`() {
        val catalog = FakeTopologyCatalog().declareVhost("empty")
        val result = page(catalog, "empty")
        assertEquals(emptyList(), result.rows)
        assertEquals(0, result.pageCount)
        assertEquals(0, result.totalCount)
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues("empty", 2) })
    }

    @Test
    fun `page 0, a negative page and a page past the last are BadPaging`() {
        val catalog = FakeTopologyCatalog().addQueue(quorum("only"))
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues("/", 0) })
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues("/", -1) })
        assertEquals(CatalogResult.BadPaging, runBlocking { catalog.queues("/", 2) })
    }

    @Test
    fun `a vhost nobody declared is NotFound, the slash vhost exists from the start`() {
        val catalog = FakeTopologyCatalog()
        assertEquals(0, page(catalog).totalCount)
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.queues("nope", 1) })
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.exchanges("nope") })
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.bindings("nope") })
        assertEquals(CatalogResult.NotFound, runBlocking { catalog.queueDetail("nope", "q") })
    }

    @Test
    fun `adding a queue of the same name again replaces it, so a test can update statistics`() {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q", ready = 1)).addQueue(quorum("q", ready = 7))
        assertEquals(listOf(7L), page(catalog).rows.map { it.readyMessages })
        assertEquals(1, page(catalog).totalCount)
    }

    // --- queue detail ---

    @Test
    fun `queueDetail returns the row, NotFound for a missing queue, after delete, and for a stream`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q", policy = "ha")).addStream("s")
        assertEquals(CatalogResult.Success(quorum("q", policy = "ha")), catalog.queueDetail("/", "q"))
        assertEquals(CatalogResult.NotFound, catalog.queueDetail("/", "missing"))
        assertEquals(CatalogResult.NotFound, catalog.queueDetail("/", "s"))
        catalog.deleteQueue("q")
        assertEquals(CatalogResult.NotFound, catalog.queueDetail("/", "q"))
    }

    // --- exchanges and bindings ---

    @Test
    fun `exchanges always start with the default exchange, direct and durable, and are per vhost`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addExchange(exchange("orders", "topic")).addExchange(exchange("elsewhere", vhost = "other"))
        val rows = (catalog.exchanges("/") as CatalogResult.Success).value
        assertEquals(listOf("", "orders"), rows.map { it.name })
        assertTrue(rows.first().isDefault)
        assertEquals("direct", rows.first().type)
        assertTrue(rows.first().durable)
        assertFalse(rows[1].isDefault)
        assertEquals(listOf("", "elsewhere"), (catalog.exchanges("other") as CatalogResult.Success).value.map { it.name })
    }

    @Test
    fun `an exchange added again replaces the old one and the default exchange cannot be replaced`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addExchange(exchange("x", "direct")).addExchange(exchange("x", "fanout")).addExchange(exchange("", "topic"))
        val rows = (catalog.exchanges("/") as CatalogResult.Success).value
        assertEquals(listOf("" to "direct", "x" to "fanout"), rows.map { it.name to it.type })
    }

    @Test
    fun `bindings carry the implicit default exchange binding of EVERY queue, streams included, before the declared ones`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("b-queue")).addStream("a-stream")
            .addBinding(binding("orders", "b-queue", routingKey = "eu")).addBinding(binding("orders", "dest-ex", destinationType = BindingDestinationType.EXCHANGE))
        val rows = (catalog.bindings("/") as CatalogResult.Success).value
        assertEquals(
            listOf(
                BindingRow("", "/", "a-stream", BindingDestinationType.QUEUE, "a-stream"),
                BindingRow("", "/", "b-queue", BindingDestinationType.QUEUE, "b-queue"),
                BindingRow("orders", "/", "b-queue", BindingDestinationType.QUEUE, "eu"),
                BindingRow("orders", "/", "dest-ex", BindingDestinationType.EXCHANGE, ""),
            ),
            rows,
        )
    }

    @Test
    fun `bindings are per vhost`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q")).addQueue(quorum("o", vhost = "other")).addBinding(binding("x", "o", vhost = "other"))
        assertEquals(listOf("q"), (catalog.bindings("/") as CatalogResult.Success).value.map { it.destination })
        assertEquals(listOf("o", "o"), (catalog.bindings("other") as CatalogResult.Success).value.map { it.destination })
    }

    // --- failures ---

    @Test
    fun `without the management tag every call is NotAuthorised, also for a vhost that does not exist`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q"))
        catalog.hasManagementTag = false
        assertEquals(CatalogResult.NotAuthorised, catalog.queues("/", 1))
        assertEquals(CatalogResult.NotAuthorised, catalog.exchanges("/"))
        assertEquals(CatalogResult.NotAuthorised, catalog.bindings("/"))
        assertEquals(CatalogResult.NotAuthorised, catalog.queueDetail("/", "q"))
        assertEquals(CatalogResult.NotAuthorised, catalog.queues("nope", 1))
    }

    @Test
    fun `an unreachable broker answers Unreachable on every call`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q"))
        catalog.unreachable = true
        assertEquals(CatalogResult.Unreachable, catalog.queues("/", 1))
        assertEquals(CatalogResult.Unreachable, catalog.exchanges("/"))
        assertEquals(CatalogResult.Unreachable, catalog.bindings("/"))
        assertEquals(CatalogResult.Unreachable, catalog.queueDetail("/", "q"))
    }

    @Test
    fun `one page can fail while the others work, and clearFailures heals it`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog()
        repeat(600) { catalog.addQueue(quorum("q-%03d".format(it))) }
        catalog.failQueuePage(2, CatalogResult.Unreachable)
        assertIs<CatalogResult.Success<*>>(catalog.queues("/", 1))
        assertEquals(CatalogResult.Unreachable, catalog.queues("/", 2))
        catalog.clearFailures()
        assertIs<CatalogResult.Success<*>>(catalog.queues("/", 2))
    }

    @Test
    fun `a call can be made to fail by name, the others keep working`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q"))
        catalog.failCall("bindings", CatalogResult.NotAuthorised)
        assertEquals(CatalogResult.NotAuthorised, catalog.bindings("/"))
        assertIs<CatalogResult.Success<*>>(catalog.queues("/", 1))
        catalog.failCall("exchanges")
        assertEquals(CatalogResult.Unexpected, catalog.exchanges("/"))
    }

    // --- bookkeeping a test relies on ---

    @Test
    fun `every call is recorded by name, refused ones too`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q"))
        catalog.queues("/", 1)
        catalog.exchanges("/")
        catalog.bindings("/")
        catalog.queueDetail("/", "q")
        catalog.hasManagementTag = false
        catalog.queues("/", 1)
        assertEquals(listOf("queues", "exchanges", "bindings", "queueDetail", "queues"), catalog.calls.toList())
    }

    @Test
    fun `a held call completes late, with the answer it computed when it was made`() = runBlocking<Unit> {
        val catalog = FakeTopologyCatalog().addQueue(quorum("q", ready = 1))
        val gate = catalog.holdNext("queues")
        val late = async { catalog.queues("/", 1) }
        yield()
        catalog.addQueue(quorum("q", ready = 99))
        gate.release()
        val rows = (late.await() as CatalogResult.Success).value.rows
        assertEquals(listOf(1L), rows.map { it.readyMessages })
        assertEquals(listOf(99L), page(catalog).rows.map { it.readyMessages })
    }
}
