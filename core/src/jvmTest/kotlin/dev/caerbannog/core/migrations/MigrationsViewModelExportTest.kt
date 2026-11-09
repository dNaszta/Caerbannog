package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.PagedTopologyCatalog
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.TopologyCatalog
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-04 scope 5: the export of the current topology carries what the rows hold; default rows give today's bytes. */
class MigrationsViewModelExportTest {

    private fun queue(name: String) = QueueRow(name, "/", "quorum", 0, 0, 0, "rabbit@n", listOf("rabbit@n"), listOf("rabbit@n"), null, "running")
    private fun exchange(name: String, durable: Boolean = true, autoDelete: Boolean = false, internal: Boolean = false) =
        ExchangeRow(name, "/", "topic", durable, autoDelete, internal)

    private fun export(setup: FakeTopologyCatalog.() -> Unit): MigrationFile {
        val catalog = FakeTopologyCatalog().apply(setup)
        val vm = MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor(catalog, FakeStreamCatalog())),
            topologyCatalog = catalog,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        return runBlocking { vm.exportCurrentTopology() }!!
    }

    /** Recorded from the first release's exporter (pretty print, 4 spaces). */
    private val defaultRowsExport = """
        {
            "version": "0001",
            "description": "Exported broker topology",
            "up": [
                {
                    "action": "declareQueue",
                    "name": "orders",
                    "type": "quorum"
                },
                {
                    "action": "declareExchange",
                    "name": "events",
                    "type": "topic"
                },
                {
                    "action": "declareBinding",
                    "exchange": "events",
                    "queue": "orders",
                    "routingKey": "order.created"
                }
            ]
        }
    """.trimIndent()

    @Test
    fun `rows with every flag at its default give the same file as before, byte for byte`() {
        val file = export {
            addQueue(queue("orders")); addExchange(exchange("events"))
            addBinding(BindingRow("events", "/", "orders", BindingDestinationType.QUEUE, "order.created"))
        }
        assertEquals(defaultRowsExport, file.rawJson)
        assertEquals(MigrationParser.computeChecksum(defaultRowsExport), file.checksum)
    }

    @Test
    fun `a non-durable, auto-delete, internal exchange keeps its flags in the file and in the parsed actions`() {
        val file = export { addExchange(exchange("events.x", durable = false, autoDelete = true, internal = true)) }
        val declared = file.up.filterIsInstance<MigrationAction.DeclareExchange>().single()
        assertEquals(Triple(false, true, true), Triple(declared.durable, declared.autoDelete, declared.internal))
        assertTrue(file.rawJson.contains("\"durable\": false") && file.rawJson.contains("\"autoDelete\": true") && file.rawJson.contains("\"internal\": true"), file.rawJson)
        val reread = (MigrationParser.parse(file.rawJson) as MigrationValidationResult.Valid).migration.up
        assertEquals(file.up, reread, "the exported file must parse back to the same actions")
    }

    @Test
    fun `a binding to an exchange keeps its destination type`() {
        val file = export { addBinding(BindingRow("ex-e2e", "/", "dlx", BindingDestinationType.EXCHANGE, "dead")) }
        val binding = file.up.filterIsInstance<MigrationAction.DeclareBinding>().single()
        assertEquals(BindingDestinationType.EXCHANGE, binding.destinationType)
        assertTrue(file.rawJson.contains("\"destinationType\": \"exchange\""), file.rawJson)
        assertEquals(file.up, (MigrationParser.parse(file.rawJson) as MigrationValidationResult.Valid).migration.up)
    }

    @Test
    fun `exported queues have no arguments and a file without a new feature has none of the new keys`() {
        val file = export {
            addQueue(queue("orders")); addExchange(exchange("events"))
            addBinding(BindingRow("events", "/", "orders", BindingDestinationType.QUEUE, "k"))
        }
        assertTrue(file.up.filterIsInstance<MigrationAction.DeclareQueue>().all { it.arguments.isEmpty() })
        for (key in listOf("autoDelete", "internal", "destinationType", "arguments", "definition", "applyTo", "priority")) {
            assertFalse(file.rawJson.contains("\"$key\""), "default rows must not write \"$key\": ${file.rawJson}")
        }
    }

    // ---- M5-08a scope 7: every page is read, a failed read writes nothing ---------------------------------------------------

    private class Rig(catalog: TopologyCatalog, val files: InMemoryMigrationFileRepository = InMemoryMigrationFileRepository()) {
        val vm = MigrationsViewModel(
            fileRepository = files,
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor()),
            topologyCatalog = catalog,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    private fun queuesNamed(count: Int) = (1..count).map { queue("queue-%03d".format(it)) }

    @Test
    fun `150 queues over four pages are all exported once and no page past the last is asked for`() {
        val fake = FakeTopologyCatalog().apply { queuesNamed(150).forEach { addQueue(it) } }
        val paged = PagedTopologyCatalog(fake, pageSize = 40)
        val rig = Rig(paged)

        val file = runBlocking { rig.vm.exportCurrentTopology() }!!

        val declared = file.up.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }
        assertEquals(150, declared.size, "queues in the exported file")
        assertEquals(queuesNamed(150).map { it.name }.toSet(), declared.toSet())
        assertEquals(listOf(1, 2, 3, 4), paged.pagesRequested.toList(), "pages asked for")
        assertEquals(file.up, (MigrationParser.parse(file.rawJson) as MigrationValidationResult.Valid).migration.up)
    }

    @Test
    fun `one page, an empty broker and an exact multiple of the page size`() {
        for ((count, pages) in listOf(1 to listOf(1), 0 to listOf(1), 80 to listOf(1, 2))) {
            val fake = FakeTopologyCatalog().apply { queuesNamed(count).forEach { addQueue(it) } }
            val paged = PagedTopologyCatalog(fake, pageSize = 40)
            val file = runBlocking { Rig(paged).vm.exportCurrentTopology() }!!
            assertEquals(count, file.up.filterIsInstance<MigrationAction.DeclareQueue>().size, "count $count")
            assertEquals(pages, paged.pagesRequested.toList(), "count $count")
        }
    }

    @Test
    fun `queues across pages and the exchanges and bindings after them are all exported (the queue list never holds a stream)`() {
        val fake = FakeTopologyCatalog().apply {
            queuesNamed(3).forEach { addQueue(it) }; addStream("audit"); addExchange(exchange("events"))
            addBinding(BindingRow("events", "/", "queue-001", BindingDestinationType.QUEUE, "k"))
        }
        val file = runBlocking { Rig(PagedTopologyCatalog(fake, pageSize = 2)).vm.exportCurrentTopology() }!!
        assertEquals(3, file.up.filterIsInstance<MigrationAction.DeclareQueue>().size)
        assertEquals(1, file.up.filterIsInstance<MigrationAction.DeclareExchange>().size)
        assertEquals(1, file.up.filterIsInstance<MigrationAction.DeclareBinding>().size)
    }

    private fun assertFailsLoudlyAndWritesNothing(rig: Rig, why: String) {
        assertNull(runBlocking { rig.vm.exportCurrentTopology() }, "$why: a file was returned")
        assertEquals("The topology could not be read", rig.vm.state.value.errorMessage, why)
        assertTrue(runBlocking { rig.files.listFiles() }.isEmpty(), "$why: a file was written")
        assertTrue(rig.vm.state.value.entries.isEmpty(), "$why: the list shows a file")
    }

    @Test
    fun `a failing queue list gives the error and no file, whatever the failure`() {
        for (failure in listOf(CatalogResult.NotAuthorised, CatalogResult.Unreachable, CatalogResult.Unexpected, CatalogResult.BadPaging, CatalogResult.NotFound)) {
            val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); failCall("queues", failure) }
            assertFailsLoudlyAndWritesNothing(Rig(fake), "queues -> $failure")
        }
    }

    @Test
    fun `a user without the management tag gets the error and no file`() {
        val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); hasManagementTag = false }
        assertFailsLoudlyAndWritesNothing(Rig(fake), "no management tag")
    }

    @Test
    fun `a failure on a later page writes no partial file`() {
        val fake = FakeTopologyCatalog().apply { queuesNamed(150).forEach { addQueue(it) } }
        val paged = PagedTopologyCatalog(fake, pageSize = 40).apply { failPage(3, CatalogResult.Unreachable) }
        assertFailsLoudlyAndWritesNothing(Rig(paged), "page 3 fails")
    }

    @Test
    fun `a failing exchange or binding list gives the error and no file`() {
        for (call in listOf("exchanges", "bindings")) {
            val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); failCall(call, CatalogResult.Unexpected) }
            assertFailsLoudlyAndWritesNothing(Rig(fake), "$call fails")
        }
    }

    @Test
    fun `the error can be dismissed and a later export works`() {
        val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); failCall("queues", CatalogResult.Unreachable) }
        val rig = Rig(fake)
        assertNull(runBlocking { rig.vm.exportCurrentTopology() })
        rig.vm.dismissError()
        assertEquals(null, rig.vm.state.value.errorMessage)
        fake.clearFailures()
        val file = runBlocking { rig.vm.exportCurrentTopology() }!!
        assertNotNull(file)
        assertEquals(1, file.up.filterIsInstance<MigrationAction.DeclareQueue>().size)
    }
}
