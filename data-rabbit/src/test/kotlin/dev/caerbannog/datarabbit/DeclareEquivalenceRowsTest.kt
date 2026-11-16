package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.DeclareEquivalenceTable
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

/**
 * M9-05b: every row of the measured `DeclareEquivalenceTable` (queues of three types, exchanges), replayed on the honest fake. A [Case] is what the
 * live spike sends: a FIRST declare that creates the item, then a SECOND one (the plan). Two claims, both per row:
 *  1. the honest `FakeManagementBroker` answers the SECOND PUT with the row's status (it compares only what the broker compares);
 *  2. the check says "Same" exactly when the broker answers 204 AND the executor's read-back would pass; "Different" otherwise.
 * The rows where the two parts of the apply differ (204 but the read-back stops: [readBackStops]) and the rows where only rule 3 tells the truth
 * ([ruleThreeOnly]: the broker answers 400 and the old matcher says "same") are NAMED below. A row of the table that nobody replays fails the test.
 */
class DeclareEquivalenceRowsTest {

    private class Case(val first: MigrationAction, val second: MigrationAction, val secondBody: String? = null)

    private fun text(v: String) = ArgumentValue.Text(v)
    private fun whole(v: Long) = ArgumentValue.Whole(v)

    private fun queue(type: String, args: Map<String, ArgumentValue> = emptyMap(), durable: Boolean = true, autoDelete: Boolean = false) =
        MigrationAction.DeclareQueue("orders", type, "/", durable, args, autoDelete)

    private fun exchange(type: String = "direct", args: Map<String, ArgumentValue> = emptyMap(), durable: Boolean = true, autoDelete: Boolean = false, internal: Boolean = false) =
        MigrationAction.DeclareExchange("orders", type, "/", durable, autoDelete, internal, args)

    /** The cases, keyed by the table's row id: exactly the declares of `RealBrokerM9SpikeDeclareEquivalenceTest`. */
    private val cases: Map<String, Case> = buildMap {
        listOf("quorum", "classic", "stream").forEach { type ->
            val limit = if (type == "stream") "x-max-length-bytes" else "x-max-length"
            val value = if (type == "stream") 1000L else 10L
            val base = mapOf(limit to whole(value))
            val moreKey = if (type == "stream") "x-max-age" to text("7D") else "x-message-ttl" to whole(5000)
            val refusedAtFirst = when (type) { "quorum" -> "x-max-priority" to whole(5); "classic" -> "x-delivery-limit" to whole(5); else -> "x-message-ttl" to whole(5000) }
            fun row(id: String, first: Map<String, ArgumentValue> = base, second: Map<String, ArgumentValue>, secondType: String = type, durable: Boolean = true, autoDelete: Boolean = false) {
                put("queue.$type.$id", Case(queue(type, first), queue(secondType, second, durable, autoDelete)))
            }
            row("same", second = base)
            row("fewer-arguments", second = emptyMap())
            row("more-arguments", second = base + moreKey)
            row("other-value", second = mapOf(limit to whole(value + 1)))
            row("number-as-text", second = mapOf(limit to text(value.toString())))
            row("number-as-decimal", second = mapOf(limit to ArgumentValue.Decimal(value.toDouble())))
            row("other-type", second = base, secondType = if (type == "quorum") "classic" else "quorum")
            row("durable-false", second = base, durable = false)
            row("auto-delete-true", second = base, autoDelete = true)
            row("unknown-argument-other-value", first = mapOf("x-foo" to text("a")), second = mapOf("x-foo" to text("b")))
            row("unknown-argument-added", first = emptyMap(), second = mapOf("x-foo" to text("a")))
            row("unknown-argument-number-as-text", first = mapOf("x-foo" to whole(1)), second = mapOf("x-foo" to text("1")))
            row("argument-of-another-type-added", first = emptyMap(), second = mapOf(refusedAtFirst))
            // a declare WITHOUT x-queue-type is a classic queue; the PUT body is hand-written, the action (what the plan holds) says classic
            put("queue.$type.type-argument-omitted", Case(queue(type, base), queue("classic", base), """{"durable":true,"auto_delete":false,"arguments":{"$limit":$value}}"""))
        }
        val ae = mapOf("alternate-exchange" to text("ae"))
        put("exchange.same", Case(exchange(args = ae), exchange(args = ae)))
        put("exchange.other-type", Case(exchange(args = ae), exchange("topic", ae)))
        put("exchange.durable-false", Case(exchange(args = ae), exchange(args = ae, durable = false)))
        put("exchange.auto-delete-true", Case(exchange(args = ae), exchange(args = ae, autoDelete = true)))
        put("exchange.internal-true", Case(exchange(args = ae), exchange(args = ae, internal = true)))
        put("exchange.alternate-exchange-fewer", Case(exchange(args = ae), exchange()))
        put("exchange.alternate-exchange-other-value", Case(exchange(args = ae), exchange(args = mapOf("alternate-exchange" to text("other")))))
        put("exchange.alternate-exchange-as-number", Case(exchange(args = ae), exchange(args = mapOf("alternate-exchange" to whole(5)))))
        put("exchange.alternate-exchange-added", Case(exchange(), exchange(args = ae)))
        put("exchange.unknown-argument-added", Case(exchange(args = ae), exchange(args = ae + ("x-foo" to text("b")))))
        put("exchange.unknown-argument-number-as-text", Case(exchange(args = mapOf("x-foo" to whole(10))), exchange(args = mapOf("x-foo" to text("10")))))
        put("exchange.unknown-argument-number-as-decimal", Case(exchange(args = mapOf("x-foo" to whole(10))), exchange(args = mapOf("x-foo" to ArgumentValue.Decimal(10.0)))))
    }

    /** 204 from the broker, then the read-back (`DeclaredResourceMatcher`) stops the apply: a declared argument is not what the broker holds. */
    private val readBackStops: Set<String> =
        listOf("quorum", "classic", "stream").flatMap { t ->
            listOf("unknown-argument-other-value", "unknown-argument-added", "unknown-argument-number-as-text", "argument-of-another-type-added").map { "queue.$t.$it" }
        }.toSet() + setOf("exchange.unknown-argument-added", "exchange.unknown-argument-number-as-text")

    /** The broker answers 400 because an argument its type honours is on the broker and the declare lacks it; the matcher of M9-05 says "same". */
    private val ruleThreeOnly: Set<String> = setOf("queue.quorum.fewer-arguments", "queue.classic.fewer-arguments", "queue.stream.fewer-arguments", "exchange.alternate-exchange-fewer")

    /**
     * The broker answers 400 because a HONOURED argument is on the broker as a whole number and the declare sends the decimal 10.0 (an AMQP type
     * difference); `DeclaredResourceMatcher` compares numbers by value (10 equals 10.0, its documented rule, shared with the read-back) and says
     * "same". The brief of M9-05b says rule 2 is "the same wire type and value (today's matcher)": for this row it is not (reported to the architect).
     * The IGNORED argument of `exchange.unknown-argument-number-as-decimal` is a 204 and the read-back passes, so that row is a plain "Same".
     */
    private val wireTypeOnly: Set<String> = setOf("queue.quorum.number-as-decimal", "queue.classic.number-as-decimal", "queue.stream.number-as-decimal")

    private fun path(action: MigrationAction) = if (action is MigrationAction.DeclareQueue) "/api/queues/%2F/orders" else "/api/exchanges/%2F/orders"

    private fun put(broker: FakeManagementBroker, action: MigrationAction, rawBody: String? = null): FakeManagementBroker.Reply {
        val request = checkNotNull(MigrationRequests.requestFor(action)) { "no request for $action" }
        return broker.handle("PUT", request.path, rawBody ?: request.body.toString())
    }

    private fun seeded(case: Case): FakeManagementBroker = FakeManagementBroker().also { assertEquals(201, put(it, case.first).status, "the first declare must create") }

    private fun check(broker: FakeManagementBroker, action: MigrationAction): BrokerFinding {
        val engine = M508a.engineOver(broker)
        return runBlocking { ManagementDeclareCheck(ResourceReader(M508a.http(engine), M508a.requests())).find(action) }
    }

    @Test
    fun `every row of the table has a case and every case a row`() {
        assertEquals(DeclareEquivalenceTable.rows.map { it.id }.toSet(), cases.keys, "a row nobody replays (or a case without a row) hides a gap")
        val unknown = (readBackStops + ruleThreeOnly + wireTypeOnly) - cases.keys
        assertTrue(unknown.isEmpty(), "named rows without a case: $unknown")
    }

    @Test
    fun `the honest fake answers the second declare of every row with the row's status`() {
        val problems = cases.filterKeys { it.startsWith("queue.") || it.startsWith("exchange.") }.mapNotNull { (id, case) ->
            val answer = put(seeded(case), case.second, case.secondBody)
            val row = DeclareEquivalenceTable.row(id)
            if (answer.status != row.status) "$id: table says ${row.status}, the fake answered ${answer.status}" else null
        }
        assertTrue(problems.isEmpty(), "the fake is not as honest as the broker:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `an ignored argument that a repeated declare carries is not stored, as on the broker`() {
        val broker = FakeManagementBroker()
        put(broker, queue("quorum", mapOf("x-foo" to text("first"))))
        assertEquals(204, put(broker, queue("quorum", mapOf("x-foo" to text("second")))).status)
        assertEquals("\"first\"", broker.queueArguments("/", "orders")!!["x-foo"].toString(), "a 204 changes nothing")
    }

    @Test
    fun `the check says same exactly when the broker answers 204 and the read-back passes, different otherwise, for every row`() {
        val problems = cases.mapNotNull { (id, case) ->
            val row = DeclareEquivalenceTable.row(id)
            val expected = if (row.status == 204 && id !in readBackStops) BrokerFinding.Same else BrokerFinding.Different
            val found = check(seeded(case), case.second)
            if (found != expected) "$id (broker ${row.status}${if (id in readBackStops) ", read-back stops" else ""}${if (id in ruleThreeOnly) ", rule 3 only" else ""}${if (id in wireTypeOnly) ", number kind only" else ""}): expected $expected, the check said $found" else null
        }
        assertTrue(problems.isEmpty(), "the check does not predict the apply:\n${problems.joinToString("\n")}")
    }

    @Test
    fun `the named rows are what they claim to be`() {
        readBackStops.forEach { id ->
            val case = cases.getValue(id)
            assertEquals(204, DeclareEquivalenceTable.status(id), "$id: the broker answers 204")
            val broker = seeded(case)
            put(broker, case.second) // the fake stores nothing for an ignored argument, exactly as the broker
            val body = broker.handle("GET", path(case.second), null).body
            assertTrue(!DeclaredResourceMatcher.matches(case.second, body), "$id: the read-back (the unchanged matcher) must stop the apply")
        }
        ruleThreeOnly.forEach { id ->
            val case = cases.getValue(id)
            assertEquals(400, DeclareEquivalenceTable.status(id), "$id: the broker answers 400")
            val body = seeded(case).handle("GET", path(case.second), null).body
            assertTrue(DeclaredResourceMatcher.matches(case.second, body), "$id: the matcher of M9-05 (unchanged, shared with the read-back) still says same; only rule 3 sees it")
        }
    }

    @Test
    fun `a whole number against a decimal on an honoured argument is the one row where the matcher and the broker differ on a type`() {
        wireTypeOnly.forEach { id ->
            val case = cases.getValue(id)
            assertEquals(400, DeclareEquivalenceTable.status(id), "$id: the broker answers 400")
            val body = seeded(case).handle("GET", path(case.second), null).body
            assertTrue(DeclaredResourceMatcher.matches(case.second, body), "$id: the shared matcher compares numbers by value and stays as it is")
        }
        val ignored = cases.getValue("exchange.unknown-argument-number-as-decimal")
        assertEquals(204, DeclareEquivalenceTable.status("exchange.unknown-argument-number-as-decimal"))
        assertEquals(BrokerFinding.Same, check(seeded(ignored), ignored.second), "an ignored argument: 204, and the read-back passes by value")
    }

    @Test
    fun `the check never writes while predicting any row`() {
        cases.values.forEach { case ->
            val broker = seeded(case)
            val before = broker.snapshot()
            val writesBefore = broker.writes.size
            check(broker, case.second)
            assertEquals(writesBefore, broker.writes.size, "the check sent a write")
            assertEquals(before, broker.snapshot())
        }
    }

    @Test
    fun `the fake answers the vhost listing, as a JSON list, for a queue or exchange named dot or dot dot`() {
        val broker = FakeManagementBroker()
        broker.addQueue("/", "orders"); broker.addExchange("/", "events")
        listOf("%2E", "%2E%2E", ".", "..").forEach { name ->
            val queues = broker.handle("GET", "/api/queues/%2F/$name", null)
            assertEquals(200, queues.status, "queue $name")
            assertTrue(Json.parseToJsonElement(queues.body) is JsonArray, "queue $name answers a list: ${queues.body}")
            val exchanges = broker.handle("GET", "/api/exchanges/%2F/$name", null)
            assertEquals(200, exchanges.status, "exchange $name")
            assertTrue(Json.parseToJsonElement(exchanges.body) is JsonArray, "exchange $name answers a list: ${exchanges.body}")
        }
        assertTrue((Json.parseToJsonElement(broker.handle("GET", "/api/queues/%2F/%2E", null).body) as JsonArray).any { (it as JsonObject)["name"].toString() == "\"orders\"" }, "the list is the vhost's queues")
    }

    @Test
    fun `an ordinary queue is still answered as one object`() {
        val broker = FakeManagementBroker()
        broker.addQueue("/", "orders")
        assertEquals(200, broker.handle("GET", "/api/queues/%2F/orders", null).status)
        assertTrue(Json.parseToJsonElement(broker.handle("GET", "/api/queues/%2F/orders", null).body) is JsonObject)
    }
}
