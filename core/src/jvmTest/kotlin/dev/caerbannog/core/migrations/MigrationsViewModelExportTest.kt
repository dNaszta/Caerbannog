package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
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
        return runBlocking { vm.exportCurrentTopology() }
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
}
