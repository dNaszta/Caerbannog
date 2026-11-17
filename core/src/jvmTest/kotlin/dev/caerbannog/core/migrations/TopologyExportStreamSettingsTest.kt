package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueDeclaration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14c step 2b: what the export WRITES into `DeclareStream.arguments` (the brief's `writtenSettings`, tested through [TopologyExport.actions]):
 * the four settings with their typed values, `x-queue-type` dropped, the leader locator written ALWAYS when the broker holds one (owner's working
 * default Q-14c-1 (a): the file says what the broker had, nothing is invented), a value `StreamSettingRules` refuses NOT written and named
 * "other settings" (the export never writes a file the parser then refuses), an argument that could not be typed named too.
 */
class TopologyExportStreamSettingsTest {

    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val length = "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000)
    private val segment = "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(100_000_000)
    private val leader = "x-queue-leader-locator" to ArgumentValue.Text("balanced")
    private val type = "x-queue-type" to ArgumentValue.Text("stream")
    private val cluster = "x-initial-cluster-size" to ArgumentValue.Whole(3)

    private fun stream(name: String, vararg arguments: Pair<String, ArgumentValue>, complete: Boolean = true) =
        QueueDeclaration(name, "/", "stream", arguments = arguments.toMap(), typedArgumentsComplete = complete)

    private fun export(declarations: List<QueueDeclaration>, exchanges: List<ExchangeRow> = emptyList(), bindings: List<BindingRow> = emptyList()): TopologyExportResult =
        assertIs<TopologyExportOutcome.Written>(TopologyExport.actions("/", declarations, exchanges, bindings, emptyList(), emptyList())).result

    private fun streams(result: TopologyExportResult) = result.actions.filterIsInstance<MigrationAction.DeclareStream>().associateBy { it.name }

    @Test
    fun `the four settings are written with their types and the queue type is dropped`() {
        val result = export(listOf(stream("audit", type, age, length, segment, leader)))
        assertEquals(mapOf(age, length, segment, leader), streams(result).getValue("audit").arguments)
        assertNull(result.note)
    }

    @Test
    fun `each setting alone is written alone`() {
        for (one in listOf(age, length, segment, leader)) {
            val result = export(listOf(stream("audit", type, one)))
            assertEquals(mapOf(one), streams(result).getValue("audit").arguments)
            assertNull(result.note, "${one.first} is carried by the file")
        }
    }

    @Test
    fun `the leader locator is written always, balanced included, as the owner's working default says`() {
        assertEquals(mapOf(leader), streams(export(listOf(stream("audit", type, leader)))).getValue("audit").arguments)
        for (value in listOf("balanced", "client-local", "random", "least-leaders")) {
            val locator = "x-queue-leader-locator" to ArgumentValue.Text(value)
            assertEquals(mapOf(locator), streams(export(listOf(stream("audit", locator)))).getValue("audit").arguments, value)
        }
    }

    @Test
    fun `nothing is invented, a stream whose broker holds no setting is written without arguments as before`() {
        val result = export(listOf(stream("audit"), stream("plain", type)))
        assertEquals(MigrationAction.DeclareStream("audit"), streams(result).getValue("audit"))
        assertEquals(MigrationAction.DeclareStream("plain"), streams(result).getValue("plain"))
        assertNull(result.note)
    }

    @Test
    fun `a stream keeps its vhost and the name`() {
        val written = assertIs<TopologyExportOutcome.Written>(TopologyExport.actions("uat", listOf(stream("audit", age)), emptyList(), emptyList(), emptyList(), emptyList())).result
        assertEquals(MigrationAction.DeclareStream("audit", "uat", mapOf(age)), written.actions.single())
    }

    @Test
    fun `an argument outside the four is not written and is named as other settings, the four are still written`() {
        val result = export(listOf(stream("audit", type, age, leader, cluster)))
        assertEquals(mapOf(age, leader), streams(result).getValue("audit").arguments)
        assertEquals("Left out: other settings of stream audit", result.note)
    }

    @Test
    fun `a value the rules refuse is not written and is named as other settings, the valid siblings are written`() {
        val refused = listOf(
            "x-max-age" to ArgumentValue.Text("7d"), "x-max-age" to ArgumentValue.Text("604800"), "x-max-age" to ArgumentValue.Text(""), "x-max-age" to ArgumentValue.Whole(7),
            "x-max-length-bytes" to ArgumentValue.Whole(0), "x-max-length-bytes" to ArgumentValue.Whole(-1), "x-max-length-bytes" to ArgumentValue.Text("1"),
            "x-max-length-bytes" to ArgumentValue.Decimal(1.5),
            "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(0),
            "x-queue-leader-locator" to ArgumentValue.Text("nearest"), "x-queue-leader-locator" to ArgumentValue.Text("Balanced"),
        )
        for (bad in refused) {
            val keep = listOf(age, length, segment, leader).filter { it.first != bad.first }
            val result = export(listOf(stream("audit", bad, *keep.toTypedArray())))
            assertEquals(keep.toMap(), streams(result).getValue("audit").arguments, "the valid settings next to $bad are written, $bad is not")
            assertEquals("Left out: other settings of stream audit", result.note, "for $bad")
        }
    }

    @Test
    fun `an argument that could not be typed is named as other settings, what could be typed is written`() {
        val result = export(listOf(stream("audit", age, leader, complete = false)))
        assertEquals(mapOf(age, leader), streams(result).getValue("audit").arguments)
        assertEquals("Left out: other settings of stream audit", result.note)
        val bare = export(listOf(stream("audit", complete = false)))
        assertEquals(MigrationAction.DeclareStream("audit"), streams(bare).getValue("audit"))
        assertEquals("Left out: other settings of stream audit", bare.note)
    }

    @Test
    fun `the partitions of a super stream carry balanced and the note says nothing about it`() {
        val partitions = (0 until 3).map { stream("orders-$it", type, leader) }
        val exchange = ExchangeRow("orders", "/", "direct", true, false, false, superStream = true, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true)))
        val bindings = (0 until 3).map { BindingRow("orders", "/", "orders-$it", BindingDestinationType.QUEUE, "$it", typedArguments = mapOf("x-stream-partition-order" to ArgumentValue.Whole(it.toLong()))) }
        val result = export(partitions, listOf(exchange), bindings)
        assertEquals(setOf("orders-0", "orders-1", "orders-2"), streams(result).keys)
        assertTrue(streams(result).values.all { it.arguments == mapOf(leader) }, "${streams(result)}")
        assertNull(result.note)
    }

    @Test
    fun `a partition with another setting turns the one entry of the super stream into other settings`() {
        val partitions = listOf(stream("orders-0", leader), stream("orders-1", leader, cluster))
        val exchange = ExchangeRow("orders", "/", "direct", true, false, false, superStream = true)
        val bindings = listOf(BindingRow("orders", "/", "orders-0", BindingDestinationType.QUEUE, "0"), BindingRow("orders", "/", "orders-1", BindingDestinationType.QUEUE, "1"))
        val result = export(partitions, listOf(exchange), bindings)
        assertEquals("Left out: other settings of super stream orders (2 partitions)", result.note)
        assertEquals(mapOf(leader), streams(result).getValue("orders-1").arguments)
    }

    @Test
    fun `whatever the export writes, the parser accepts, in every combination of broker values`() {
        val values = mapOf(
            "x-max-age" to listOf(ArgumentValue.Text("7D"), ArgumentValue.Text("604800s"), ArgumentValue.Text("7d"), ArgumentValue.Whole(7), ArgumentValue.Text("")),
            "x-max-length-bytes" to listOf(ArgumentValue.Whole(1), ArgumentValue.Whole(0), ArgumentValue.Text("1"), ArgumentValue.Decimal(2.5)),
            "x-stream-max-segment-size-bytes" to listOf(ArgumentValue.Whole(5), ArgumentValue.Whole(-5)),
            "x-queue-leader-locator" to listOf(ArgumentValue.Text("random"), ArgumentValue.Text("nearest"), ArgumentValue.Nothing),
        )
        var n = 0
        for (a in values.getValue("x-max-age")) for (b in values.getValue("x-max-length-bytes")) for (c in values.getValue("x-stream-max-segment-size-bytes"))
            for (d in values.getValue("x-queue-leader-locator")) {
                val result = export(listOf(stream("s$n", type, "x-max-age" to a, "x-max-length-bytes" to b, "x-stream-max-segment-size-bytes" to c, "x-queue-leader-locator" to d, cluster)))
                val text = MigrationParser.exportToJson("0001", "export", result.actions)
                val parsed = MigrationParser.parse(text)
                assertIs<MigrationValidationResult.Valid>(parsed, "the export wrote a file the parser refuses for ($a, $b, $c, $d): $parsed")
                assertEquals(result.actions, parsed.migration.up)
                n++
            }
        assertEquals(5 * 4 * 2 * 3, n)
    }

    @Test
    fun `a stream is written after the queues and exchanges and before the bindings, with its settings`() {
        val queue = QueueDeclaration("orders", "/", "quorum")
        val exchange = ExchangeRow("events", "/", "topic", true, false, false)
        val binding = BindingRow("events", "/", "audit", BindingDestinationType.QUEUE, "k")
        val names = export(listOf(stream("audit", age), queue), listOf(exchange), listOf(binding)).actions.map { it.actionName }
        assertEquals(listOf("declareQueue", "declareExchange", "declareStream", "declareBinding"), names)
    }

    @Test
    fun `a hostile text value is refused, never written, and the file stays parseable`() {
        val hostile = listOf("7D\u0000", "7D\n", "‮7D", "9".repeat(10_000) + "D")
        for (v in hostile) {
            val result = export(listOf(stream("audit", "x-max-age" to ArgumentValue.Text(v), leader)))
            assertEquals(mapOf(leader), streams(result).getValue("audit").arguments)
            assertEquals("Left out: other settings of stream audit", result.note)
        }
    }
}
