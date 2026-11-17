package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14b: the pure rules of the export note (what each stream, super stream and connection queue says about what the file leaves out),
 * the cap of ten entries, the typed outcome (`Written` / `NotWritable`) and the completeness check that covers only what is written.
 * No broker, no view model: [TopologyExport.actions] is called directly.
 *
 * M9-14c step 2b: a stream now CARRIES its four settings (`x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`,
 * `x-queue-leader-locator`), so the words "retention" and "leader locator" are gone (`RETENTION_WORD`, `LEADER_LOCATOR_WORD` are removed): the
 * note of a stream keeps ONLY "other settings" (an argument that is none of the four, a value `StreamSettingRules` refuses, or an argument that
 * could not be typed). What the file writes is in `TopologyExportStreamSettingsTest`. The examples that used the leader locator or the retention
 * as the thing left out use `x-initial-cluster-size`, a setting the file cannot carry.
 */
class TopologyExportNoteTest {

    private val leader = "x-queue-leader-locator" to ArgumentValue.Text("balanced")
    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val cluster = "x-initial-cluster-size" to ArgumentValue.Whole(3)

    private fun stream(name: String, vararg arguments: Pair<String, ArgumentValue>) = QueueDeclaration(name, "/", "stream", arguments = arguments.toMap())
    private fun exclusive(name: String) = QueueDeclaration(name, "/", "classic", durable = false, exclusive = true)
    private fun superExchange(name: String) = ExchangeRow(name, "/", "direct", true, false, false, superStream = true, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true)))
    private fun toQueue(source: String, destination: String) = BindingRow(source, "/", destination, BindingDestinationType.QUEUE, "k")

    private fun run(
        declarations: List<QueueDeclaration> = emptyList(),
        exchanges: List<ExchangeRow> = emptyList(),
        bindings: List<BindingRow> = emptyList(),
        policies: List<PolicyRow> = emptyList(),
        operatorPolicies: List<PolicyRow> = emptyList(),
    ): TopologyExportOutcome = TopologyExport.actions("/", declarations, exchanges, bindings, policies, operatorPolicies)

    private fun written(outcome: TopologyExportOutcome): TopologyExportResult = assertIs<TopologyExportOutcome.Written>(outcome, "the export was refused: $outcome").result

    private fun note(
        declarations: List<QueueDeclaration> = emptyList(),
        exchanges: List<ExchangeRow> = emptyList(),
        bindings: List<BindingRow> = emptyList(),
    ): String? = written(run(declarations, exchanges, bindings)).note

    // ---- the wording table, one stream ----------------------------------------------------------------------------------------

    @Test
    fun `a stream with any of the four settings says nothing, the file carries them`() {
        for ((key, value) in listOf(age, "x-max-length-bytes" to ArgumentValue.Whole(5), "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(5), leader)) {
            assertNull(note(listOf(stream("audit", key to value))), key)
        }
        assertNull(note(listOf(stream("audit", age, leader))))
    }

    @Test
    fun `a stream with any other argument says other settings, with the four settings or without`() {
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", cluster))))
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", age, "x-other" to ArgumentValue.Flag(true)))))
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", leader, "x-other" to ArgumentValue.Flag(true)))))
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", age, leader, "x-other" to ArgumentValue.Flag(true)))))
    }

    @Test
    fun `a value of the four keys that the rules refuse is other settings too, never a word about retention`() {
        for ((key, value) in listOf(
            "x-max-age" to ArgumentValue.Text("7d"), "x-max-age" to ArgumentValue.Text("604800"), "x-max-age" to ArgumentValue.Whole(7),
            "x-max-length-bytes" to ArgumentValue.Whole(0), "x-max-length-bytes" to ArgumentValue.Text("1gb"),
            "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(-1),
            "x-queue-leader-locator" to ArgumentValue.Text("nearest"), "x-queue-leader-locator" to ArgumentValue.Text("Balanced"),
        )) {
            val note = note(listOf(stream("audit", key to value)))
            assertEquals("Left out: other settings of stream audit", note, "$key $value")
            assertFalse(note!!.contains("retention") || note.contains("leader locator"), note)
        }
    }

    @Test
    fun `the words never name retention or the leader locator, whatever the arguments are`() {
        val all = listOf(
            listOf(age), listOf(leader), listOf(age, leader), listOf(age, leader, cluster), listOf(cluster, leader, age),
            listOf("x-max-age" to ArgumentValue.Text("7d"), "x-queue-leader-locator" to ArgumentValue.Text("nearest")),
        )
        for (arguments in all) {
            val text = note(listOf(stream("audit", *arguments.toTypedArray()))).orEmpty()
            assertFalse(text.contains("retention") || text.contains("leader locator"), "$arguments gave: $text")
        }
    }

    @Test
    fun `a stream without arguments, or with only x-queue-type, has no entry and no note`() {
        assertNull(note(listOf(stream("audit"), stream("audit2", "x-queue-type" to ArgumentValue.Text("stream")))))
    }

    @Test
    fun `entries are joined with a semicolon, a stream first and the exclusive and the transient queue as before`() {
        val transient = QueueDeclaration("scratch", "/", "classic", durable = false, autoDelete = true)
        assertEquals(
            "Left out: other settings of stream audit; exclusive queue session-1; transient queue scratch (cannot be applied)",
            note(listOf(stream("audit", cluster), exclusive("session-1"), transient)),
        )
    }

    @Test
    fun `the constants and builders are the words of the note`() {
        assertEquals("Left out: ", TopologyExport.NOTE_PREFIX)
        assertEquals("; ", TopologyExport.NOTE_SEPARATOR)
        assertEquals(10, TopologyExport.MAX_NOTE_ENTRIES)
        assertEquals("other settings", TopologyExport.OTHER_SETTINGS_WORD)
        assertEquals("other settings of stream audit", TopologyExport.streamNote(listOf(TopologyExport.OTHER_SETTINGS_WORD), "audit"))
        assertEquals("other settings of super stream orders (3 partitions)", TopologyExport.superStreamNote(listOf(TopologyExport.OTHER_SETTINGS_WORD), "orders", 3))
        assertEquals("other settings of super stream orders (1 partition)", TopologyExport.superStreamNote(listOf(TopologyExport.OTHER_SETTINGS_WORD), "orders", 1))
        assertEquals("and 2 more", TopologyExport.moreNote(2))
        assertEquals("exclusive queue session-1", TopologyExport.exclusiveQueueNote("session-1"))
        assertEquals("transient queue scratch (cannot be applied)", TopologyExport.transientQueueNote("scratch"))
    }

    @Test
    fun `a stream whose argument the parser refused is exported and named as other settings, alone or with retention`() {
        val refused = stream("audit").copy(typedArgumentsComplete = false)
        val outcome = run(declarations = listOf(refused))
        val result = written(outcome)
        assertEquals(listOf("audit"), result.actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name })
        assertEquals("Left out: other settings of stream audit", result.note)
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", age).copy(typedArgumentsComplete = false))))
        assertEquals("Left out: other settings of stream audit", note(listOf(stream("audit", leader).copy(typedArgumentsComplete = false))))
    }

    @Test
    fun `a super stream partition with a refused argument adds other settings to the union`() {
        val (decls, exchanges, bindings) = superStream("orders", 3) { arrayOf(leader) }
        val withRefused = decls.mapIndexed { i, d -> if (i == 1) d.copy(typedArgumentsComplete = false) else d }
        assertEquals("Left out: other settings of super stream orders (3 partitions)", note(withRefused, exchanges, bindings))
        assertNull(noteOf(Triple(decls, exchanges, bindings)), "partitions that carry only the leader locator lose nothing")
        val onlyRefused = superStream("orders", 2) { emptyArray() }.let { (d, e, b) -> note(d.map { it.copy(typedArgumentsComplete = false) }, e, b) }
        assertEquals("Left out: other settings of super stream orders (2 partitions)", onlyRefused)
    }

    // ---- super streams ----------------------------------------------------------------------------------------------------------

    private fun superStream(name: String, partitions: Int, arguments: (Int) -> Array<Pair<String, ArgumentValue>>): Triple<List<QueueDeclaration>, List<ExchangeRow>, List<BindingRow>> =
        Triple(
            (0 until partitions).map { stream("$name-$it", *arguments(it)) },
            listOf(superExchange(name)),
            (0 until partitions).map { toQueue(name, "$name-$it") },
        )

    private fun noteOf(parts: Triple<List<QueueDeclaration>, List<ExchangeRow>, List<BindingRow>>) = note(parts.first, parts.second, parts.third)

    @Test
    fun `a super stream is one entry with its partition count, not one per partition`() {
        assertEquals("Left out: other settings of super stream orders (3 partitions)", noteOf(superStream("orders", 3) { arrayOf(cluster, leader) }))
    }

    @Test
    fun `one partition says 1 partition`() {
        assertEquals("Left out: other settings of super stream orders (1 partition)", noteOf(superStream("orders", 1) { arrayOf(cluster) }))
    }

    @Test
    fun `partitions that carry the leader locator and a retention lose nothing, the CLI gives every partition balanced`() {
        assertNull(noteOf(superStream("orders", 3) { arrayOf(leader) }))
        assertNull(noteOf(superStream("orders", 3) { arrayOf(age, leader) }))
    }

    @Test
    fun `the entry is one line whatever the partitions hold, the count is all partitions also those without other settings`() {
        val onlyOne = superStream("orders", 3) { i -> if (i == 2) arrayOf(cluster) else arrayOf(leader) }
        assertEquals("Left out: other settings of super stream orders (3 partitions)", noteOf(onlyOne))
        val mixed = superStream("orders", 4) { i -> when (i) { 0 -> arrayOf(age); 1 -> arrayOf(cluster); else -> emptyArray() } }
        assertEquals("Left out: other settings of super stream orders (4 partitions)", noteOf(mixed))
    }

    @Test
    fun `a super stream where no partition has settings has no entry`() {
        assertNull(noteOf(superStream("orders", 3) { emptyArray() }))
    }

    @Test
    fun `a stream that is not a partition keeps its own entry next to the super stream`() {
        val (decls, exchanges, bindings) = superStream("orders", 2) { arrayOf(cluster) }
        val note = note(decls + stream("audit", cluster), exchanges, bindings)!!
        assertEquals(setOf("other settings of stream audit", "other settings of super stream orders (2 partitions)"), note.removePrefix("Left out: ").split("; ").toSet())
        assertFalse(note.contains("orders-0"), note)
    }

    @Test
    fun `a partition belongs to the first super stream by exchange name`() {
        val decls = listOf(stream("s", cluster))
        val exchanges = listOf(superExchange("b"), superExchange("a"))
        val bindings = listOf(toQueue("b", "s"), toQueue("a", "s"))
        assertEquals("Left out: other settings of super stream a (1 partition)", note(decls, exchanges, bindings))
    }

    @Test
    fun `an ordinary exchange bound to a stream does not turn the stream into a partition`() {
        val note = note(listOf(stream("audit", cluster)), listOf(ExchangeRow("events", "/", "direct", true, false, false)), listOf(toQueue("events", "audit")))
        assertEquals("Left out: other settings of stream audit", note)
    }

    // ---- the cap ------------------------------------------------------------------------------------------------------------------

    private fun exclusives(count: Int) = (1..count).map { exclusive("conn-%02d".format(it)) }

    @Test
    fun `exactly ten entries are all named, no and 0 more`() {
        val note = note(exclusives(10))!!
        val entries = note.removePrefix("Left out: ").split("; ")
        assertEquals(10, entries.size)
        assertFalse(note.contains("more"), note)
        assertEquals("exclusive queue conn-10", entries.last())
    }

    @Test
    fun `eleven and twelve entries stop after ten and say how many more`() {
        val eleven = note(exclusives(11))!!.removePrefix("Left out: ").split("; ")
        assertEquals(11, eleven.size)
        assertEquals("exclusive queue conn-10", eleven[9])
        assertEquals("and 1 more", eleven.last())
        val twelve = note(exclusives(12))!!
        assertTrue(twelve.endsWith("exclusive queue conn-10; and 2 more"), twelve)
        assertFalse(twelve.contains("conn-11") || twelve.contains("conn-12"), twelve)
    }

    @Test
    fun `the cap counts all kinds of entries together, a super stream is one`() {
        val (decls, exchanges, bindings) = superStream("orders", 3) { arrayOf(cluster) }
        val note = note(decls + exclusives(11), exchanges, bindings)!!
        val entries = note.removePrefix("Left out: ").split("; ")
        assertEquals(11, entries.size, note)
        assertEquals("and 2 more", entries.last())
        assertEquals(1, entries.count { it.contains("super stream orders (3 partitions)") }, note)
    }

    // ---- the typed outcome ------------------------------------------------------------------------------------------------------

    @Test
    fun `a topology that can be written is Written with the actions and the note`() {
        val result = written(run(declarations = listOf(QueueDeclaration("orders", "/", "quorum"), stream("audit", age, cluster))))
        assertEquals(listOf("orders"), result.actions.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
        assertEquals("Left out: other settings of stream audit", result.note)
    }

    @Test
    fun `a user queue, exchange, written binding, policy or operator policy that is incomplete is NotWritable`() {
        val queue = QueueDeclaration("orders", "/", "quorum")
        val policy = PolicyRow("p", "/", ".*", "all", 0, emptyMap())
        val exchange = ExchangeRow("events", "/", "topic", true, false, false)
        assertIs<TopologyExportOutcome.NotWritable>(run(declarations = listOf(queue.copy(typedArgumentsComplete = false))), "queue argument")
        assertIs<TopologyExportOutcome.NotWritable>(run(exchanges = listOf(exchange.copy(typedArgumentsComplete = false))), "exchange argument")
        assertIs<TopologyExportOutcome.NotWritable>(run(declarations = listOf(queue), exchanges = listOf(exchange), bindings = listOf(toQueue("events", "orders").copy(typedArgumentsComplete = false))), "written binding")
        assertIs<TopologyExportOutcome.NotWritable>(run(policies = listOf(policy.copy(definitionComplete = false))), "policy definition")
        assertIs<TopologyExportOutcome.NotWritable>(run(operatorPolicies = listOf(policy.copy(definitionComplete = false))), "operator policy definition")
        assertIs<TopologyExportOutcome.Written>(run(declarations = listOf(queue), exchanges = listOf(exchange), bindings = listOf(toQueue("events", "orders")), policies = listOf(policy), operatorPolicies = listOf(policy)))
    }

    @Test
    fun `a binding to an exchange destination that is incomplete is NotWritable, so is one on a built-in exchange to a kept queue`() {
        val a = ExchangeRow("a", "/", "direct", true, false, false)
        val b = ExchangeRow("b", "/", "direct", true, false, false)
        val toExchange = BindingRow("a", "/", "b", BindingDestinationType.EXCHANGE, "k", typedArgumentsComplete = false)
        assertIs<TopologyExportOutcome.NotWritable>(run(exchanges = listOf(a, b), bindings = listOf(toExchange)))
        val builtIn = BindingRow("amq.topic", "/", "orders", BindingDestinationType.QUEUE, "k", typedArgumentsComplete = false)
        assertIs<TopologyExportOutcome.NotWritable>(run(declarations = listOf(QueueDeclaration("orders", "/", "quorum")), bindings = listOf(builtIn)), "the binding is written, so it must be complete")
    }

    // ---- F4: what is not written is not checked --------------------------------------------------------------------------------

    @Test
    fun `an incomplete argument on a left-out queue does not fail, the queue is in the note`() {
        val result = written(run(declarations = listOf(QueueDeclaration("orders", "/", "quorum"), exclusive("session-1").copy(typedArgumentsComplete = false))))
        assertEquals(listOf("orders"), result.actions.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
        assertEquals("Left out: exclusive queue session-1", result.note)
        val transient = QueueDeclaration("scratch", "/", "classic", durable = false, autoDelete = true, typedArgumentsComplete = false)
        assertEquals("Left out: transient queue scratch (cannot be applied)", written(run(declarations = listOf(transient))).note)
        val serverNamed = QueueDeclaration("amq.gen-abc", "/", "classic", durable = false, autoDelete = true, exclusive = true, typedArgumentsComplete = false)
        assertEquals("Left out: exclusive queue amq.gen-abc", written(run(declarations = listOf(serverNamed))).note, "as before M9-14b: an exclusive queue is named, whatever its name")
        val mqtt = QueueDeclaration("mqtt-subscription-c1qos0", "/", "classic", durable = true, typedArgumentsComplete = false)
        assertNull(written(run(declarations = listOf(mqtt))).note, "an MQTT subscription queue is left out silently, its incomplete argument does not matter")
    }

    @Test
    fun `an incomplete exchange that is built in or the nameless default is not written and does not fail`() {
        val builtIn = ExchangeRow("amq.topic", "/", "topic", true, false, false, typedArgumentsComplete = false)
        val default = ExchangeRow("", "/", "direct", true, false, false, typedArgumentsComplete = false)
        val result = written(run(exchanges = listOf(builtIn, default)))
        assertTrue(result.actions.isEmpty(), "${result.actions}")
    }

    @Test
    fun `an incomplete binding of a left-out queue does not fail, binding and queue are both left out and the queue is in the note`() {
        val result = written(
            run(
                declarations = listOf(exclusive("session-1")),
                exchanges = listOf(ExchangeRow("events", "/", "topic", true, false, false)),
                bindings = listOf(toQueue("events", "session-1").copy(typedArgumentsComplete = false)),
            ),
        )
        assertTrue(result.actions.none { it is MigrationAction.DeclareBinding }, "${result.actions}")
        assertEquals("Left out: exclusive queue session-1", result.note)
    }

    @Test
    fun `an incomplete binding that is not written because an end is nameless does not fail`() {
        val fromDefault = BindingRow("", "/", "orders", BindingDestinationType.QUEUE, "orders", typedArgumentsComplete = false)
        assertIs<TopologyExportOutcome.Written>(run(declarations = listOf(QueueDeclaration("orders", "/", "quorum")), bindings = listOf(fromDefault)))
    }
}
