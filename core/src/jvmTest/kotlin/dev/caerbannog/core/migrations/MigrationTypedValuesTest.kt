package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.only
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-04: typed argument and definition values through the whole parser, and the fixed, non-echoing refusal messages. */
class MigrationTypedValuesTest {

    private fun queueArgs(valueJson: String): Map<String, ArgumentValue> =
        (only(fileWith("""{ "action": "declareQueue", "name": "q", "arguments": { "x-v": $valueJson } }""")) as MigrationAction.DeclareQueue).arguments

    private fun value(valueJson: String): ArgumentValue = queueArgs(valueJson).getValue("x-v")

    @Test
    fun `a whole number is Whole, a decimal is Decimal, an exponent stays a number`() {
        assertEquals(ArgumentValue.Whole(5), value("5"))
        assertEquals(ArgumentValue.Decimal(5.5), value("5.5"))
        assertEquals(ArgumentValue.Decimal(1000.0), value("1e3"))
    }

    @Test
    fun `a whole number above Long MAX_VALUE becomes Decimal`() {
        assertEquals(ArgumentValue.Decimal(9223372036854775808.0), value("9223372036854775808"))
        assertEquals(ArgumentValue.Whole(Long.MAX_VALUE), value(Long.MAX_VALUE.toString()))
        assertEquals(ArgumentValue.Whole(Long.MIN_VALUE), value(Long.MIN_VALUE.toString()))
    }

    @Test
    fun `boolean, null, text, list and object keep their type`() {
        assertEquals(ArgumentValue.Flag(true), value("true"))
        assertEquals(ArgumentValue.Flag(false), value("false"))
        assertEquals(ArgumentValue.Nothing, value("null"))
        assertEquals(ArgumentValue.Text("dlx"), value("\"dlx\""))
        assertEquals(ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"), ArgumentValue.Nothing)), value("""[1, "a", null]"""))
        assertEquals(
            ArgumentValue.Table(mapOf("k" to ArgumentValue.Items(listOf(ArgumentValue.Flag(false))))),
            value("""{ "k": [false] }"""),
        )
    }

    @Test
    fun `a number never becomes a string, and a quoted number stays a string`() {
        assertEquals(ArgumentValue.Whole(5), value("5"))
        assertEquals(ArgumentValue.Text("5"), value("\"5\""))
        assertFalse(value("5") is ArgumentValue.Text)
    }

    @Test
    fun `a policy definition is typed the same way`() {
        val policy = only(
            fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "definition": { "max-length": 1000, "overflow": "reject-publish", "ha": false } }"""),
        ) as MigrationAction.DeclarePolicy
        assertEquals(
            mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish"), "ha" to ArgumentValue.Flag(false)),
            policy.definition,
        )
    }

    // ---- fixed messages, nothing echoed

    private val sentinel = "SENTINEL-Zq7"

    private fun refusals(): List<Pair<String, String>> = listOf(
        // file text to expected fixed message
        fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "applyTo": "$sentinel" }""") to "the policy apply-to is not supported",
        fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "pattern": ".*", "applyTo": "$sentinel" }""") to "the policy apply-to is not supported",
        fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "destinationType": "$sentinel" }""") to "the binding destination type must be queue or exchange",
        fileWith("""{ "action": "declareQueue", "name": "q", "arguments": "$sentinel" }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declareQueue", "name": "q", "arguments": [ "$sentinel" ] }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declareExchange", "name": "x", "arguments": "$sentinel" }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "arguments": "$sentinel" }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "definition": "$sentinel" }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "pattern": ".*", "definition": "$sentinel" }""") to "the arguments are not valid or nested too deep",
        fileWith("""{ "action": "declareQueue", "name": "q", "arguments": { "x-queue-type": "$sentinel" } }""") to "the queue type belongs in \"type\"",
        fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "priority": "$sentinel" }""") to "the policy priority must be a whole number",
        fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "priority": 1.5 }""") to "the policy priority must be a whole number",
        fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "pattern": ".*", "priority": "$sentinel" }""") to "the policy priority must be a whole number",
    )

    @Test
    fun `each refusal gives its fixed message`() {
        for ((text, expected) in refusals()) assertEquals(expected, reason(text), "for: $text")
    }

    @Test
    fun `no refusal message contains any part of the input`() {
        for ((text, _) in refusals()) {
            val message = reason(text)
            assertFalse(message.contains(sentinel), "the message echoes the input: \"$message\" for: $text")
            assertFalse(message.contains("1.5"), "the message echoes the input: \"$message\"")
        }
    }

    @Test
    fun `x-queue-type is refused in a queue and the queue type key is not`() {
        assertEquals("the queue type belongs in \"type\"", reason(fileWith("""{ "action": "declareQueue", "name": "q", "arguments": { "x-queue-type": "quorum" } }""")))
        val ok = only(fileWith("""{ "action": "declareQueue", "name": "q", "type": "quorum", "arguments": { "x-delivery-limit": 5 } }""")) as MigrationAction.DeclareQueue
        assertEquals("quorum", ok.type)
        assertFalse("x-queue-type" in ok.arguments)
    }

    @Test
    fun `x-queue-type is only refused for queues, an exchange may carry that key like any other argument`() {
        // The rule is about "the queue type belongs in type"; spec says it for declareQueue only.
        only(fileWith("""{ "action": "declareExchange", "name": "x", "arguments": { "x-queue-type": "quorum" } }"""))
    }

    @Test
    fun `a whole priority, also a negative one, is accepted`() {
        for (p in listOf(0, 7, -3)) {
            assertEquals(p, (only(fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "priority": $p }""")) as MigrationAction.DeclarePolicy).priority)
        }
    }

    // ---- detail line of the dry-run row (brief scope 2)

    @Test
    fun `detail lists key=value pairs sorted by key with each value shown plainly`() {
        val queue = MigrationAction.DeclareQueue(
            "orders", "quorum",
            arguments = mapOf(
                "x-message-ttl" to ArgumentValue.Whole(60000),
                "x-dead-letter-exchange" to ArgumentValue.Text("dlx"),
                "x-delivery-limit" to ArgumentValue.Whole(5),
            ),
        )
        assertEquals("x-dead-letter-exchange=dlx, x-delivery-limit=5, x-message-ttl=60000", queue.detail)
    }

    @Test
    fun `detail shows boolean, null, list and object compactly`() {
        val exchange = MigrationAction.DeclareExchange(
            "x", "topic",
            arguments = mapOf(
                "a-flag" to ArgumentValue.Flag(true),
                "b-none" to ArgumentValue.Nothing,
                "c-list" to ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"))),
                "d-obj" to ArgumentValue.Table(mapOf("k" to ArgumentValue.Whole(2))),
                "e-ratio" to ArgumentValue.Decimal(0.5),
            ),
        )
        assertEquals("""a-flag=true, b-none=null, c-list=[1,"a"], d-obj={"k":2}, e-ratio=0.5""", exchange.detail)
    }

    @Test
    fun `detail of a policy has its definition keys and its apply-to and priority`() {
        val detail = MigrationAction.DeclarePolicy(
            "limits", "^orders",
            definition = mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish")),
            applyTo = "quorum_queues", priority = 7,
        ).detail
        assertTrue(detail != null && "max-length=1000" in detail && "overflow=reject-publish" in detail, "detail was: $detail")
        assertTrue("quorum_queues" in detail!! && "7" in detail, "detail was: $detail")
        assertTrue(MigrationAction.DeclareOperatorPolicy("o", ".*", mapOf("max-length" to ArgumentValue.Whole(1)), applyTo = "queues").detail!!.contains("max-length=1"))
    }

    @Test
    fun `detail is cut at 120 characters with an ellipsis`() {
        val long = MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("x-long" to ArgumentValue.Text("v".repeat(500))))
        val detail = long.detail!!
        assertTrue(detail.length in 100..125, "length ${detail.length}")
        assertTrue(detail.endsWith("…") || detail.endsWith("..."), detail.takeLast(5))
        assertTrue(detail.startsWith("x-long=vvvv"))
    }

    @Test
    fun `detail is null when there is nothing to show and description is not changed`() {
        assertNull(MigrationAction.DeclareQueue("orders", "quorum").detail)
        assertNull(MigrationAction.DeclareExchange("x", "topic").detail)
        assertNull(MigrationAction.DeclareBinding("e", "q").detail)
        assertNull(MigrationAction.DeleteQueue("q").detail)
        assertNull(MigrationAction.DeclareStream("s").detail)
        assertEquals("Declare quorum queue orders", MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("a" to ArgumentValue.Whole(1))).description)
        assertEquals("Declare policy p", MigrationAction.DeclarePolicy("p", ".*", mapOf("a" to ArgumentValue.Whole(1)), applyTo = "queues", priority = 2).description)
    }

    // ---- detail format pinned by the architect (step 2 answers)

    @Test
    fun `a policy detail is exactly apply-to, priority, then the sorted definition pairs`() {
        val policy = MigrationAction.DeclarePolicy(
            "limits", "^orders",
            definition = mapOf("overflow" to ArgumentValue.Text("reject-publish"), "max-length" to ArgumentValue.Whole(1000)),
            applyTo = "quorum_queues", priority = 7,
        )
        assertEquals("apply-to=quorum_queues, priority=7; max-length=1000, overflow=reject-publish", policy.detail)
        val operator = MigrationAction.DeclareOperatorPolicy("op", ".*", mapOf("max-length" to ArgumentValue.Whole(5)), applyTo = "queues", priority = 2)
        assertEquals("apply-to=queues, priority=2; max-length=5", operator.detail)
    }

    @Test
    fun `a default policy and a default operator policy have a detail, never null`() {
        assertEquals("apply-to=all, priority=0", MigrationAction.DeclarePolicy("p", ".*").detail)
        assertEquals("apply-to=all, priority=0", MigrationAction.DeclareOperatorPolicy("o", ".*").detail)
    }

    @Test
    fun `a control character in a key or value becomes a space and the detail is one line`() {
        val queue = MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("x-note" to ArgumentValue.Text("line1\nline2\ttab\r")))
        assertEquals("x-note=line1 line2 tab ", queue.detail)
        val policy = MigrationAction.DeclarePolicy("p", ".*", mapOf("a\nb" to ArgumentValue.Text("v")))
        assertEquals("apply-to=all, priority=0; a b=v", policy.detail)
        assertFalse(queue.detail!!.any { it.isISOControl() })
    }

    @Test
    fun `the detail is cut at 120 characters in total, 119 plus an ellipsis, also for a policy`() {
        val queue = MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("x-long" to ArgumentValue.Text("v".repeat(500))))
        assertEquals(120, queue.detail!!.length)
        assertEquals("x-long=" + "v".repeat(112) + "…", queue.detail)
        val policy = MigrationAction.DeclarePolicy("p", ".*", mapOf("max" to ArgumentValue.Text("w".repeat(500))))
        assertEquals(120, policy.detail!!.length)
        assertTrue(policy.detail!!.startsWith("apply-to=all, priority=0; max=www") && policy.detail!!.endsWith("…"))
        val exactly120 = MigrationAction.DeclareQueue("q", "quorum", arguments = mapOf("k" to ArgumentValue.Text("v".repeat(118))))
        assertEquals(120, exactly120.detail!!.length)
        assertFalse(exactly120.detail!!.endsWith("…"), "120 characters fit and are not cut")
    }

    // ---- JSON null as the whole arguments or definition means absent; a non-object does not

    @Test
    fun `null as the whole arguments or definition value means none`() {
        val queue = only(fileWith("""{ "action": "declareQueue", "name": "q", "arguments": null }""")) as MigrationAction.DeclareQueue
        assertEquals(only(fileWith("""{ "action": "declareQueue", "name": "q" }""")), queue)
        assertTrue(queue.arguments.isEmpty())
        val exchange = only(fileWith("""{ "action": "declareExchange", "name": "x", "arguments": null }""")) as MigrationAction.DeclareExchange
        assertTrue(exchange.arguments.isEmpty())
        val binding = only(fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "arguments": null }""")) as MigrationAction.DeclareBinding
        assertTrue(binding.arguments.isEmpty())
        for (kind in listOf("declarePolicy", "declareOperatorPolicy")) {
            val a = only(fileWith("""{ "action": "$kind", "name": "p", "pattern": ".*", "definition": null }"""))
            assertEquals(only(fileWith("""{ "action": "$kind", "name": "p", "pattern": ".*" }""")), a)
        }
    }

    @Test
    fun `a number, boolean or list as the whole arguments or definition stays refused`() {
        for (bad in listOf("5", "true", "[]", "\"text\"")) {
            assertEquals("the arguments are not valid or nested too deep", reason(fileWith("""{ "action": "declareQueue", "name": "q", "arguments": $bad }""")), bad)
            assertEquals("the arguments are not valid or nested too deep", reason(fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "definition": $bad }""")), bad)
        }
    }

    // ---- x-queue-type is refused for declareQueue arguments only

    @Test
    fun `exchange arguments, binding arguments and policy definitions may carry an x-queue-type key`() {
        val x = only(fileWith("""{ "action": "declareExchange", "name": "x", "arguments": { "x-queue-type": "quorum" } }""")) as MigrationAction.DeclareExchange
        assertEquals(ArgumentValue.Text("quorum"), x.arguments["x-queue-type"])
        val b = only(fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "arguments": { "x-queue-type": "quorum" } }""")) as MigrationAction.DeclareBinding
        assertEquals(ArgumentValue.Text("quorum"), b.arguments["x-queue-type"])
        val p = only(fileWith("""{ "action": "declarePolicy", "name": "p", "pattern": ".*", "definition": { "x-queue-type": "quorum" } }""")) as MigrationAction.DeclarePolicy
        assertEquals(ArgumentValue.Text("quorum"), p.definition["x-queue-type"])
        val o = only(fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "pattern": ".*", "definition": { "x-queue-type": "quorum" } }""")) as MigrationAction.DeclareOperatorPolicy
        assertEquals(ArgumentValue.Text("quorum"), o.definition["x-queue-type"])
    }

    @Test
    fun `U+2028, U+2029, NEL, vertical tab and form feed in a key or value become spaces in detail`() {
        val odd = listOf('\u2028', '\u2029', '\u0085', '\u000B', '\u000C')
        for (c in odd) {
            val queue = MigrationAction.DeclareQueue("q", arguments = mapOf("k${c}k" to ArgumentValue.Text("a${c}b")))
            assertEquals("k k=a b", queue.detail, "U+%04X".format(c.code))
            val policy = MigrationAction.DeclarePolicy("p", ".*", mapOf("x" to ArgumentValue.Text("v${c}w")))
            assertEquals("apply-to=all, priority=0; x=v w", policy.detail, "U+%04X".format(c.code))
        }
        val nested = MigrationAction.DeclareQueue("q", arguments = mapOf("k" to ArgumentValue.Items(listOf(ArgumentValue.Text("a\u2028b")))))
        assertTrue(nested.detail!!.none { it == '\u2028' || it == '\u2029' }, nested.detail)
    }
}
