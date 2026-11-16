package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.QueueDeclaration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M9-14 step 4 exploratory checks on the export: hostile data, a bigger super stream, the empty vhost, bindings of left-out queues. */
class MigrationsViewModelExportExploratoryTest {

    private class Rig(val catalog: FakeTopologyCatalog) {
        val vm = MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor(catalog, FakeStreamCatalog())),
            topologyCatalog = catalog, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        fun export() = runBlocking { vm.exportCurrentTopology() }
    }

    private fun decl(name: String, type: String = "classic", arguments: Map<String, ArgumentValue> = emptyMap()) = QueueDeclaration(name, "/", type, arguments = arguments)

    @Test
    fun `hostile names and arguments survive the file and parse back, null values and 30 levels of nesting too`() {
        var nested: ArgumentValue = ArgumentValue.Text("leaf")
        repeat(30) { nested = ArgumentValue.Table(mapOf("k$it" to nested)) }
        val name = "<script>alert(1)</script>‮" + "x".repeat(200)
        val args = mapOf("ключ-キー" to ArgumentValue.Text("\"quote\" \\ \n tab\t"), "nothing" to ArgumentValue.Nothing, "huge" to ArgumentValue.Whole(Long.MAX_VALUE), "nested" to nested)
        val rig = Rig(FakeTopologyCatalog().apply { declare(decl(name, arguments = args)) })
        val file = assertNotNull(rig.export())
        assertEquals(args, file.up.filterIsInstance<MigrationAction.DeclareQueue>().single().arguments)
        val parsed = MigrationParser.parse(file.rawJson)
        assertTrue(parsed is MigrationValidationResult.Valid, "the exported file must be valid: $parsed")
        assertEquals(file.up, (parsed as MigrationValidationResult.Valid).migration.up)
    }

    @Test
    fun `the empty vhost exports an empty file without a note`() {
        val rig = Rig(FakeTopologyCatalog())
        assertEquals(0, assertNotNull(rig.export()).up.size)
        assertEquals(null, rig.vm.state.value.exportNote)
    }

    @Test
    fun `CHARACTERISATION the note of a 12 partition super stream names every partition as retention, with the leader locator argument`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            for (i in 0 until 12) addStream("orders-$i", arguments = mapOf("x-queue-leader-locator" to ArgumentValue.Text("balanced")))
        })
        rig.export()
        val note = assertNotNull(rig.vm.state.value.exportNote)
        assertEquals(12, Regex("retention of stream").findAll(note).count(), note)
        println("M9-14 note length for 12 partitions: ${note.length}")
    }

    @Test
    fun `a binding to a left-out queue is dropped but a binding from an exchange to an exchange stays, and a stream binding stays`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            declare(QueueDeclaration("conn", "/", "classic", durable = false, exclusive = true)); addStream("s")
            addExchange(FakeTopologyCatalog.exchange("a")); addExchange(FakeTopologyCatalog.exchange("b"))
            addBinding(BindingRow("a", "/", "conn", BindingDestinationType.QUEUE, "k"))
            addBinding(BindingRow("a", "/", "b", BindingDestinationType.EXCHANGE, "k"))
            addBinding(BindingRow("a", "/", "s", BindingDestinationType.QUEUE, "k"))
        })
        val file = assertNotNull(rig.export())
        assertEquals(setOf("b", "s"), file.up.filterIsInstance<MigrationAction.DeclareBinding>().map { it.queue }.toSet())
        assertFalse(file.rawJson.contains("\"conn\""))
    }

    @Test
    fun `an exchange named like a left-out queue does not hide the binding to the exchange`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            declare(QueueDeclaration("amq.gen-x", "/", "classic", durable = false, autoDelete = true, exclusive = true))
            addExchange(FakeTopologyCatalog.exchange("a")); addExchange(FakeTopologyCatalog.exchange("amq.gen-x"))
            addBinding(BindingRow("a", "/", "amq.gen-x", BindingDestinationType.EXCHANGE, "k"))
        })
        val bindings = assertNotNull(rig.export()).up.filterIsInstance<MigrationAction.DeclareBinding>()
        assertEquals(1, bindings.size)
    }
}
