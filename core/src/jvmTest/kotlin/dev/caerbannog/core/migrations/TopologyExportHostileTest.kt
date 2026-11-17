package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueDeclaration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14b step 4 (round 1) exploratory checks on the pure export: hostile names, shared partitions, odd bindings, the size of the note.
 * M9-14c step 2b: the leader locator and the retention are written now, so they can no longer be "what the note names"; the examples use
 * `x-initial-cluster-size` (a setting the file cannot carry), and the note says "other settings of ..." only. Hostile names still reach the note
 * as they are, one line of plain text.
 */
class TopologyExportHostileTest {

    private val leader = "x-queue-leader-locator" to ArgumentValue.Text("balanced")
    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val cluster = "x-initial-cluster-size" to ArgumentValue.Whole(3)

    private fun stream(name: String, vararg arguments: Pair<String, ArgumentValue>) = QueueDeclaration(name, "/", "stream", arguments = arguments.toMap())
    private fun superExchange(name: String) = ExchangeRow(name, "/", "direct", true, false, false, superStream = true)
    private fun toQueue(source: String, destination: String, key: String = "k") = BindingRow(source, "/", destination, BindingDestinationType.QUEUE, key)

    private fun written(
        declarations: List<QueueDeclaration> = emptyList(),
        exchanges: List<ExchangeRow> = emptyList(),
        bindings: List<BindingRow> = emptyList(),
    ): TopologyExportResult =
        assertIs<TopologyExportOutcome.Written>(TopologyExport.actions("/", declarations, exchanges, bindings, emptyList(), emptyList())).result

    @Test
    fun `two super streams that share partitions, the first by name owns them and the other has no entry`() {
        val decls = (0..2).map { stream("p$it", cluster) }
        val bindings = (0..2).flatMap { listOf(toQueue("b", "p$it"), toQueue("a", "p$it")) }
        assertEquals("Left out: other settings of super stream a (3 partitions)", written(decls, listOf(superExchange("b"), superExchange("a")), bindings).note)
    }

    @Test
    fun `two super streams with their own partitions are two entries, sorted by exchange name`() {
        val decls = listOf(stream("a-0", cluster), stream("a-1", cluster), stream("b-0", cluster))
        val bindings = listOf(toQueue("a", "a-0"), toQueue("a", "a-1"), toQueue("b", "b-0"))
        assertEquals(
            "Left out: other settings of super stream a (2 partitions); other settings of super stream b (1 partition)",
            written(decls, listOf(superExchange("b"), superExchange("a")), bindings).note,
        )
    }

    @Test
    fun `a partition bound twice with different routing keys counts once`() {
        val decls = listOf(stream("o-0", cluster))
        assertEquals("Left out: other settings of super stream o (1 partition)", written(decls, listOf(superExchange("o")), listOf(toQueue("o", "o-0", "0"), toQueue("o", "o-0", "x"))).note)
    }

    @Test
    fun `a super stream binding that points at a missing stream or at an exchange is not a partition`() {
        val bindings = listOf(
            toQueue("o", "o-0"), toQueue("o", "ghost"),
            BindingRow("o", "/", "o-0", BindingDestinationType.EXCHANGE, "k"),
        )
        val result = written(listOf(stream("o-0", cluster)), listOf(superExchange("o")), bindings)
        assertEquals("Left out: other settings of super stream o (1 partition)", result.note)
        assertEquals(listOf("o-0"), result.actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name })
    }

    @Test
    fun `a super stream exchange without any bound stream gives no entry and its streams stay plain`() {
        assertNull(written(emptyList(), listOf(superExchange("o"))).note)
        assertEquals("Left out: other settings of stream s", written(listOf(stream("s", cluster)), listOf(superExchange("o"))).note)
    }

    @Test
    fun `a partition bound to a super stream exchange that is not flagged as one stays a plain stream`() {
        val plainExchange = ExchangeRow("o", "/", "direct", true, false, false, superStream = false)
        assertEquals("Left out: other settings of stream o-0", written(listOf(stream("o-0", cluster)), listOf(plainExchange), listOf(toQueue("o", "o-0"))).note)
    }

    @Test
    fun `an exclusive amq-gen queue is named, a non-exclusive one is silent, whatever the incomplete flag says`() {
        val named = QueueDeclaration("amq.gen-AAA", "/", "classic", durable = false, autoDelete = true, exclusive = true, typedArgumentsComplete = false)
        val silent = QueueDeclaration("amq.gen-BBB", "/", "classic", durable = false, autoDelete = true, exclusive = false, typedArgumentsComplete = false)
        assertEquals("Left out: exclusive queue amq.gen-AAA", written(listOf(named, silent)).note)
    }

    @Test
    fun `a stream bound to the default exchange or from a built-in exchange, with an incomplete binding, still exports (a nameless end is not written)`() {
        val s = stream("s")
        val fromBuiltIn = toQueue("amq.topic", "s").copy(typedArgumentsComplete = false)
        // this binding IS written (a binding on a built-in exchange is kept), so it must refuse
        assertIs<TopologyExportOutcome.NotWritable>(TopologyExport.actions("/", listOf(s), emptyList(), listOf(fromBuiltIn), emptyList(), emptyList()))
        val toNameless = BindingRow("x", "/", "", BindingDestinationType.EXCHANGE, "k", typedArgumentsComplete = false)
        assertIs<TopologyExportOutcome.Written>(TopologyExport.actions("/", listOf(s), emptyList(), listOf(toNameless), emptyList(), emptyList()))
    }

    @Test
    fun `an incomplete binding to a stream is written, so it refuses, but one to a left-out queue does not`() {
        val s = stream("s")
        val conn = QueueDeclaration("c", "/", "classic", durable = false, exclusive = true)
        val toStream = toQueue("e", "s").copy(typedArgumentsComplete = false)
        val toConn = toQueue("e", "c").copy(typedArgumentsComplete = false)
        assertIs<TopologyExportOutcome.NotWritable>(TopologyExport.actions("/", listOf(s, conn), emptyList(), listOf(toStream, toConn), emptyList(), emptyList()))
        assertIs<TopologyExportOutcome.Written>(TopologyExport.actions("/", listOf(s, conn), emptyList(), listOf(toConn), emptyList(), emptyList()))
    }

    // ---- hostile text and the size of the note --------------------------------------------------------------------------------

    private val hostile = "<b>x</b>&amp;‮\u0000\n\t\"'\\é日本語🐇"

    @Test
    fun `hostile names appear in the note as they are, one line of plain text, nothing is escaped or interpreted`() {
        val name = hostile + "n".repeat(100)
        val note = written(listOf(stream(name, cluster))).note!!
        assertEquals("Left out: other settings of stream $name", note)
    }

    @Test
    fun `MEASURE the longest note a hostile topology can give, ten entries of 255 character names plus the closing entry, three words become one`() {
        val long = "n".repeat(255)
        val ten = (1..12).map { stream("%02d".format(it) + long.drop(2), age, leader, "x-other" to ArgumentValue.Flag(true)) }
        val note = written(ten).note!!
        assertEquals(11, note.removePrefix("Left out: ").split("; ").size)
        assertTrue(note.endsWith("and 2 more"))
        println("M9-14b longest stream note, 10 streams of 255 characters with three words: ${note.length} characters")
        val supers = (1..12).map { "%02d".format(it) + long.drop(2) }
        val decls = supers.map { stream("$it-0", cluster) }
        val exchanges = supers.map { superExchange(it) }
        val bindings = supers.map { toQueue(it, "$it-0") }
        val note2 = written(decls, exchanges, bindings).note!!
        println("M9-14b longest super stream note: ${note2.length} characters")
        assertTrue(note2.length < 4000 && note.length < 4000, "unexpectedly long: ${note.length} / ${note2.length}")
    }

    @Test
    fun `5000 streams and 5000 exclusive queues give a note of eleven entries, and the work stays linear`() {
        val decls = (1..5000).map { stream("s-$it", cluster) } + (1..5000).map { QueueDeclaration("e-$it", "/", "classic", durable = false, exclusive = true) }
        val note = written(decls).note!!
        assertEquals(11, note.removePrefix("Left out: ").split("; ").size)
        assertTrue(note.endsWith("and 9990 more"), note.takeLast(40))
    }

    @Test
    fun `a name with a semicolon cannot fake an entry boundary the cap counts`() {
        val note = written(listOf(stream("a; other settings of stream b", cluster))).note!!
        assertEquals("Left out: other settings of stream a; other settings of stream b", note)
        assertFalse(note.contains("more"))
    }
}
