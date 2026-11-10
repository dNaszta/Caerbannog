package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M8-06b: `MigrationParser.parseActions` and `actionsToJson` (brief "Core additions"). The sheet of "Replace with my own actions" is prefilled
 * with `actionsToJson(previewActions(...))` and read back with `parseActions`, so the two must be inverses for every action the codecs know,
 * and the reasons of `parseActions` must be the ones `parse` gives for the same action (one shared loop, no drift).
 */
class ActionsParserTest {
    private val everyAction: List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("orders", "quorum", "/", true, mapOf("x-delivery-limit" to ArgumentValue.Whole(5)), false),
        MigrationAction.DeclareQueue("jobs", "classic", "/", false, mapOf("x-max-priority" to ArgumentValue.Whole(5)), true),
        MigrationAction.DeclareExchange("events", "topic", "/", true, false, true, mapOf("alternate-exchange" to ArgumentValue.Text("dead"))),
        MigrationAction.DeclareBinding("events", "orders", "k", "/", BindingDestinationType.QUEUE, mapOf("x-match" to ArgumentValue.Text("all"))),
        MigrationAction.DeclareBinding("events", "audit", "", "/", BindingDestinationType.EXCHANGE),
        MigrationAction.DeclareStream("log", "/"),
        MigrationAction.DeclarePolicy("limits", "^orders$", mapOf("max-length" to ArgumentValue.Whole(10)), "/", "queues", 3),
        MigrationAction.DeclareOperatorPolicy("op", "^orders$", mapOf("max-length" to ArgumentValue.Whole(10)), "/", "all", 0),
        MigrationAction.DeleteQueue("old-q"),
        MigrationAction.DeleteExchange("old-e"),
        MigrationAction.DeleteBinding("events", "old-q", "k"),
        MigrationAction.DeleteStream("old-s"),
        MigrationAction.DeletePolicy("old-p"),
    )

    private fun parsed(text: String): List<MigrationAction> = assertIs<ActionsParseResult.Parsed>(MigrationParser.parseActions(text), "for $text").actions
    private fun invalid(text: String): String = assertIs<ActionsParseResult.Invalid>(MigrationParser.parseActions(text), "for $text").reason

    @Test
    fun `premise - the file path already round-trips the same actions, so a failure above is the new functions`() {
        for (list in listOf(everyAction.filterNot { it.isDestructive }, everyAction.filter { it.isDestructive })) {
            val file = MigrationParser.parse(MigrationParser.exportToJson("0001", "d", list)) as MigrationValidationResult.Valid
            assertEquals(list, file.migration.up)
        }
    }

    @Test
    fun `the round trip holds for every action type the codecs know, in another vhost too`() {
        // The delete actions are parsed too (the CORE refuses them in a replacement, the parser does not): the codecs know them.
        // RerunSafety refuses a file that declares and deletes the same item, so declares and deletes go in separate lists.
        val declares = everyAction.filterNot { it.isDestructive }
        val deletes = everyAction.filter { it.isDestructive }
        for (list in listOf(declares, deletes, declares.map { it.inVhost("other") }, listOf(MigrationAction.DeclareQueue("x"))))
            assertEquals(ActionsParseResult.Parsed(list), MigrationParser.parseActions(MigrationParser.actionsToJson(list)), "for ${list.map { it.description }}")
    }

    private fun MigrationAction.inVhost(v: String): MigrationAction = when (this) {
        is MigrationAction.DeclareQueue -> copy(vhost = v)
        is MigrationAction.DeclareExchange -> copy(vhost = v)
        is MigrationAction.DeclareBinding -> copy(vhost = v)
        is MigrationAction.DeclareStream -> copy(vhost = v)
        is MigrationAction.DeclarePolicy -> copy(vhost = v)
        is MigrationAction.DeclareOperatorPolicy -> copy(vhost = v)
        else -> this
    }

    @Test
    fun `actionsToJson is a pretty JSON array that omits the vhost "slash" like exportToJson and writes any other vhost`() {
        val text = MigrationParser.actionsToJson(listOf(MigrationAction.DeclareQueue("a"), MigrationAction.DeclareQueue("b", vhost = "other")))
        assertTrue(text.trimStart().startsWith("["), text)
        assertTrue(text.contains("\n"), "pretty printed: $text")
        assertEquals(1, Regex("\"vhost\"").findAll(text).count(), "only the action for another vhost carries it: $text")
        assertTrue(text.contains("\"other\""), text)
        // the same writer as exportToJson: every action of the array appears in the file text of the same actions, field for field
        val file = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareQueue("a"), MigrationAction.DeclareQueue("b", vhost = "other")))
        assertEquals(parsed(text), (MigrationParser.parse(file) as MigrationValidationResult.Valid).migration.up)
    }

    @Test
    fun `an empty list is parsed and writes an empty array`() {
        assertEquals(emptyList(), parsed("[]"))
        assertEquals(emptyList(), parsed(MigrationParser.actionsToJson(emptyList())))
        assertEquals(emptyList(), parsed("  [ ]  "))
    }

    @Test
    fun `a missing vhost means slash`() {
        val actions = parsed("""[{"action":"declareQueue","name":"orders","type":"quorum"}]""")
        assertEquals(listOf(MigrationAction.DeclareQueue("orders", "quorum", "/")), actions)
    }

    @Test
    fun `the reasons for text that is not an array of actions`() {
        assertEquals("the text is not valid JSON", invalid("not json"))
        assertEquals("the text is not valid JSON", invalid(""))
        assertEquals("the text is not valid JSON", invalid("[{\"action\":"))
        assertEquals("the text is not a list of actions", invalid("""{"action":"declareQueue","name":"x"}"""))
        assertEquals("the text is not a list of actions", invalid("\"declareQueue\""))
        assertEquals("the text is not a list of actions", invalid("42"))
        assertEquals("the text is not a list of actions", invalid("null"))
    }

    @Test
    fun `nesting too deep is refused with its reason and never overflows the stack`() {
        assertEquals("the text is nested too deep", invalid("[".repeat(10_000) + "]".repeat(10_000)))
        assertEquals("the text is nested too deep", invalid("""[{"action":"declareQueue","name":"x","arguments":""" + "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000) + "}]"))
    }

    @Test
    fun `an element that is not an object, or has no action, gets the reason parse gives`() {
        val expected = (MigrationParser.parse("""{"version":"0001","up":[1]}""") as MigrationValidationResult.Invalid).reason
        assertEquals(expected, invalid("[1]"))
        assertEquals(expected, invalid("""[{"name":"x"}]"""))
        assertEquals(expected, invalid("""[{"action":""}]"""))
        assertEquals(expected, invalid("""["declareQueue"]"""))
    }

    @Test
    fun `an unknown action, a codec refusal and a rerun conflict give exactly the reason parse gives`() {
        val cases = listOf(
            """{"action":"declareShovel","name":"s"}""",
            """{"action":"declareQueue","name":".."}""",
            """{"action":"declareQueue","name":"."}""",
            """{"action":"declareQueue","name":"q","durable":"yes"}""",
        )
        for (action in cases) {
            val viaFile = (MigrationParser.parse("""{"version":"0001","up":[$action]}""") as MigrationValidationResult.Invalid).reason
            assertEquals(viaFile, invalid("[$action]"), "for $action")
        }
        val conflict = """[{"action":"declareQueue","name":"q"},{"action":"deleteQueue","name":"q"}]"""
        val viaFile = (MigrationParser.parse("""{"version":"0001","up":$conflict}""") as MigrationValidationResult.Invalid).reason
        assertEquals(viaFile, invalid(conflict))
        assertTrue(viaFile.contains("split it into two migrations"), viaFile)
    }

    @Test
    fun `a hostile action name or queue name is never echoed`() {
        val hostile = "<script>alert(1)</script>\u0007‮evil"
        val reason = invalid("""[{"action":"$hostile"}]""")
        assertFalse(reason.contains("script") || reason.contains("evil") || reason.contains('\u0007') || reason.contains('‮'), reason)
        val quote = invalid("""[{"action":"declareQueue","name":".."}]""")
        assertFalse(quote.contains("SENTINEL"), quote)
        val long = invalid("""[{"action":"${"a".repeat(65)}"}]""")
        assertFalse(long.contains("aaaaaaaa"), "a 65 character action name is not echoed: $long")
        // a plain short unknown action name keeps the echo rule of the file parser
        assertEquals((MigrationParser.parse("""{"version":"0001","up":[{"action":"declareShovel"}]}""") as MigrationValidationResult.Invalid).reason, invalid("""[{"action":"declareShovel"}]"""))
    }

    @Test
    fun `a reason never holds the input text`() {
        val secret = "SENTINEL-PASSWORD-xyz"
        for (text in listOf("$secret not json", "[\"$secret\"]", "{\"k\":\"$secret\"}", "[{\"action\":\"$secret\"}]", "[{\"action\":\"declareQueue\",\"name\":\"$secret\\u0000\"}]"))
            assertFalse((MigrationParser.parseActions(text) as? ActionsParseResult.Invalid)?.reason.orEmpty().contains(secret), "reason for $text")
    }

    @Test
    fun `an array of 100,000 elements is read in bounded time and gives one reason`() {
        val text = "[" + List(100_000) { "1" }.joinToString(",") + "]"
        val started = System.nanoTime()
        val result = MigrationParser.parseActions(text)
        assertIs<ActionsParseResult.Invalid>(result)
        assertTrue((System.nanoTime() - started) < 20_000_000_000L, "took too long")
        val big = "[" + List(100_000) { """{"action":"declareQueue","name":"q$it"}""" }.joinToString(",") + "]"
        assertEquals(100_000, parsed(big).size)
    }
}
