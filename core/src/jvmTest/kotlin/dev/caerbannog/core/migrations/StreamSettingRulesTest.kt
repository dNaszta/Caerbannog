package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14c: `StreamSettingRules`, the ONE pure object that holds the four stream setting keys, their value rules and their refusal reasons
 * (table "Decision 2" of `docs/tasks/M9-14c-stream-arguments.md`, shapes MEASURED on RabbitMQ 4.4.0, `docs/tasks/M9-14c-spike-results.md`).
 * The parser, the `StreamSettings` builder and the export all call it. Contract fixed here:
 * `StreamSettingRules.refusal(key, value): String?` (null = accepted, else the fixed reason), `refusal(arguments): String?` (the first refusal in
 * the map's order, null = all accepted), `keys: List<String>` (the four, in the order max-age, max-length-bytes, segment size, leader locator),
 * `LEADER_LOCATORS: List<String>`. A refusal is a typed result, never an exception.
 */
class StreamSettingRulesTest {

    private fun text(v: String) = ArgumentValue.Text(v)
    private fun whole(v: Long) = ArgumentValue.Whole(v)

    private val notText = listOf(
        ArgumentValue.Whole(7), ArgumentValue.Decimal(7.0), ArgumentValue.Flag(true), ArgumentValue.Nothing,
        ArgumentValue.Items(listOf(text("7D"))), ArgumentValue.Table(mapOf("a" to text("7D"))),
    )
    private val notNumber = listOf(
        text("1000"), ArgumentValue.Decimal(1.5), ArgumentValue.Decimal(1000.0), ArgumentValue.Flag(true), ArgumentValue.Nothing,
        ArgumentValue.Items(listOf(whole(1))), ArgumentValue.Table(mapOf("a" to whole(1))),
    )

    @Test
    fun `the four keys are exactly these, in this order`() {
        assertEquals(listOf("x-max-age", "x-max-length-bytes", "x-stream-max-segment-size-bytes", "x-queue-leader-locator"), StreamSettingRules.keys)
        assertEquals(listOf("balanced", "client-local", "random", "least-leaders"), StreamSettingRules.LEADER_LOCATORS)
    }

    // ---- x-max-age: text [1-9][0-9]{0,8} then exactly one of Y M D h m s (S-14c-6: the broker checks nothing, so the rule is ours) ----

    @Test
    fun `x-max-age accepts the units of the stream documentation and the broker's own shown form`() {
        for (v in listOf("7D", "1Y", "12M", "30D", "24h", "90m", "1s", "604800s", "31536000s", "999999999D", "9s")) {
            assertNull(StreamSettingRules.refusal("x-max-age", text(v)), "x-max-age \"$v\" must be accepted")
        }
    }

    @Test
    fun `x-max-age refuses what is not digits then one unit, case-sensitive`() {
        val refused = listOf(
            "7d", "7W", "P7D", "604800", "0s", "0D", "", " ", "7 D", " 7D", "7D ", "7D\n", "07D", "-7D", "+7D", "7.5D", "7DD", "7Dh", "D", "7", "s",
            "1000000000D", // ten digits: the hostile-input bound is nine
            "７D", // a full-width digit is not a digit here
            "7٤D", // an Arabic-Indic digit neither
            "7\u0000D", "7D‮", "1".repeat(10_000) + "D",
        )
        for (v in refused) assertNotNull(StreamSettingRules.refusal("x-max-age", text(v)), "x-max-age \"${v.take(20)}\" must be refused")
    }

    @Test
    fun `x-max-age refuses every value that is not text`() {
        for (v in notText) assertEquals(MAX_AGE_REASON, StreamSettingRules.refusal("x-max-age", v), "for $v")
    }

    @Test
    fun `the x-max-age reason is the fixed text, whatever the wrong value was`() {
        for (v in listOf(text("7d"), text(""), text("x".repeat(10_000)), whole(7))) assertEquals(MAX_AGE_REASON, StreamSettingRules.refusal("x-max-age", v))
    }

    // ---- the two byte keys: a whole JSON number from 1 to Long.MAX_VALUE ----

    @Test
    fun `the byte keys accept a whole number from 1 to Long MAX_VALUE`() {
        for (key in listOf("x-max-length-bytes", "x-stream-max-segment-size-bytes")) {
            for (v in listOf(1L, 2L, 1_000_000_000L, 3_000_000_000L, 4_000_000_000L, Long.MAX_VALUE)) {
                // 3,000,000,000 and above: the broker's own bound is an apply-time refusal, not the rule's
                assertNull(StreamSettingRules.refusal(key, whole(v)), "$key $v must be accepted")
            }
        }
    }

    @Test
    fun `the byte keys refuse 0, a negative number, a text, a decimal and every other type`() {
        for (key in listOf("x-max-length-bytes", "x-stream-max-segment-size-bytes")) {
            val reason = "stream setting \"$key\" must be a whole number above 0"
            for (v in notNumber + listOf(whole(0), whole(-1), whole(Long.MIN_VALUE))) assertEquals(reason, StreamSettingRules.refusal(key, v), "$key for $v")
        }
    }

    // ---- the leader locator: exactly one of four, case-sensitive (S-14c-2) ----

    @Test
    fun `the leader locator accepts exactly the four measured values`() {
        for (v in listOf("balanced", "client-local", "random", "least-leaders")) assertNull(StreamSettingRules.refusal("x-queue-leader-locator", text(v)), v)
    }

    @Test
    fun `the leader locator refuses what the broker refused and everything else`() {
        for (v in listOf("nearest", "Balanced", "BALANCED", "client_local", " balanced", "balanced ", "", "7", "balanced\n", "leastleaders", "x".repeat(10_000))) {
            assertEquals(LOCATOR_REASON, StreamSettingRules.refusal("x-queue-leader-locator", text(v)), "for \"${v.take(20)}\"")
        }
        for (v in notText) assertEquals(LOCATOR_REASON, StreamSettingRules.refusal("x-queue-leader-locator", v), "for $v")
    }

    // ---- any other key ----

    @Test
    fun `any other key is refused as not supported, the protocol names included`() {
        for (key in listOf("x-queue-type", "x-dead-letter-exchange", "x-initial-cluster-size", "x-foo", "x-stream-filter-size-bytes", "max-age", "queue-leader-locator", "X-MAX-AGE", "x-max-age ", "")) {
            val reason = StreamSettingRules.refusal(key, text("x"))
            assertNotNull(reason, "key \"$key\" must be refused")
            assertTrue(reason.endsWith("is not supported"), reason)
        }
    }

    @Test
    fun `the not supported reason is the architect's wording`() {
        assertEquals("stream setting \"x-queue-type\" is not supported", StreamSettingRules.refusal("x-queue-type", text("stream")))
        assertEquals("stream setting \"x-dead-letter-exchange\" is not supported", StreamSettingRules.refusal("x-dead-letter-exchange", text("x")))
        assertEquals("stream setting \"x-initial-cluster-size\" is not supported", StreamSettingRules.refusal("x-initial-cluster-size", whole(3)))
    }

    @Test
    fun `a hostile key never reaches the reason raw, long or with control characters`() {
        val hostile = listOf("x".repeat(10_000), "x-a\nb", "x-a\u0000b", "x\"; drop", "x-‮dcba", "\u001B[31mred", "x-a\r\nInjected: header")
        for (key in hostile) {
            val reason = StreamSettingRules.refusal(key, text("x"))
            assertNotNull(reason)
            assertTrue(reason.length <= 200, "the reason is ${reason.length} characters")
            assertTrue(reason.none { it.isISOControl() || it == '‮' }, "control or bidi character in: ${reason.take(80)}")
            assertTrue(reason.startsWith("stream setting") && reason.endsWith("is not supported"), reason.take(80))
        }
    }

    // ---- a whole map ----

    @Test
    fun `a map is accepted when every setting is, the empty map too`() {
        assertNull(StreamSettingRules.refusal(emptyMap()))
        assertNull(
            StreamSettingRules.refusal(
                mapOf("x-max-age" to text("7D"), "x-max-length-bytes" to whole(1), "x-stream-max-segment-size-bytes" to whole(2), "x-queue-leader-locator" to text("random")),
            ),
        )
    }

    @Test
    fun `a map is refused with the reason of its first refused setting, in the map's order`() {
        val a = linkedMapOf("x-max-age" to text("7d"), "x-queue-leader-locator" to text("nearest"))
        assertEquals(MAX_AGE_REASON, StreamSettingRules.refusal(a))
        val b = linkedMapOf("x-queue-leader-locator" to text("nearest"), "x-max-age" to text("7d"))
        assertEquals(LOCATOR_REASON, StreamSettingRules.refusal(b))
        assertFalse(StreamSettingRules.refusal(mapOf("x-max-age" to text("7D"), "x-foo" to whole(1))) == null)
    }

    private companion object {
        const val MAX_AGE_REASON = "stream setting \"x-max-age\" must be text such as 7D"
        const val LOCATOR_REASON = "stream setting \"x-queue-leader-locator\" must be balanced, client-local, random or least-leaders"
    }
}
