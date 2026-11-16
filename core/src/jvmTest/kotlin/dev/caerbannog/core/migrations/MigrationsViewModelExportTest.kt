package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.TopologyCatalog
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
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
    fun `a file without any new feature has none of the new keys`() {
        val file = export {
            addQueue(queue("orders")); addExchange(exchange("events"))
            addBinding(BindingRow("events", "/", "orders", BindingDestinationType.QUEUE, "k"))
        }
        assertTrue(file.up.filterIsInstance<MigrationAction.DeclareQueue>().all { it.arguments.isEmpty() })
        for (key in listOf("autoDelete", "internal", "destinationType", "arguments", "definition", "applyTo", "priority")) {
            assertFalse(file.rawJson.contains("\"$key\""), "default rows must not write \"$key\": ${file.rawJson}")
        }
    }

    // ---- M9-14: arguments, flags, policies, streams, exclusions (facts: docs/tasks/M9-14-spike-results.md) -------------------

    private fun declared(name: String, type: String = "classic", durable: Boolean = true, autoDelete: Boolean = false, exclusive: Boolean = false, arguments: Map<String, ArgumentValue> = emptyMap()) =
        QueueDeclaration(name = name, vhost = "/", type = type, durable = durable, autoDelete = autoDelete, exclusive = exclusive, arguments = arguments)

    private fun reparsed(file: MigrationFile): List<MigrationAction> = (MigrationParser.parse(file.rawJson) as MigrationValidationResult.Valid).migration.up

    private fun queueNamed(file: MigrationFile, name: String) = file.up.filterIsInstance<MigrationAction.DeclareQueue>().single { it.name == name }

    @Test
    fun `a queue keeps its type, its durable and auto-delete flags and its arguments, numbers stay numbers`() {
        val args = mapOf(
            "x-message-ttl" to ArgumentValue.Whole(60000), "x-max-length" to ArgumentValue.Whole(1000),
            "x-dead-letter-exchange" to ArgumentValue.Text("dlx"), "x-overflow" to ArgumentValue.Text("reject-publish"),
        )
        val file = export {
            declare(declared("jobs", "classic", autoDelete = true, arguments = args))
            declare(declared("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-dead-letter-exchange" to ArgumentValue.Text("dlx"))))
        }
        val jobs = queueNamed(file, "jobs")
        assertEquals("classic", jobs.type)
        assertTrue(jobs.durable && jobs.autoDelete)
        assertEquals(args, jobs.arguments)
        assertEquals(mapOf("x-delivery-limit" to ArgumentValue.Whole(5), "x-dead-letter-exchange" to ArgumentValue.Text("dlx")), queueNamed(file, "orders").arguments)
        assertTrue(file.rawJson.contains("\"x-delivery-limit\": 5"), "a number must be written as a JSON number: ${file.rawJson}")
        assertTrue(file.rawJson.contains("\"x-message-ttl\": 60000"), file.rawJson)
        assertEquals(file.up, reparsed(file), "the file must parse back to the same actions")
    }

    @Test
    fun `every JSON type of an argument is written as it is, a number as text stays text`() {
        val args = mapOf(
            "whole" to ArgumentValue.Whole(3), "decimal" to ArgumentValue.Decimal(1.5), "flag" to ArgumentValue.Flag(true),
            "text" to ArgumentValue.Text("eu"), "numberAsText" to ArgumentValue.Text("5"),
            "items" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"))),
            "table" to ArgumentValue.Table(mapOf("inner" to ArgumentValue.Flag(false))),
        )
        val file = export { declare(declared("typed", arguments = args)) }
        assertEquals(args, queueNamed(file, "typed").arguments)
        assertTrue(file.rawJson.contains("\"numberAsText\": \"5\"") && file.rawJson.contains("\"whole\": 3") && file.rawJson.contains("\"flag\": true"), file.rawJson)
        assertEquals(file.up, reparsed(file))
    }

    @Test
    fun `the queue type argument is never written, the type key says it (a file that holds it is refused)`() {
        val file = export {
            declare(declared("orders", "quorum", arguments = mapOf("x-queue-type" to ArgumentValue.Text("quorum"), "x-delivery-limit" to ArgumentValue.Whole(5))))
        }
        assertFalse(file.rawJson.contains("x-queue-type"), file.rawJson)
        assertTrue(MigrationParser.parse(file.rawJson) is MigrationValidationResult.Valid, "the exported file must be a valid migration")
        assertEquals(mapOf("x-delivery-limit" to ArgumentValue.Whole(5)), queueNamed(file, "orders").arguments)
    }

    @Test
    fun `exchange arguments and binding arguments keep their JSON types`() {
        val file = export {
            declare(declared("q1", "quorum"))
            addExchange(ExchangeRow("events", "/", "topic", true, false, false, typedArguments = mapOf("alternate-exchange" to ArgumentValue.Text("dlx"))))
            addExchange(ExchangeRow("orders", "/", "direct", true, false, false, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true))))
            addExchange(ExchangeRow("hdr", "/", "headers", true, false, false))
            addBinding(
                BindingRow(
                    "hdr", "/", "q1", BindingDestinationType.QUEUE, "",
                    typedArguments = mapOf(
                        "x-match" to ArgumentValue.Text("all"), "region" to ArgumentValue.Text("eu"), "level" to ArgumentValue.Whole(3),
                        "urgent" to ArgumentValue.Flag(true), "ratio" to ArgumentValue.Decimal(1.5),
                    ),
                ),
            )
            addBinding(BindingRow("events", "/", "q1", BindingDestinationType.QUEUE, "k", typedArguments = mapOf("n" to ArgumentValue.Text("5"))))
        }
        val exchanges = file.up.filterIsInstance<MigrationAction.DeclareExchange>().associateBy { it.name }
        assertEquals(mapOf("alternate-exchange" to ArgumentValue.Text("dlx")), exchanges.getValue("events").arguments)
        assertEquals(mapOf("x-super-stream" to ArgumentValue.Flag(true)), exchanges.getValue("orders").arguments)
        assertTrue(exchanges.getValue("hdr").arguments.isEmpty())
        val bindings = file.up.filterIsInstance<MigrationAction.DeclareBinding>().associateBy { it.exchange }
        assertEquals(
            mapOf("x-match" to ArgumentValue.Text("all"), "region" to ArgumentValue.Text("eu"), "level" to ArgumentValue.Whole(3), "urgent" to ArgumentValue.Flag(true), "ratio" to ArgumentValue.Decimal(1.5)),
            bindings.getValue("hdr").arguments,
        )
        assertEquals(mapOf("n" to ArgumentValue.Text("5")), bindings.getValue("events").arguments)
        assertTrue(file.rawJson.contains("\"x-super-stream\": true") && file.rawJson.contains("\"level\": 3"), file.rawJson)
        assertEquals(file.up, reparsed(file))
    }

    @Test
    fun `an internal exchange keeps its flag and the built-in exchanges are not declared but a binding on one is kept`() {
        val file = export {
            declare(declared("sensors", "quorum"))
            addExchange(exchange("internal-x", internal = true)); addExchange(exchange("amq.topic"))
            addBinding(BindingRow("amq.topic", "/", "sensors", BindingDestinationType.QUEUE, "sensors.#"))
        }
        assertTrue(file.up.filterIsInstance<MigrationAction.DeclareExchange>().single().internal)
        assertEquals(listOf("internal-x"), file.up.filterIsInstance<MigrationAction.DeclareExchange>().map { it.name })
        val binding = file.up.filterIsInstance<MigrationAction.DeclareBinding>().single()
        assertEquals("amq.topic" to "sensors.#", binding.exchange to binding.routingKey)
    }

    @Test
    fun `policies keep name, pattern, apply-to, priority and typed definition, operator policies are operator policies`() {
        val file = export {
            declare(declared("q1", "quorum"))
            addPolicy(PolicyRow("limits", "/", "^q-", "queues", 7, mapOf("max-length" to ArgumentValue.Whole(5000), "overflow" to ArgumentValue.Text("reject-publish"))))
            addPolicy(PolicyRow("everything", "/", ".*", "all", -1, mapOf("queue-leader-locator" to ArgumentValue.Text("balanced"))))
            addOperatorPolicy(PolicyRow("op-limits", "/", "^q-", "queues", 3, mapOf("max-length" to ArgumentValue.Whole(100000))))
            addPolicy(PolicyRow("elsewhere", "other", ".*", "all", 0, mapOf("max-length" to ArgumentValue.Whole(1))))
        }
        val policies = file.up.filterIsInstance<MigrationAction.DeclarePolicy>().associateBy { it.name }
        assertEquals(setOf("limits", "everything"), policies.keys, "only the policies of this virtual host")
        assertEquals(
            MigrationAction.DeclarePolicy("limits", "^q-", mapOf("max-length" to ArgumentValue.Whole(5000), "overflow" to ArgumentValue.Text("reject-publish")), "/", "queues", 7),
            policies.getValue("limits"),
        )
        assertEquals(-1, policies.getValue("everything").priority)
        assertEquals("all", policies.getValue("everything").applyTo)
        assertEquals(
            listOf(MigrationAction.DeclareOperatorPolicy("op-limits", "^q-", mapOf("max-length" to ArgumentValue.Whole(100000)), "/", "queues", 3)),
            file.up.filterIsInstance<MigrationAction.DeclareOperatorPolicy>(),
        )
        assertTrue(file.rawJson.contains("\"max-length\": 5000") && file.rawJson.contains("\"priority\": 7"), file.rawJson)
        assertEquals(file.up, reparsed(file))
    }

    @Test
    fun `streams and the partitions of a super stream are exported, the super stream keeps its marker and partition order`() {
        val file = export {
            addStream("audit")
            addExchange(ExchangeRow("orders", "/", "direct", true, false, false, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true))))
            for (i in 0..2) {
                addStream("orders-$i")
                addBinding(BindingRow("orders", "/", "orders-$i", BindingDestinationType.QUEUE, "$i", typedArguments = mapOf("x-stream-partition-order" to ArgumentValue.Whole(i.toLong()))))
            }
        }
        assertEquals(setOf("audit", "orders-0", "orders-1", "orders-2"), file.up.filterIsInstance<MigrationAction.DeclareStream>().map { it.name }.toSet())
        val bindings = file.up.filterIsInstance<MigrationAction.DeclareBinding>().sortedBy { it.queue }
        assertEquals(listOf(0L, 1L, 2L), bindings.map { (it.arguments.getValue("x-stream-partition-order") as ArgumentValue.Whole).value })
        assertEquals(file.up, reparsed(file))
    }

    @Test
    fun `exclusive, server-named, MQTT subscription and non-durable queues are not exported, nor the bindings to them`() {
        val file = export {
            declare(declared("keep", "quorum"))
            declare(declared("session-exclusive", "classic", durable = false, exclusive = true))
            declare(declared("amq.gen-rzkEUd4HTHeWWNo24x6BCw", "classic", durable = false, autoDelete = true, exclusive = true))
            declare(declared("mqtt-subscription-client1qos0", "rabbit_mqtt_qos0_queue", exclusive = true))
            declare(declared("mqtt-subscription-client2qos1", "classic", exclusive = true))
            declare(declared("mqtt-subscription-persistentqos1", "classic", exclusive = false, arguments = mapOf("x-expires" to ArgumentValue.Whole(86400000))))
            declare(declared("transient", "classic", durable = false, autoDelete = true))
            addExchange(exchange("events"))
            for (name in listOf("keep", "session-exclusive", "amq.gen-rzkEUd4HTHeWWNo24x6BCw", "mqtt-subscription-client2qos1", "mqtt-subscription-persistentqos1", "transient")) {
                addBinding(BindingRow("events", "/", name, BindingDestinationType.QUEUE, "k"))
            }
        }
        assertEquals(listOf("keep"), file.up.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
        assertEquals(listOf("keep"), file.up.filterIsInstance<MigrationAction.DeclareBinding>().map { it.queue })
        assertTrue(file.up.none { it is MigrationAction.DeclareStream })
    }

    @Test
    fun `a durable auto-delete classic queue is exported with its flag, the broker accepts it`() {
        val file = export { declare(declared("jobs", "classic", durable = true, autoDelete = true)) }
        assertTrue(queueNamed(file, "jobs").let { it.durable && it.autoDelete })
    }

    @Test
    fun `bindings come after every queue, exchange and stream, and policies come last`() {
        val file = export {
            declare(declared("q1", "quorum")); addStream("s1"); addExchange(exchange("events"))
            addBinding(BindingRow("events", "/", "q1", BindingDestinationType.QUEUE, "k", typedArguments = mapOf("n" to ArgumentValue.Whole(1))))
            addPolicy(PolicyRow("p", "/", "^q", "queues", 1, mapOf("max-length" to ArgumentValue.Whole(10))))
            addOperatorPolicy(PolicyRow("op", "/", "^q", "queues", 1, mapOf("max-length" to ArgumentValue.Whole(10))))
        }
        val kinds = file.up.map { it.actionName }
        val lastEnd = kinds.indexOfLast { it == "declareQueue" || it == "declareExchange" || it == "declareStream" }
        val firstBinding = kinds.indexOf("declareBinding"); val lastBinding = kinds.lastIndexOf("declareBinding")
        val firstPolicy = kinds.indexOfFirst { it == "declarePolicy" || it == "declareOperatorPolicy" }
        assertTrue(firstBinding > lastEnd, "bindings before their ends: $kinds")
        assertTrue(firstPolicy > lastBinding, "a policy before the bindings: $kinds")
        assertTrue(kinds.indexOf("declarePolicy") >= 0 && kinds.indexOf("declareOperatorPolicy") >= 0)
        assertEquals(kinds.size - 2, firstPolicy, "the two policies are the last two actions: $kinds")
    }

    @Test
    fun `the export asks the catalog only for its reads and reads each once`() {
        val catalog = FakeTopologyCatalog().apply { declare(declared("q1", "quorum")); addExchange(exchange("events")) }
        val vm = MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor(catalog, FakeStreamCatalog())),
            topologyCatalog = catalog, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        runBlocking { vm.exportCurrentTopology() }!!
        assertEquals(setOf("queueDeclarations", "exchanges", "bindings", "policies", "operatorPolicies"), catalog.calls.toSet(), "calls were ${catalog.calls}")
        assertEquals(catalog.calls.size, catalog.calls.toSet().size, "each read once: ${catalog.calls}")
    }

    @Test
    fun `policies that cannot be read make the export fail loudly and write no file, whatever the failure`() {
        for (call in listOf("policies", "operatorPolicies")) {
            for (failure in listOf(CatalogResult.NotAuthorised, CatalogResult.Unreachable, CatalogResult.Unexpected, CatalogResult.NotFound)) {
                val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); addPolicy(PolicyRow("p", "/", ".*", "all", 0, emptyMap())); failCall(call, failure) }
                assertFailsLoudlyAndWritesNothing(Rig(fake), "$call -> $failure")
            }
        }
    }

    @Test
    fun `a failing list of queue declarations gives the error and no file, whatever the failure`() {
        for (failure in listOf(CatalogResult.NotAuthorised, CatalogResult.Unreachable, CatalogResult.Unexpected, CatalogResult.BadPaging, CatalogResult.NotFound)) {
            val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); failCall("queueDeclarations", failure) }
            assertFailsLoudlyAndWritesNothing(Rig(fake), "queueDeclarations -> $failure")
        }
    }

    // ---- M9-14: what the export says it left out ----------------------------------------------------------------------------

    @Test
    fun `nothing left out gives no note`() {
        val rig = Rig(FakeTopologyCatalog().apply { declare(declared("orders", "quorum")); addStream("audit"); addPolicy(PolicyRow("p", "/", ".*", "all", 0, emptyMap())) })
        runBlocking { rig.vm.exportCurrentTopology() }!!
        assertNull(rig.vm.state.value.exportNote, "a stream with no arguments loses nothing")
    }

    @Test
    fun `a stream with retention is exported by name and the note names it and says retention is left out`() {
        val rig = Rig(FakeTopologyCatalog().apply { addStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000))) })
        val file = runBlocking { rig.vm.exportCurrentTopology() }!!
        assertEquals(listOf("audit"), file.up.filterIsInstance<MigrationAction.DeclareStream>().map { it.name })
        val note = rig.vm.state.value.exportNote
        assertNotNull(note, "retention was dropped silently")
        assertTrue(note.contains("audit") && note.contains("retention", ignoreCase = true), note)
        assertFalse(file.rawJson.contains("x-max-age"), "DeclareStream has no arguments; nothing may be invented")
    }

    @Test
    fun `the note names the exclusive and the transient queues that were left out`() {
        val rig = Rig(
            FakeTopologyCatalog().apply {
                declare(declared("orders", "quorum"))
                declare(declared("session-exclusive", "classic", durable = false, exclusive = true))
                declare(declared("scratch", "classic", durable = false, autoDelete = true))
            },
        )
        runBlocking { rig.vm.exportCurrentTopology() }!!
        val note = rig.vm.state.value.exportNote
        assertNotNull(note)
        assertTrue(note.contains("session-exclusive") && note.contains("exclusive", ignoreCase = true), note)
        assertTrue(note.contains("scratch") && note.contains("transient", ignoreCase = true), note)
        assertFalse(note.contains("orders"), "a queue that was exported is not in the note: $note")
    }

    @Test
    fun `a failed export has no note and a later export without losses clears an old one`() {
        val fake = FakeTopologyCatalog().apply { addStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"))) }
        val rig = Rig(fake)
        runBlocking { rig.vm.exportCurrentTopology() }!!
        assertNotNull(rig.vm.state.value.exportNote)
        fake.deleteQueue("audit"); fake.addStream("audit2")
        runBlocking { rig.vm.exportCurrentTopology() }!!
        assertNull(rig.vm.state.value.exportNote, "the note belongs to the last export")
        fake.failCall("policies", CatalogResult.Unreachable)
        assertNull(runBlocking { rig.vm.exportCurrentTopology() })
        assertNull(rig.vm.state.value.exportNote, "a failed export reports the error, not a note")
    }

    // ---- M9-14b: a setting the file cannot carry says so, a read that fails says that ------------------------------------------

    // The text is pinned here, so these tests do not depend on where the constant lives (the brief: `MigrationsViewModel.TOPOLOGY_NOT_WRITABLE`).
    private val NOT_WRITABLE = "The topology was read, but a setting in it cannot be written to a migration file."

    private fun assertNotWritable(rig: Rig, why: String) {
        assertNull(runBlocking { rig.vm.exportCurrentTopology() }, "$why: a file was returned")
        assertEquals(NOT_WRITABLE, rig.vm.state.value.errorMessage, why)
        assertNull(rig.vm.state.value.exportNote, "$why: a failed export has no note")
        assertTrue(runBlocking { rig.files.listFiles() }.isEmpty(), "$why: a file was written")
        assertTrue(rig.vm.state.value.entries.isEmpty(), "$why: the list shows a file")
    }

    @Test
    fun `a queue argument the model refuses says the topology was read but cannot be written, and writes no file`() {
        val fake = FakeTopologyCatalog().apply { declare(declared("orders", "quorum").copy(typedArgumentsComplete = false)) }
        assertNotWritable(Rig(fake), "incomplete queue argument")
    }

    @Test
    fun `a policy definition or an operator policy definition the model refuses says the same`() {
        val bad = PolicyRow("p", "/", ".*", "all", 0, emptyMap(), definitionComplete = false)
        assertNotWritable(Rig(FakeTopologyCatalog().apply { declare(declared("orders", "quorum")); addPolicy(bad) }), "incomplete policy")
        assertNotWritable(Rig(FakeTopologyCatalog().apply { declare(declared("orders", "quorum")); addOperatorPolicy(bad) }), "incomplete operator policy")
    }

    @Test
    fun `a read that fails keeps the old sentence, not the new one, and the two sentences differ`() {
        for (call in listOf("queueDeclarations", "exchanges", "bindings", "policies", "operatorPolicies")) {
            val rig = Rig(FakeTopologyCatalog().apply { declare(declared("orders", "quorum")); failCall(call, CatalogResult.Unexpected) })
            assertFailsLoudlyAndWritesNothing(rig, call)
        }
    }

    @Test
    fun `a not writable export clears the note of the export before it`() {
        val fake = FakeTopologyCatalog().apply { addStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"))) }
        val rig = Rig(fake)
        runBlocking { rig.vm.exportCurrentTopology() }!!
        assertNotNull(rig.vm.state.value.exportNote)
        fake.declare(declared("orders", "quorum").copy(typedArgumentsComplete = false))
        assertNull(runBlocking { rig.vm.exportCurrentTopology() })
        assertNull(rig.vm.state.value.exportNote)
        assertEquals(NOT_WRITABLE, rig.vm.state.value.errorMessage)
    }

    @Test
    fun `the note shown after an export is the one line of the wording table`() {
        val rig = Rig(
            FakeTopologyCatalog().apply {
                addStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-queue-leader-locator" to ArgumentValue.Text("balanced")))
                declare(declared("session-1", "classic", durable = false, exclusive = true))
            },
        )
        runBlocking { rig.vm.exportCurrentTopology() }!!
        assertEquals("Left out: retention and leader locator of stream audit; exclusive queue session-1", rig.vm.state.value.exportNote)
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
    fun `150 queues are all exported once (the catalog pages by itself, the view model asks once)`() {
        val fake = FakeTopologyCatalog().apply { queuesNamed(150).forEach { addQueue(it) } }
        val rig = Rig(fake)

        val file = runBlocking { rig.vm.exportCurrentTopology() }!!

        val declared = file.up.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name }
        assertEquals(150, declared.size, "queues in the exported file")
        assertEquals(queuesNamed(150).map { it.name }.toSet(), declared.toSet())
        assertEquals(1, fake.calls.count { it == "queueDeclarations" }, "calls: ${fake.calls}")
        assertEquals(file.up, (MigrationParser.parse(file.rawJson) as MigrationValidationResult.Valid).migration.up)
    }

    @Test
    fun `no queue and an empty broker give a file with no actions`() {
        val file = runBlocking { Rig(FakeTopologyCatalog()).vm.exportCurrentTopology() }!!
        assertEquals(0, file.up.size)
    }

    private fun assertFailsLoudlyAndWritesNothing(rig: Rig, why: String) {
        assertNull(runBlocking { rig.vm.exportCurrentTopology() }, "$why: a file was returned")
        assertEquals("The topology could not be read", rig.vm.state.value.errorMessage, why)
        assertTrue(runBlocking { rig.files.listFiles() }.isEmpty(), "$why: a file was written")
        assertTrue(rig.vm.state.value.entries.isEmpty(), "$why: the list shows a file")
    }

    @Test
    fun `a user without the management tag gets the error and no file`() {
        val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); hasManagementTag = false }
        assertFailsLoudlyAndWritesNothing(Rig(fake), "no management tag")
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
        val fake = FakeTopologyCatalog().apply { addQueue(queue("orders")); failCall("queueDeclarations", CatalogResult.Unreachable) }
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
