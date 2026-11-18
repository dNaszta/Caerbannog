package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-12 step 2 (RED by compilation until `ActionCodec.keys: Set<String>` exists): "every ActionCodec states the keys it reads", and the guard that makes
 * `MigrationParser.hasUnknownKeys` safe: a codec that reads a key it does not state would let a file with that key pass as "known", and a codec that states
 * a key it does not read would hide an unknown one. For each of the 15 codecs, with an action that sets every field away from its default:
 *  1. `keys` is exactly the table below (the keys the codec reads; `action` and `vhost` are not part of it, they are known for every action);
 *  2. every key the codec writes is in `keys`;
 *  3. every key in `keys` that the codec writes is READ: reading the entry without it gives another result;
 *  4. a key that is not in `keys` is not read: an entry with one more key reads the same.
 * `destination` (the binding's second spelling of `queue`) is read and never written: it has its own check.
 */
class ActionCodecKeysGuardTest {
    private val nested = mapOf("x-a" to ArgumentValue.Text("v"), "x-b" to ArgumentValue.Whole(2), "x-c" to ArgumentValue.Flag(true))

    private val samples: Map<String, MigrationAction> = listOf(
        MigrationAction.DeclareQueue("q", "classic", "orders", durable = false, arguments = nested, autoDelete = true),
        MigrationAction.DeleteQueue("q", "orders"),
        MigrationAction.DeclareExchange("e", "topic", "orders", durable = false, autoDelete = true, internal = true, arguments = nested),
        MigrationAction.DeleteExchange("e", "orders"),
        MigrationAction.DeclareBinding("e", "q", "rk", "orders", BindingDestinationType.EXCHANGE, nested),
        MigrationAction.DeleteBinding("e", "q", "rk", "orders"),
        MigrationAction.DeclareStream(
            "s", "orders",
            mapOf(
                "x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Whole(1000),
                "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(500), "x-queue-leader-locator" to ArgumentValue.Text("balanced"),
            ),
        ),
        MigrationAction.DeleteStream("s", "orders"),
        MigrationAction.DeclarePolicy("p", "^a", nested, "orders", "queues", 5),
        MigrationAction.DeclareOperatorPolicy("op", "^a", nested, "orders", "quorum_queues", 5),
        MigrationAction.DeletePolicy("p", "orders"),
        MigrationAction.DeclareVhost("orders", "the orders", listOf("t1"), "quorum"),
        MigrationAction.DeclareUser("svc", SecretValue.of("HASH"), "rabbit_password_hashing_sha512", listOf("management")),
        MigrationAction.SetPermissions("orders", "svc", "c", "w", "r"),
        MigrationAction.SetTopicPermissions("orders", "svc", "amq.topic", "w", "r"),
    ).associateBy { it.actionName }

    /** The keys each codec reads, from the codec sources of M8 (vhost and action excluded). */
    private val expected: Map<String, Set<String>> = mapOf(
        "declareQueue" to setOf("name", "type", "durable", "autoDelete", "arguments"),
        "deleteQueue" to setOf("name"),
        "declareExchange" to setOf("name", "type", "durable", "autoDelete", "internal", "arguments"),
        "deleteExchange" to setOf("name"),
        "declareBinding" to setOf("exchange", "queue", "destination", "routingKey", "destinationType", "arguments"),
        "deleteBinding" to setOf("exchange", "queue", "destination", "routingKey"),
        "declareStream" to setOf("name", "arguments"),
        "deleteStream" to setOf("name"),
        "declarePolicy" to setOf("name", "pattern", "definition", "applyTo", "priority"),
        "declareOperatorPolicy" to setOf("name", "pattern", "definition", "applyTo", "priority"),
        "deletePolicy" to setOf("name"),
        "declareVhost" to setOf("name", "description", "tags", "defaultQueueType"),
        "declareUser" to setOf("name", "passwordHash", "hashingAlgorithm", "tags"),
        "setPermissions" to setOf("user", "configure", "write", "read"),
        "setTopicPermissions" to setOf("user", "exchange", "write", "read"),
    )

    private val neverWritten = mapOf("declareBinding" to setOf("destination"), "deleteBinding" to setOf("destination"))

    private fun entryOf(action: MigrationAction): JsonObject {
        val array = Json.parseToJsonElement(MigrationParser.actionsToJson(listOf(action)))
        return (array as kotlinx.serialization.json.JsonArray).single().jsonObject
    }

    /** A result as a comparable value: a [MigrationAction.DeclareUser] holds a secret with no equals, so it is compared field by field. */
    private fun read(codec: ActionCodec, entry: JsonObject): Any = when (val r = codec.read(entry)) {
        is ActionReadResult.Read -> (r.action as? MigrationAction.DeclareUser)
            ?.let { listOf(it.name, it.passwordHash.expose(), it.hashingAlgorithm, it.tags) } ?: r.action
        is ActionReadResult.Refused -> "refused: ${r.reason}"
    }

    private fun comparable(action: MigrationAction): Any = (action as? MigrationAction.DeclareUser)
        ?.let { listOf(it.name, it.passwordHash.expose(), it.hashingAlgorithm, it.tags) } ?: action

    @Test
    fun `there is a sample for each of the 15 codecs`() {
        assertEquals(15, samples.size)
        assertEquals(expected.keys, samples.keys)
    }

    @Test
    fun `each codec states exactly the keys it reads`() {
        samples.keys.forEach { name ->
            assertEquals(expected.getValue(name), ActionCodecs.forAction(name).keys, "keys of $name")
        }
    }

    @Test
    fun `every key a codec writes is a key it states`() {
        samples.forEach { (name, action) ->
            val written = entryOf(action).keys - setOf("action", "vhost")
            val stated = ActionCodecs.forAction(name).keys
            assertTrue(stated.containsAll(written), "$name writes ${written - stated} that it does not state")
        }
    }

    @Test
    fun `every stated key that is written is read, so a file with it is not left out by mistake`() {
        samples.forEach { (name, action) ->
            val codec = ActionCodecs.forAction(name)
            val entry = entryOf(action)
            val full = read(codec, entry)
            assertEquals(comparable(action), full, "$name reads back what it wrote")
            codec.keys.filter { it in entry.keys }.forEach { key ->
                val without = JsonObject(entry - key)
                assertTrue(read(codec, without) != full, "$name does not read \"$key\", but states it")
            }
            assertTrue(neverWritten.getOrDefault(name, emptySet()).all { it !in entry.keys }, "$name writes a key that is meant to be read only")
        }
    }

    @Test
    fun `a key that is not stated is not read`() {
        samples.forEach { (name, action) ->
            val codec = ActionCodecs.forAction(name)
            val entry = entryOf(action)
            val unstated = listOf("comment", "x", "Name", "queueType", "ttl").filter { it !in codec.keys && it !in entry.keys }
            assertTrue(unstated.isNotEmpty())
            unstated.forEach { extra ->
                assertEquals(read(codec, entry), read(codec, JsonObject(entry + (extra to JsonPrimitive("1")))), "$name reads \"$extra\", which it does not state")
            }
        }
    }

    @Test
    fun `destination is read as the binding's destination and never written`() {
        listOf("declareBinding", "deleteBinding").forEach { name ->
            val codec = ActionCodecs.forAction(name)
            val entry = entryOf(samples.getValue(name))
            assertTrue("destination" !in entry.keys, "$name writes destination")
            val spelled = JsonObject(entry - "queue" + ("destination" to entry.getValue("queue")))
            assertTrue(codec.read(spelled) is ActionReadResult.Read, "$name reads destination")
            assertEquals(read(codec, entry), read(codec, spelled), "$name: destination reads as queue")
        }
    }
}
