package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-05b step 4 (test developer): edges of the two check-only rules. Number kind is the JSON literal (a `.`, `e` or `E` makes it decimal);
 * hostile bodies never throw, never read "Already there" and never write.
 */
class HonouredArgumentRuleEdgeTest {

    private fun queueBody(arguments: String) =
        """{"name":"orders","vhost":"/","type":"quorum","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum",$arguments}}"""

    private fun kind(declared: ArgumentValue, onBroker: String): String? =
        HonouredArgumentRule.otherNumberKind(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to declared)), queueBody("\"x-delivery-limit\":$onBroker"))

    @Test
    fun `a literal with an exponent is a decimal even when it is a whole value, so 1E3 against 1000 is another kind`() {
        assertEquals("x-delivery-limit", kind(ArgumentValue.Whole(1000), "1E3"))
        assertEquals("x-delivery-limit", kind(ArgumentValue.Whole(1000), "1e3"))
        assertEquals("x-delivery-limit", kind(ArgumentValue.Decimal(1000.0), "1000"))
        assertNull(kind(ArgumentValue.Decimal(1000.0), "1.0E3"), "both decimal literals")
        assertNull(kind(ArgumentValue.Decimal(1.0E7), "10000000.0"), "both decimal literals, whatever the spelling")
    }

    @Test
    fun `zero, negative zero and negative numbers keep their kind`() {
        assertNull(kind(ArgumentValue.Whole(0), "-0"), "-0 is a whole literal")
        assertNull(kind(ArgumentValue.Whole(-3), "-3"))
        assertEquals("x-delivery-limit", kind(ArgumentValue.Whole(0), "-0.0"))
        assertNull(kind(ArgumentValue.Decimal(-0.0), "-0.0"))
        assertNull(kind(ArgumentValue.Decimal(0.0), "0.0"))
    }

    @Test
    fun `a whole number beyond a long on the broker is a whole number, never a crash`() {
        assertNull(kind(ArgumentValue.Whole(Long.MAX_VALUE), "9223372036854775807"))
        assertNull(kind(ArgumentValue.Whole(Long.MAX_VALUE), "99999999999999999999999999"), "both whole literals: the value difference is the matcher's")
        assertEquals("x-delivery-limit", kind(ArgumentValue.Whole(Long.MAX_VALUE), "9223372036854775807.0"))
    }

    @Test
    fun `text, boolean, null and a nested value on either side are never judged for number kind`() {
        val broker = listOf("\"10\"", "\"10.0\"", "true", "null", "[10]", "{\"a\":10.0}", "\"\"")
        for (b in broker) {
            assertNull(kind(ArgumentValue.Whole(10), b), "declared 10 against $b")
            assertNull(kind(ArgumentValue.Decimal(10.0), b), "declared 10.0 against $b")
        }
        for (declared in listOf(ArgumentValue.Text("10"), ArgumentValue.Text("10.0"), ArgumentValue.Flag(true), ArgumentValue.Nothing,
            ArgumentValue.Items(listOf(ArgumentValue.Whole(10))), ArgumentValue.Table(mapOf("a" to ArgumentValue.Decimal(1.0))))) {
            assertNull(kind(declared, "10"), "declared $declared against 10")
            assertNull(kind(declared, "10.0"), "declared $declared against 10.0")
        }
    }

    @Test
    fun `a bare word that the tree reader accepts as a number is not a number`() {
        // kotlinx reads NaN, Infinity and abc as non-string primitives
        for (word in listOf("NaN", "Infinity", "-Infinity", "abc")) {
            val body = queueBody("\"x-delivery-limit\":$word")
            val found = runCatching { HonouredArgumentRule.otherNumberKind(MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(1))), body) }
            assertTrue(found.isSuccess, "$word must not throw")
        }
    }

    // ---- hostile bodies through the check ----

    private val declares = listOf<MigrationAction>(
        MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareExchange("events"), MigrationAction.DeclareStream("telemetry"),
        MigrationAction.DeclarePolicy("limits", "^orders"), MigrationAction.DeclareOperatorPolicy("caps", "^orders"),
    )

    private fun find(action: MigrationAction, body: String, status: Int = 200): BrokerFinding =
        runBlocking { ManagementDeclareCheck(ResourceReader(M508a.http(M508a.answering(status, body)), M508a.requests())).find(action) }

    @Test
    fun `a list nested exactly at the probe limit and one level past is never read as the item, and nothing throws`() {
        for (depth in listOf(1, 63, 64, 65, 66, 200, 5000)) {
            val body = "[".repeat(depth) + "]".repeat(depth)
            for (action in declares) {
                val found = find(action, body)
                assertNotEquals(BrokerFinding.Same, found, "${action.description} depth $depth")
                assertNotEquals(BrokerFinding.Absent, found, "${action.description} depth $depth")
            }
        }
    }

    @Test
    fun `an item with no arguments, null arguments or arguments that are not an object is judged by type and flags only, as the matcher does`() {
        val bare = MigrationAction.DeclareQueue("orders")
        for (args in listOf("null", "[]", "\"x\"")) {
            val found = find(bare, """{"type":"quorum","durable":true,"auto_delete":false,"arguments":$args}""")
            assertEquals(BrokerFinding.Same, found, "arguments $args")
        }
        val declared = MigrationAction.DeclareQueue("orders", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
        for (args in listOf("null", "[]", "\"x\"")) {
            assertEquals(BrokerFinding.Different, find(declared, """{"type":"quorum","durable":true,"auto_delete":false,"arguments":$args}"""), "arguments $args")
        }
    }

    // A list nested deeper than the probe's 64 levels reads "Exists with different settings", not "Could not be checked" (step 4 finding, minor):
    // a real broker never sends one, and the result is still never Same or Absent, which the next test pins.
    @Test
    fun `hostile object bodies are never the same and never throw`() {
        val bodies = listOf(
            """{"type":"quorum","durable":true,"auto_delete":false,"arguments":{"x-delivery-limit":3}}""".replace("quorum", "classic"),
            "{" + "\"a\":{".repeat(5000),
            "\u0000\u0001ÿ binary",
            "x".repeat(3_000_000),
            """{"arguments":{"x-delivery-limit":${"9".repeat(5000)}}}""",
        )
        for (body in bodies) for (action in declares.take(2)) {
            val found = find(action, body)
            assertNotEquals(BrokerFinding.Same, found, "${action.description} with body of ${body.length} chars")
        }
    }

    @Test
    fun `a classic default argument set that the broker may add is not an extra when it is not honoured`() {
        // x-queue-version and x-max-priority are honoured by classic only; a quorum item carrying them is read, not refused.
        val action = MigrationAction.DeclareQueue("orders", "quorum")
        assertNull(HonouredArgumentRule.extraOnBroker(action, queueBody("\"x-queue-version\":2")))
    }

    @Test
    fun `400 steps are 400 GETs, one each, with an honoured extra on every tenth and nothing written`() {
        val broker = dev.caerbannog.core.migrations.fakes.FakeManagementBroker()
        val seen = mutableListOf<M508a.Seen>()
        for (i in 1..400) broker.addQueue("/", "q$i", arguments = if (i % 10 == 0) mapOf("x-max-length" to kotlinx.serialization.json.JsonPrimitive(5)) else emptyMap())
        val check = ManagementDeclareCheck(ResourceReader(M508a.http(M508a.engineOver(broker, seen)), M508a.requests()))
        val found = runBlocking { (1..400).map { check.find(MigrationAction.DeclareQueue("q$it")) } }
        assertEquals(400, seen.size)
        assertTrue(seen.all { it.method == "GET" }, "only reads")
        assertEquals(40, found.count { it == BrokerFinding.Different })
        assertEquals(360, found.count { it == BrokerFinding.Same })
        assertTrue(broker.writes.isEmpty())
    }

    @Test
    fun `a connection that closes mid-check leaves the later steps could not be checked, with the fixed text`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            if (calls.incrementAndGet() > 2) throw java.io.IOException("connection closed SENTINEL-secret-9f")
            respond("""{"type":"quorum","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum"}}""", io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf("Content-Type", "application/json"))
        }
        val check = ManagementDeclareCheck(ResourceReader(M508a.http(engine), M508a.requests()))
        val found = runBlocking { (1..5).map { check.find(MigrationAction.DeclareQueue("q$it")) } }
        assertEquals(listOf(BrokerFinding.Same, BrokerFinding.Same), found.take(2))
        found.drop(2).forEach { assertEquals(BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER), it); assertTrue(!it.toString().contains("SENTINEL")) }
    }
}
