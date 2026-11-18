package dev.caerbannog.core.migrations

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-12 step 2 (RED by compilation until `MigrationParser.hasUnknownKeys` exists): "answers whether the file, or an action in it, holds any other key".
 * Top level: `version`, `description`, `up`. Per action: `action`, `vhost` and the keys its codec states (`ActionCodec.keys`).
 * Contract where the brief is silent: text that is not a JSON object answers true (nothing can be said to be known), so a caller that skips the check
 * for an invalid file cannot be led into opening it; the planner only asks about files the parser accepted.
 */
class MigrationParserUnknownKeysTest {
    private fun file(top: String = "", action: String = """{ "action": "declareQueue", "name": "orders" }""") =
        """{ "version": "0005", "description": "d", $top "up": [ $action ] }"""

    @Test
    fun `a file the app wrote holds no unknown key for any action it can write`() {
        val actions = listOf(
            MigrationAction.DeclareQueue("q", "classic", "orders", durable = false, autoDelete = true),
            MigrationAction.DeclareExchange("e", "topic", "orders", internal = true),
            MigrationAction.DeclareBinding("e", "q", "k", "orders"),
            MigrationAction.DeclareStream("s", "orders"),
            MigrationAction.DeclarePolicy("p", "^a", emptyMap(), "orders", "queues", 3),
            MigrationAction.DeclareOperatorPolicy("op", "^a", emptyMap(), "orders", "queues", 3),
            MigrationAction.DeleteQueue("old"), MigrationAction.DeleteExchange("old"), MigrationAction.DeleteStream("old"),
            MigrationAction.DeleteBinding("e", "q", "k"), MigrationAction.DeletePolicy("old"),
        )
        assertEquals(false, MigrationParser.hasUnknownKeys(MigrationParser.exportToJson("0005", "d", actions)))
    }

    @Test
    fun `a plain file holds none`() = assertEquals(false, MigrationParser.hasUnknownKeys(file()))

    @Test
    fun `an extra key on the file is unknown`() = assertEquals(true, MigrationParser.hasUnknownKeys(file(top = """ "author": "me",""")))

    @Test
    fun `an extra key on an action is unknown`() =
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = """{ "action": "declareQueue", "name": "orders", "comment": "x" }""")))

    @Test
    fun `an extra key on one action of several is found`() {
        val up = """{ "action": "declareQueue", "name": "a" }, { "action": "declareExchange", "name": "b", "note": 1 }"""
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = up)))
    }

    @Test
    fun `vhost and action are known on every action`() =
        assertEquals(false, MigrationParser.hasUnknownKeys(file(action = """{ "action": "deleteQueue", "name": "a", "vhost": "orders" }""")))

    @Test
    fun `a key that belongs to another action is unknown here`() {
        // "durable" is a queue key: a deleteQueue does not read it, so saving would drop it
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = """{ "action": "deleteQueue", "name": "a", "durable": false }""")))
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = """{ "action": "declareStream", "name": "a", "type": "quorum" }""")))
    }

    @Test
    fun `the binding's read-only spelling destination is a known key`() =
        assertEquals(false, MigrationParser.hasUnknownKeys(file(action = """{ "action": "declareBinding", "exchange": "e", "destination": "q" }""")))

    @Test
    fun `key names are case sensitive`() {
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = """{ "action": "declareQueue", "Name": "orders", "name": "orders" }""")))
        assertEquals(true, MigrationParser.hasUnknownKeys(file(top = """ "Version": "1",""")))
    }

    @Test
    fun `a null value under an unknown key is still an unknown key`() =
        assertEquals(true, MigrationParser.hasUnknownKeys(file(action = """{ "action": "declareQueue", "name": "orders", "comment": null }""")))

    @Test
    fun `text that is not a JSON object answers true`() {
        listOf("", "not json", "[]", "42", "null").forEach { assertEquals(true, MigrationParser.hasUnknownKeys(it), "text: $it") }
    }
}
