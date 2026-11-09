package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M8-04 round 1 (F1/F2): an untrusted migration file with a value of the wrong JSON type is `Invalid` with a fixed message and NEVER throws.
 * `parse` is called directly, so an escaped IllegalArgumentException fails the test. The key named in the message is a fixed identifier of
 * the code; nothing of the offending value is echoed (a sentinel sits inside every object and array).
 */
class MigrationWrongTypeTest {

    private val sentinel = "SENTINEL-Wt9"
    private fun wrongType(key: String) = "the value of \"$key\" has the wrong type"

    private val wrong = mapOf(
        "number" to "5",
        "boolean" to "true",
        "object" to """{ "s": "$sentinel" }""",
        "array" to """[ "$sentinel" ]""",
    )

    /** Parses [text]; fails the test (with the case) if parse throws; returns the refusal, or null when the file is valid. */
    private fun outcome(case: String, text: String): String? {
        val result = try {
            MigrationParser.parse(text)
        } catch (t: Throwable) {
            fail("$case: parse threw ${t::class.simpleName} instead of returning Invalid; file: ${text.take(200)}")
        }
        return when (result) {
            is MigrationValidationResult.Invalid -> {
                assertFalse(result.reason.contains(sentinel), "$case: the message echoes the input: \"${result.reason}\"")
                result.reason
            }
            is MigrationValidationResult.Valid -> null
        }
    }

    /** Every case must be refused with one of [accepted] messages. */
    private fun mustRefuse(case: String, text: String, vararg accepted: String) {
        val reason = outcome(case, text) ?: fail("$case: the file was accepted but must be refused; file: ${text.take(200)}")
        assertTrue(reason in accepted, "$case: message was \"$reason\", expected one of ${accepted.toList()}")
    }

    // action -> the plain string keys it reads, with a valid base so only the key under test is wrong
    private val strings: Map<String, Pair<String, List<String>>> = mapOf(
        "declareQueue" to ("""name": "q", "type": "quorum""" to listOf("name", "type", "vhost")),
        "deleteQueue" to ("""name": "q""" to listOf("name", "vhost")),
        "declareExchange" to ("""name": "x", "type": "topic""" to listOf("name", "type", "vhost")),
        "deleteExchange" to ("""name": "x""" to listOf("name", "vhost")),
        "declareBinding" to ("""exchange": "e", "queue": "q", "routingKey": "k""" to listOf("exchange", "queue", "routingKey", "vhost")),
        "deleteBinding" to ("""exchange": "e", "queue": "q", "routingKey": "k""" to listOf("exchange", "queue", "routingKey", "vhost")),
        "declareStream" to ("""name": "s""" to listOf("name", "vhost")),
        "deleteStream" to ("""name": "s""" to listOf("name", "vhost")),
        "declarePolicy" to ("""name": "p", "pattern": ".*""" to listOf("name", "pattern", "vhost")),
        "declareOperatorPolicy" to ("""name": "p", "pattern": ".*""" to listOf("name", "pattern", "vhost")),
        "deletePolicy" to ("""name": "p""" to listOf("name", "vhost")),
    )

    private fun action(kind: String, key: String, valueJson: String): String {
        val base = strings.getValue(kind).first.let { "\"" + it + "\"" }
        // drop the key under test from the base, then add it with the wrong value
        val kept = base.split(", ").filterNot { it.trimStart().startsWith("\"$key\"") }
        return "{ \"action\": \"$kind\", ${(kept + "\"$key\": $valueJson").joinToString(", ")} }"
    }

    @Test
    fun `every plain string field refuses a number, boolean, object and array without throwing`() {
        for ((kind, spec) in strings) for (key in spec.second) for ((typeName, value) in wrong) {
            mustRefuse("$kind.$key=$typeName", fileWith(action(kind, key, value)), wrongType(key))
        }
    }

    @Test
    fun `the destination synonym refuses every wrong type`() {
        for ((typeName, value) in wrong) {
            val text = fileWith("""{ "action": "declareBinding", "exchange": "e", "destination": $value }""")
            mustRefuse("declareBinding.destination=$typeName", text, wrongType("destination"))
        }
    }

    @Test
    fun `the flags refuse a number, an object, an array and the string maybe`() {
        val flagKeys = mapOf(
            "declareQueue" to listOf("durable", "autoDelete"),
            "declareExchange" to listOf("durable", "autoDelete", "internal"),
        )
        val badValues = mapOf("number" to "5", "object" to wrong.getValue("object"), "array" to wrong.getValue("array"), "maybe" to "\"maybe\"")
        for ((kind, keys) in flagKeys) for (key in keys) for ((typeName, value) in badValues) {
            mustRefuse("$kind.$key=$typeName", fileWith(action(kind, key, value)), wrongType(key))
        }
    }

    @Test
    fun `applyTo and destinationType refuse a number, an object and an array`() {
        for (typeName in listOf("number", "object", "array")) {
            val v = wrong.getValue(typeName)
            for (kind in listOf("declarePolicy", "declareOperatorPolicy")) {
                mustRefuse("$kind.applyTo=$typeName", fileWith("""{ "action": "$kind", "name": "p", "applyTo": $v }"""), wrongType("applyTo"), "the policy apply-to is not supported")
            }
            mustRefuse(
                "declareBinding.destinationType=$typeName",
                fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "destinationType": $v }"""),
                wrongType("destinationType"), "the binding destination type must be queue or exchange",
            )
        }
    }

    @Test
    fun `priority refuses an object, an array, a string and a decimal`() {
        for ((typeName, v) in mapOf("object" to wrong.getValue("object"), "array" to wrong.getValue("array"), "string" to "\"3\"", "decimal" to "3.5")) {
            for (kind in listOf("declarePolicy", "declareOperatorPolicy")) {
                mustRefuse("$kind.priority=$typeName", fileWith("""{ "action": "$kind", "name": "p", "priority": $v }"""), wrongType("priority"), "the policy priority must be a whole number")
            }
        }
    }

    @Test
    fun `arguments and definition refuse a number, a string and an array`() {
        val arguments = "the arguments are not valid or nested too deep"
        for ((typeName, v) in mapOf("number" to "5", "string" to "\"$sentinel\"", "array" to wrong.getValue("array"))) {
            mustRefuse("declareQueue.arguments=$typeName", fileWith("""{ "action": "declareQueue", "name": "q", "arguments": $v }"""), wrongType("arguments"), arguments)
            mustRefuse("declareExchange.arguments=$typeName", fileWith("""{ "action": "declareExchange", "name": "x", "arguments": $v }"""), wrongType("arguments"), arguments)
            mustRefuse("declareBinding.arguments=$typeName", fileWith("""{ "action": "declareBinding", "exchange": "e", "queue": "q", "arguments": $v }"""), wrongType("arguments"), arguments)
            mustRefuse("declarePolicy.definition=$typeName", fileWith("""{ "action": "declarePolicy", "name": "p", "definition": $v }"""), wrongType("definition"), arguments)
            mustRefuse("declareOperatorPolicy.definition=$typeName", fileWith("""{ "action": "declareOperatorPolicy", "name": "p", "definition": $v }"""), wrongType("definition"), arguments)
        }
    }

    // ---- root fields

    @Test
    fun `the root version refuses a boolean, an object and an array`() {
        for (typeName in listOf("boolean", "object", "array")) {
            mustRefuse("version=$typeName", """{ "version": ${wrong.getValue(typeName)}, "up": [] }""", wrongType("version"))
        }
    }

    @Test
    fun `the root description refuses a number, a boolean, an object and an array`() {
        for ((typeName, v) in wrong) mustRefuse("description=$typeName", """{ "version": "1", "description": $v, "up": [] }""", wrongType("description"))
    }

    @Test
    fun `the action key refuses a number, an object, an array and null`() {
        for ((typeName, v) in wrong + ("null" to "null")) {
            if (typeName == "boolean") continue
            mustRefuse("action=$typeName", fileWith("""{ "action": $v, "name": "q" }"""), wrongType("action"), "which action is not supported")
        }
    }

    // ---- compatibility: what stays accepted

    @Test
    fun `a numeric version is accepted and read as its text`() {
        val v = (MigrationParser.parse("""{ "version": 3, "up": [] }""") as MigrationValidationResult.Valid).migration.version
        assertEquals("3", v)
    }

    @Test
    fun `the string false and the string true are still read as flags`() {
        val q = (MigrationParser.parse(fileWith("""{ "action": "declareQueue", "name": "q", "durable": "false", "autoDelete": "true" }""")) as MigrationValidationResult.Valid)
            .migration.up.single() as MigrationAction.DeclareQueue
        assertFalse(q.durable)
        assertTrue(q.autoDelete)
    }

    @Test
    fun `JSON null for a text field counts as absent`() {
        assertEquals("queue name is required", outcome("null name", fileWith("""{ "action": "declareQueue", "name": null }""")))
        assertEquals("exchange name is required", outcome("null exchange name", fileWith("""{ "action": "declareExchange", "name": null }""")))
        assertEquals("policy name is required", outcome("null policy name", fileWith("""{ "action": "declarePolicy", "name": null }""")))
        assertEquals("stream name is required", outcome("null stream name", fileWith("""{ "action": "declareStream", "name": null }""")))
        assertEquals("binding requires exchange and queue", outcome("null exchange", fileWith("""{ "action": "declareBinding", "exchange": null, "queue": "q" }""")))
    }

    @Test
    fun `JSON null for vhost, type and durable takes the defaults`() {
        val q = (MigrationParser.parse(fileWith("""{ "action": "declareQueue", "name": "q", "vhost": null, "type": null, "durable": null }""")) as MigrationValidationResult.Valid)
            .migration.up.single()
        assertEquals(MigrationAction.DeclareQueue("q"), q)
        val x = (MigrationParser.parse(fileWith("""{ "action": "declareExchange", "name": "x", "type": null, "durable": null, "vhost": null }""")) as MigrationValidationResult.Valid)
            .migration.up.single()
        assertEquals(MigrationAction.DeclareExchange("x"), x)
    }

    @Test
    fun `JSON null for the version means no version`() {
        assertEquals("the file has no version", outcome("null version", """{ "version": null, "up": [] }"""))
    }

    @Test
    fun `only the version may be a number`() {
        mustRefuse("number name", fileWith("""{ "action": "declareQueue", "name": 5 }"""), wrongType("name"))
        mustRefuse("number description", """{ "version": "1", "description": 5, "up": [] }""", wrongType("description"))
    }
}
