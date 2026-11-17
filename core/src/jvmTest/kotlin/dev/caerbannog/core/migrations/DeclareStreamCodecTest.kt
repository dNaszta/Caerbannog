package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationTestSupport.actions
import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.keyOrders
import dev.caerbannog.core.migrations.MigrationTestSupport.only
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14c: `DeclareStream.arguments` through the whole parser and writer (`declareStreamCodec`, a codec of its own like `declareQueueCodec`).
 * Backward compatible by construction (A2): a file without `arguments` parses to the same action and is written back byte for byte; the key is
 * written only when the map is not empty. Contract fixed here: `MigrationAction.DeclareStream(name, vhost = "/", arguments = emptyMap())`,
 * `detail` = the existing key=value wording of a queue's arguments, `description` unchanged.
 */
class DeclareStreamCodecTest {

    private fun stream(argumentsJson: String?, name: String = "audit") =
        """{ "action": "declareStream", "name": "$name"${argumentsJson?.let { ", \"arguments\": $it" } ?: ""} }"""

    private fun declared(argumentsJson: String?) = only(fileWith(stream(argumentsJson))) as MigrationAction.DeclareStream

    // ---- backward compatibility: the old shape, byte for byte ----

    @Test
    fun `a stream without arguments is written exactly as before, no arguments key`() {
        val written = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareStream("audit"), MigrationAction.DeclareStream("t", "other")))
        assertEquals(
            """
            {
                "version": "0001",
                "description": "d",
                "up": [
                    {
                        "action": "declareStream",
                        "name": "audit"
                    },
                    {
                        "action": "declareStream",
                        "vhost": "other",
                        "name": "t"
                    }
                ]
            }
            """.trimIndent(),
            written,
        )
        assertFalse(written.contains("arguments"), written)
    }

    @Test
    fun `an empty arguments object parses to no arguments and is written back without the key`() {
        assertEquals(MigrationAction.DeclareStream("audit"), declared("{}"))
        val back = MigrationParser.actionsToJson(listOf(declared("{}")))
        assertFalse(back.contains("arguments"), back)
    }

    @Test
    fun `a file without arguments parses to the same action as before`() {
        assertEquals(MigrationAction.DeclareStream("audit", "/", emptyMap()), declared(null))
        assertEquals(emptyMap(), declared(null).arguments)
        assertNull(declared(null).detail)
    }

    @Test
    fun `the action keeps its description and gets a detail only from its arguments`() {
        val withSettings = MigrationAction.DeclareStream("audit", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-queue-leader-locator" to ArgumentValue.Text("balanced")))
        assertEquals("Declare stream audit", withSettings.description)
        assertEquals("x-max-age=7D, x-queue-leader-locator=balanced", withSettings.detail)
        assertEquals("Declare stream audit", MigrationAction.DeclareStream("audit").description)
        assertNull(MigrationAction.DeclareStream("audit").detail)
    }

    // ---- round trip ----

    private val allFour = linkedMapOf<String, ArgumentValue>(
        "x-max-age" to ArgumentValue.Text("7D"),
        "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000),
        "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(100_000_000),
        "x-queue-leader-locator" to ArgumentValue.Text("client-local"),
    )

    @Test
    fun `a stream with the four settings reads with their types`() {
        val read = declared("""{ "x-max-age": "7D", "x-max-length-bytes": 1000000000, "x-stream-max-segment-size-bytes": 100000000, "x-queue-leader-locator": "client-local" }""")
        assertEquals(allFour, read.arguments)
        assertIs<ArgumentValue.Text>(read.arguments.getValue("x-max-age"))
        assertIs<ArgumentValue.Whole>(read.arguments.getValue("x-max-length-bytes"))
    }

    @Test
    fun `the round trip keeps every setting, text as text and numbers as JSON numbers`() {
        val action = MigrationAction.DeclareStream("audit", "/", allFour)
        val written = MigrationParser.exportToJson("0001", "d", listOf(action))
        assertTrue(Regex("\"x-max-length-bytes\": 1000000000(?![\\d\"])").containsMatchIn(written), "a number must stay a number: $written")
        assertTrue(written.contains("\"x-max-age\": \"7D\""), written)
        assertEquals(listOf(action), actions(written))
        assertEquals(MigrationValidationResult.Valid::class, MigrationParser.parse(written)::class)
        assertEquals(ActionsParseResult.Parsed(listOf(action)), MigrationParser.parseActions(MigrationParser.actionsToJson(listOf(action))))
    }

    @Test
    fun `one setting alone and each single setting round trip`() {
        for ((key, value) in allFour) {
            val action = MigrationAction.DeclareStream("audit", arguments = mapOf(key to value))
            assertEquals(listOf(action), actions(MigrationParser.exportToJson("0001", "d", listOf(action))), key)
        }
    }

    @Test
    fun `arguments come after the name and a stream in another vhost keeps it`() {
        val action = MigrationAction.DeclareStream("audit", "other", mapOf("x-max-age" to ArgumentValue.Text("7D")))
        val order = keyOrders(MigrationParser.exportToJson("0001", "d", listOf(action))).single()
        assertEquals(listOf("action", "vhost", "name", "arguments"), order)
        assertEquals(listOf(action), actions(MigrationParser.exportToJson("0001", "d", listOf(action))))
    }

    @Test
    fun `the checksum of a file without arguments does not depend on this change`() {
        val raw = fileWith(stream(null))
        assertEquals(MigrationParser.computeChecksum(raw), (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration.checksum)
    }

    // ---- refusals, each with its reason ----

    private fun refusal(argumentsJson: String) = reason(fileWith(stream(argumentsJson)))

    @Test
    fun `each refusal of the outline has its fixed reason`() {
        assertEquals("stream setting \"x-dead-letter-exchange\" is not supported", refusal("""{ "x-dead-letter-exchange": "x" }"""))
        assertEquals("stream setting \"x-queue-type\" is not supported", refusal("""{ "x-queue-type": "stream" }"""))
        assertEquals("stream setting \"x-max-age\" must be text such as 7D", refusal("""{ "x-max-age": 7 }"""))
        assertEquals("stream setting \"x-max-age\" must be text such as 7D", refusal("""{ "x-max-age": "7d" }"""))
        assertEquals("stream setting \"x-max-length-bytes\" must be a whole number above 0", refusal("""{ "x-max-length-bytes": "1gb" }"""))
        assertEquals("stream setting \"x-max-length-bytes\" must be a whole number above 0", refusal("""{ "x-max-length-bytes": 0 }"""))
        assertEquals("stream setting \"x-stream-max-segment-size-bytes\" must be a whole number above 0", refusal("""{ "x-stream-max-segment-size-bytes": -1 }"""))
        assertEquals(
            "stream setting \"x-queue-leader-locator\" must be balanced, client-local, random or least-leaders",
            refusal("""{ "x-queue-leader-locator": "nearest" }"""),
        )
    }

    @Test
    fun `a decimal, an exponent and a number above Long MAX_VALUE are not whole numbers`() {
        val reason = "stream setting \"x-max-length-bytes\" must be a whole number above 0"
        for (v in listOf("1.5", "1e3", "1000.0", "9223372036854775808")) assertEquals(reason, refusal("""{ "x-max-length-bytes": $v }"""), v)
    }

    @Test
    fun `a nested table, a list, a flag and null are refused where a text or a number is required`() {
        for (v in listOf("""{ "a": 1 }""", "[1]", "true", "null")) {
            assertEquals("stream setting \"x-max-age\" must be text such as 7D", refusal("""{ "x-max-age": $v }"""), v)
            assertEquals("stream setting \"x-max-length-bytes\" must be a whole number above 0", refusal("""{ "x-max-length-bytes": $v }"""), v)
        }
    }

    @Test
    fun `arguments that are not an object keep the existing reason`() {
        for (v in listOf("[]", "\"x\"", "5", "true")) assertEquals("the arguments are not valid or nested too deep", refusal(v), v)
    }

    @Test
    fun `one refused setting refuses the whole file, nothing of it is read`() {
        val text = fileWith(stream("""{ "x-max-age": "7D", "x-foo": 1 }"""))
        assertEquals("stream setting \"x-foo\" is not supported", reason(text))
        assertIs<ActionsParseResult.Invalid>(MigrationParser.parseActions("[" + stream("""{ "x-max-age": "7D", "x-foo": 1 }""") + "]"))
    }

    @Test
    fun `the protocol names are not settings of the file`() {
        for (key in listOf("max-age", "max-length-bytes", "stream-max-segment-size-bytes", "queue-leader-locator")) {
            assertEquals("stream setting \"$key\" is not supported", refusal("""{ "$key": "x" }"""), key)
        }
    }

    // ---- hostile values ----

    @Test
    fun `a control character, a NUL or ten thousand characters in a value never reach a reason`() {
        val hostileTexts = listOf("7D\\n", "7D\\u0000", "\\u202E7D", "9".repeat(10_000) + "D", "x".repeat(10_000))
        for (v in hostileTexts) {
            val r = refusal("""{ "x-max-age": "$v" }""")
            assertEquals("stream setting \"x-max-age\" must be text such as 7D", r)
            val l = refusal("""{ "x-queue-leader-locator": "$v" }""")
            assertEquals("stream setting \"x-queue-leader-locator\" must be balanced, client-local, random or least-leaders", l)
        }
    }

    @Test
    fun `a hostile key is refused with a short plain reason`() {
        for (key in listOf("x".repeat(10_000), "x-a\\nb", "x-a\\u0000b", "x\\\"y", "x-\\u202Edcba")) {
            val r = refusal("""{ "$key": 1 }""")
            assertTrue(r.length <= 200, "reason of ${r.length} characters")
            assertTrue(r.none { it.isISOControl() || it == '‮' }, r.take(80))
            assertTrue(r.startsWith("stream setting") && r.endsWith("is not supported"), r.take(80))
        }
    }

    @Test
    fun `a value nested far too deep is refused with a fixed text and does not overflow the stack`() {
        val r = refusal("""{ "x-max-age": ${MigrationTestSupport.nested(5_000)} }""")
        assertTrue(
            r in setOf("the file is nested too deep", "the arguments are not valid or nested too deep", "stream setting \"x-max-age\" must be text such as 7D"),
            "a fixed reason was expected, got ${r.take(80)}",
        )
    }

    @Test
    fun `a file of a stream with settings and a stream without declares two different actions`() {
        val both = actions(fileWith(stream("""{ "x-max-age": "7D" }""", "a"), stream(null, "b")))
        assertEquals(listOf(mapOf("x-max-age" to ArgumentValue.Text("7D")), emptyMap()), both.map { (it as MigrationAction.DeclareStream).arguments })
    }

    @Test
    fun `RerunSafety is not changed by arguments, a declare and a delete of the same stream still conflict`() {
        val text = fileWith(stream("""{ "x-max-age": "7D" }"""), """{ "action": "deleteStream", "name": "audit" }""")
        assertTrue(reason(text).isNotBlank())
    }
}
