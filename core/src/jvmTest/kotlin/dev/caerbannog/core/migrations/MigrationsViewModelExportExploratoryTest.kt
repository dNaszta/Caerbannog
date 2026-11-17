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

    private val NOT_WRITABLE = "The topology was read, but a setting in it cannot be written to a migration file."

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
    fun `CHARACTERISATION twelve streams that are not a super stream give ten entries and and 2 more, the settings are other settings`() {
        // M9-14c step 2b: the leader locator is written now, so it cannot be the example any more; x-initial-cluster-size stays unwritable.
        val rig = Rig(FakeTopologyCatalog().apply {
            for (i in 0 until 12) addStream("orders-%02d".format(i), arguments = mapOf("x-initial-cluster-size" to ArgumentValue.Whole(3)))
        })
        rig.export()
        val note = assertNotNull(rig.vm.state.value.exportNote)
        assertEquals(10, Regex("other settings of stream").findAll(note).count(), note)
        assertEquals(0, Regex("retention|leader locator").findAll(note).count(), note)
        assertTrue(note.endsWith("; and 2 more"), note)
        println("M9-14b note length for 12 streams: ${note.length}")
    }

    @Test
    fun `CHARACTERISATION the CLI super stream of 12 partitions is one short entry`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            addExchange(FakeTopologyCatalog.exchange("orders", superStream = true, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true))))
            for (i in 0 until 12) {
                addStream("orders-$i", arguments = mapOf("x-initial-cluster-size" to ArgumentValue.Whole(3)))
                addBinding(BindingRow("orders", "/", "orders-$i", BindingDestinationType.QUEUE, "$i"))
            }
        })
        rig.export()
        assertEquals("Left out: other settings of super stream orders (12 partitions)", rig.vm.state.value.exportNote)
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

    // ---- F1 round 2: an exchange or binding argument that could not be typed makes the export fail loudly, never write a thinner file ----

    private fun assertFailsLoudly(rig: Rig, why: String, message: String = NOT_WRITABLE) {
        assertEquals(null, rig.export(), "$why: a file was returned")
        assertEquals(message, rig.vm.state.value.errorMessage, why)
        assertEquals(null, rig.vm.state.value.exportNote, why)
        assertTrue(runBlocking { rig.vm.state.value.entries }.isEmpty(), "$why: a file is listed")
    }

    @Test
    fun `an exchange with an argument that could not be typed fails the export and writes no file`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            declare(decl("orders", "quorum")); addExchange(FakeTopologyCatalog.exchange("fine"))
            addExchange(dev.caerbannog.core.topology.ExchangeRow("odd", "/", "headers", true, false, false, arguments = mapOf("deep" to "[[[...]]]"), typedArgumentsComplete = false))
        })
        assertFailsLoudly(rig, "incomplete exchange")
    }

    @Test
    fun `a binding with an argument that could not be typed fails the export and writes no file`() {
        val rig = Rig(FakeTopologyCatalog().apply {
            declare(decl("orders", "quorum")); addExchange(FakeTopologyCatalog.exchange("events"))
            addBinding(BindingRow("events", "/", "orders", BindingDestinationType.QUEUE, "k", arguments = mapOf("deep" to "[[[...]]]"), typedArgumentsComplete = false))
        })
        assertFailsLoudly(rig, "incomplete binding")
    }

    @Test
    fun `an incomplete binding of a left-out queue exports fine, binding and queue are both left out, and a later export works too`() {
        val fake = FakeTopologyCatalog().apply {
            declare(QueueDeclaration("conn", "/", "classic", durable = false, exclusive = true)); addExchange(FakeTopologyCatalog.exchange("events"))
            addBinding(BindingRow("events", "/", "conn", BindingDestinationType.QUEUE, "k", typedArgumentsComplete = false))
        }
        val rig = Rig(fake)
        val file = assertNotNull(rig.export())
        assertTrue(file.up.none { it is MigrationAction.DeclareBinding || it is MigrationAction.DeclareQueue }, "${file.up}")
        assertEquals(null, rig.vm.state.value.errorMessage)
        assertEquals("Left out: exclusive queue conn", rig.vm.state.value.exportNote)
        fake.deleteBinding("events", "conn", "k")
        assertNotNull(rig.export())
    }

    @Test
    fun `an incomplete binding that IS written still fails loud with the new sentence, and a later export of a complete topology works`() {
        val fake = FakeTopologyCatalog().apply {
            declare(decl("orders", "quorum")); addExchange(FakeTopologyCatalog.exchange("events"))
            addBinding(BindingRow("events", "/", "orders", BindingDestinationType.QUEUE, "k", typedArgumentsComplete = false))
        }
        val rig = Rig(fake)
        assertFailsLoudly(rig, "incomplete binding that is written")
        fake.deleteBinding("events", "orders", "k")
        rig.vm.dismissError()
        assertNotNull(rig.export())
    }
}
