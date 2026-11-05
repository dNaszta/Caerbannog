package dev.caerbannog.devseed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * M2-12, C3/C4: the in-memory definitions body that `dev:seed --queues N --vhost V` posts to `POST /api/definitions`
 * (brief, open point 2: generated at seed time, nothing committed). Idempotent on the broker because every queue is
 * declared with the same arguments each time; the test pins that the arguments are fixed and equal.
 * Contract: build(vhost, count) is a JSON object with a "queues" array of exactly `count` entries, one per
 * QueueNames.generate(count) name, each `{"name", "vhost", "durable": true, "auto_delete": false,
 * "arguments": {"x-queue-type": "quorum"}}`. It declares no vhost, user, permission or exchange (those belong to
 * 001). Count 0 gives an empty "queues" array, a negative count is an IllegalArgumentException. The vhost name is
 * JSON-escaped (quotes, backslash, unicode).
 */
class ManyQueuesDefinitionsTest {
    private fun queues(json: String): JsonArray = Json.parseToJsonElement(json).jsonObject["queues"]!!.jsonArray

    @Test
    fun `count 0 gives an empty queue list`() {
        assertEquals(0, queues(ManyQueuesDefinitions.build("many", 0)).size)
    }

    @Test
    fun `count 1 gives one quorum queue in the vhost`() {
        val q = queues(ManyQueuesDefinitions.build("many", 1)).single().jsonObject
        assertEquals(QueueNames.generate(1).single(), q["name"]!!.jsonPrimitive.content)
        assertEquals("many", q["vhost"]!!.jsonPrimitive.content)
        assertTrue(q["durable"]!!.jsonPrimitive.boolean)
        assertEquals(false, q["auto_delete"]!!.jsonPrimitive.boolean)
        assertEquals(
            mapOf("x-queue-type" to "quorum"),
            q["arguments"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `count N gives N queues named like the generator and nothing else`() {
        val root = Json.parseToJsonElement(ManyQueuesDefinitions.build("many", 1000)).jsonObject
        assertEquals(setOf("queues"), root.keys, "only queues are declared here")
        assertEquals(QueueNames.generate(1000), root["queues"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `the body is the same on every call so a second seed run declares equal queues`() {
        assertEquals(ManyQueuesDefinitions.build("many", 50), ManyQueuesDefinitions.build("many", 50))
    }

    @Test
    fun `an unusual vhost name survives JSON escaping`() {
        val vhost = "we\"ird \\ vhost/é"
        val q = queues(ManyQueuesDefinitions.build(vhost, 1)).single().jsonObject
        assertEquals(vhost, q["vhost"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<IllegalArgumentException> { ManyQueuesDefinitions.build("many", -1) }
    }
}
