package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.fakes.PolicyKeyMatrix
import dev.caerbannog.core.migrations.fakes.PolicyKeyMatrix.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first. API fixed here (package `dev.caerbannog.core.planner`, commonMain, all pure; the architect confirms it in the report):
 *   enum class PolicyTarget(val wire: String, val label: String) { QUORUM_QUEUES, CLASSIC_QUEUES, QUEUES, STREAMS, EXCHANGES, ALL; companion fun fromWire(wire: String): PolicyTarget? }
 *   sealed interface PolicyValueKind { data class WholeNumber(val min: Long); data object Duration; data object Text; data class Choice(val values: List<ChoiceValue>) }
 *   data class ChoiceValue(val wire: String, val label: String)
 *   data class PolicyKeyEntry(val key: String, val label: String, val kind: PolicyValueKind, val targets: Set<PolicyTarget>, val evidence: List<String>)
 *   object PolicyKeyCatalog { val entries: List<PolicyKeyEntry>; fun offeredFor(target: PolicyTarget): List<PolicyKeyEntry>; fun entry(key: String): PolicyKeyEntry? }
 *   object MaxAgeUnits { val measured: Set<Char> }
 * `evidence` is a LIST of references the test resolves (one string cannot carry alternate-exchange: the matrix for the queue targets, a live row for exchanges):
 *   "effect:<PolicyKeyMatrix.Target name>" (the key is in `PolicyKeyMatrix.effect` of that target), "wrong-type:<row id>" (a `PolicyKeyMatrix.wrongTypeRows` id of this key),
 *   "live:<id>" with <id> one of [LIVE_ROWS] (rows of `RealBrokerM9PolicyStepTest`). A key without evidence is not in the catalog.
 * The catalog order is the order of the label table of docs/tasks/M9-08-policy-step.md; `offeredFor` keeps it.
 */
class PolicyKeyCatalogTest {

    private val liveRows = LIVE_ROWS

    private val table = listOf(
        // key, label, targets that the table lists
        Triple("max-length", "Maximum number of messages", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("max-length-bytes", "Keep at most (bytes)", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES, PolicyTarget.STREAMS, PolicyTarget.QUEUES, PolicyTarget.ALL)),
        Triple("overflow", "When the queue is full", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("message-ttl", "Message time to live (ms)", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("expires", "Delete the queue when unused for (ms)", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("dead-letter-exchange", "Dead-letter exchange", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("dead-letter-routing-key", "Dead-letter routing key", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES)),
        Triple("dead-letter-strategy", "Dead-letter strategy", setOf(PolicyTarget.QUORUM_QUEUES)),
        Triple("delivery-limit", "Delivery limit", setOf(PolicyTarget.QUORUM_QUEUES)),
        Triple("queue-leader-locator", "Leader locator", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES, PolicyTarget.STREAMS, PolicyTarget.QUEUES, PolicyTarget.ALL)),
        Triple("target-group-size", "Target number of members", setOf(PolicyTarget.QUORUM_QUEUES)),
        Triple("max-age", "Keep messages for", setOf(PolicyTarget.STREAMS)),
        Triple("stream-max-segment-size-bytes", "Segment size (bytes)", setOf(PolicyTarget.STREAMS)),
        Triple("alternate-exchange", "Alternate exchange", setOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES, PolicyTarget.STREAMS, PolicyTarget.QUEUES, PolicyTarget.ALL, PolicyTarget.EXCHANGES)),
    )

    @Test
    fun `the six targets have their wire text and label`() {
        assertEquals(
            listOf("quorum_queues" to "Quorum queues", "classic_queues" to "Classic queues", "queues" to "Queues", "streams" to "Streams", "exchanges" to "Exchanges", "all" to "All"),
            PolicyTarget.entries.map { it.wire to it.label },
        )
        assertEquals(PolicyTarget.STREAMS, PolicyTarget.fromWire("streams"))
        assertNull(PolicyTarget.fromWire("Streams"), "the wire text is exact")
        assertNull(PolicyTarget.fromWire("super_streams"))
        assertNull(PolicyTarget.fromWire(""))
    }

    @Test
    fun `the catalog holds exactly the keys of the label table, in its order, with its labels and targets`() {
        assertEquals(table.map { it.first }, PolicyKeyCatalog.entries.map { it.key })
        table.forEach { (key, label, targets) ->
            val entry = assertNotNull(PolicyKeyCatalog.entry(key), key)
            assertEquals(label, entry.label, "label of $key")
            assertEquals(targets, entry.targets, "targets of $key")
        }
    }

    @Test
    fun `an unknown key is no entry`() {
        assertNull(PolicyKeyCatalog.entry("x-made-up"))
        assertNull(PolicyKeyCatalog.entry("ha-mode"), "a key of the classic mirroring policy is not offered")
        assertNull(PolicyKeyCatalog.entry(""))
    }

    @Test
    fun `value kinds follow the table`() {
        fun kind(key: String) = PolicyKeyCatalog.entry(key)!!.kind
        assertEquals(PolicyValueKind.WholeNumber(0), kind("max-length"))
        assertEquals(PolicyValueKind.WholeNumber(0), kind("max-length-bytes"))
        assertEquals(PolicyValueKind.WholeNumber(0), kind("message-ttl"))
        assertEquals(PolicyValueKind.WholeNumber(1), kind("expires"), "expires 0 is a 400 (S-F1c)")
        assertEquals(PolicyValueKind.WholeNumber(0), kind("delivery-limit"))
        assertEquals(PolicyValueKind.WholeNumber(1), kind("target-group-size"), "target-group-size 0 is a 400 (S-F1c)")
        assertEquals(PolicyValueKind.WholeNumber(1), kind("stream-max-segment-size-bytes"))
        assertEquals(PolicyValueKind.Duration, kind("max-age"))
        assertEquals(PolicyValueKind.Text, kind("dead-letter-exchange"))
        assertEquals(PolicyValueKind.Text, kind("dead-letter-routing-key"))
        assertEquals(PolicyValueKind.Text, kind("alternate-exchange"))
    }

    @Test
    fun `choices are only the measured values, with the table's words`() {
        fun choices(key: String) = (PolicyKeyCatalog.entry(key)!!.kind as PolicyValueKind.Choice).values.map { it.wire to it.label }
        assertEquals(
            listOf("drop-head" to "drop oldest", "reject-publish" to "refuse new", "reject-publish-dlx" to "refuse new and dead-letter"),
            choices("overflow"),
        )
        assertEquals(PolicyKeyMatrix.overflowValues.toSet(), choices("overflow").map { it.first }.toSet(), "the three overflow values of the matrix, no other")
        assertEquals(listOf("at-least-once" to "at least once"), choices("dead-letter-strategy"))
        assertEquals(listOf("balanced" to "balanced"), choices("queue-leader-locator"), "an unmeasured locator is not offered (S-14c-2 measured balanced only for a policy)")
    }

    @Test
    fun `every entry has evidence and every reference resolves`() {
        PolicyKeyCatalog.entries.forEach { entry ->
            assertTrue(entry.evidence.isNotEmpty(), "${entry.key} has no evidence, so it must not be in the catalog")
            entry.evidence.forEach { ref ->
                val (kind, id) = ref.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                when (kind) {
                    "effect" -> assertTrue(PolicyKeyMatrix.takesEffect(Target.valueOf(id), entry.key), "$ref: ${entry.key} is not in PolicyKeyMatrix.effect of $id")
                    "wrong-type" -> assertEquals(entry.key, PolicyKeyMatrix.wrongTypeRows.singleOrNull { it.id == id }?.key, "$ref is no wrongTypeRows id of ${entry.key}")
                    "live" -> assertTrue(id in liveRows, "$ref is no live row of RealBrokerM9PolicyStepTest: $liveRows")
                    else -> throw AssertionError("evidence $ref of ${entry.key} has an unknown form")
                }
            }
        }
    }

    @Test
    fun `every target of an entry is covered by its evidence`() {
        PolicyKeyCatalog.entries.forEach { entry ->
            entry.targets.forEach { target ->
                val covered = when (target) {
                    PolicyTarget.QUORUM_QUEUES -> "effect:QUORUM" in entry.evidence
                    PolicyTarget.CLASSIC_QUEUES -> "effect:CLASSIC" in entry.evidence
                    PolicyTarget.STREAMS -> "effect:STREAM" in entry.evidence
                    PolicyTarget.EXCHANGES -> "live:$ALTERNATE_EXCHANGE_ROW" in entry.evidence
                    // the intersection of the three: the evidence of all three queue kinds
                    PolicyTarget.QUEUES, PolicyTarget.ALL -> listOf("effect:QUORUM", "effect:CLASSIC", "effect:STREAM").all { it in entry.evidence }
                }
                assertTrue(covered, "${entry.key} is offered for $target without evidence: ${entry.evidence}")
            }
        }
    }

    @Test
    fun `quorum, classic and stream policies offer exactly what the measured matrix says takes effect, both ways`() {
        mapOf(PolicyTarget.QUORUM_QUEUES to Target.QUORUM, PolicyTarget.CLASSIC_QUEUES to Target.CLASSIC, PolicyTarget.STREAMS to Target.STREAM).forEach { (target, matrix) ->
            val offered = PolicyKeyCatalog.offeredFor(target).map { it.key }.toSet()
            val effect = PolicyKeyMatrix.effect.getValue(matrix)
            val offeredButIgnored = offered - effect
            assertEquals(emptySet(), offeredButIgnored, "$target offers keys the broker ignores for it (a whole policy would then bind to nothing)")
            val leftOut = effect - offered
            assertEquals(emptySet(), leftOut, "$target leaves out keys the matrix says take effect, with no named reason")
        }
    }

    @Test
    fun `queues and all offer the intersection of the three kinds, in the label table order`() {
        val expected = listOf("max-length-bytes", "queue-leader-locator", "alternate-exchange")
        assertEquals(expected, PolicyKeyCatalog.offeredFor(PolicyTarget.QUEUES).map { it.key })
        assertEquals(expected, PolicyKeyCatalog.offeredFor(PolicyTarget.ALL).map { it.key })
        val intersection = listOf(Target.QUORUM, Target.CLASSIC, Target.STREAM).map { PolicyKeyMatrix.effect.getValue(it) }.reduce { a, b -> a intersect b }
        assertEquals(intersection, expected.toSet(), "the conservative reading IS the intersection of the matrix")
    }

    @Test
    fun `exchanges offer the alternate exchange only`() {
        assertEquals(listOf("alternate-exchange"), PolicyKeyCatalog.offeredFor(PolicyTarget.EXCHANGES).map { it.key })
    }

    @Test
    fun `a stream policy offers no dead-letter setting (S-D2) and no queue limit`() {
        val keys = PolicyKeyCatalog.offeredFor(PolicyTarget.STREAMS).map { it.key }
        assertEquals(listOf("max-length-bytes", "queue-leader-locator", "max-age", "stream-max-segment-size-bytes", "alternate-exchange"), keys)
        assertTrue(listOf("dead-letter-exchange", "dead-letter-routing-key", "dead-letter-strategy", "max-length", "delivery-limit", "overflow").none { it in keys })
    }

    @Test
    fun `labels are unique, so the form's dropdown and the scenarios cannot confuse two keys`() {
        assertEquals(PolicyKeyCatalog.entries.size, PolicyKeyCatalog.entries.map { it.label }.toSet().size)
        assertEquals(PolicyKeyCatalog.entries.size, PolicyKeyCatalog.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `a minimum of 1 is exactly where the broker answered 400 for the number 0 (S-F1c)`() {
        val zeroRefused = PolicyKeyMatrix.wrongTypeRows.filter { it.valueJson == "0" && it.status == 400 }.map { it.key }.toSet()
        zeroRefused.forEach { key ->
            val kind = PolicyKeyCatalog.entry(key)?.kind as? PolicyValueKind.WholeNumber ?: return@forEach
            assertTrue(kind.min >= 1, "$key: the broker refuses 0, the catalog says min ${kind.min}")
        }
        assertTrue("expires" in zeroRefused && "target-group-size" in zeroRefused, "the two rows this test stands on")
    }

    @Test
    fun `max-age units are the measured ones and nothing else`() {
        // measured: 1Y, 1000s, 7D answered 201 (S-F1c); 1M, 1h, 1m answered 201 in RealBrokerM9PolicyStepTest (M9-08); 7d, 7, abc answered 400
        assertEquals(setOf('Y', 'M', 'D', 'h', 'm', 's'), MaxAgeUnits.measured)
        assertTrue('d' !in MaxAgeUnits.measured && 'H' !in MaxAgeUnits.measured && 'S' !in MaxAgeUnits.measured && 'y' !in MaxAgeUnits.measured)
    }

    companion object {
        const val ALTERNATE_EXCHANGE_ROW = "M9-08-alternate-exchange-on-exchange"
        val LIVE_ROWS = setOf(ALTERNATE_EXCHANGE_ROW, "M9-08-max-age-units")
    }
}
