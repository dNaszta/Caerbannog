package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.fakes.PolicyKeyMatrix
import dev.caerbannog.core.migrations.fakes.PolicyKeyMatrix.Target
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * M9-00c spike, part 1 (S-F1a to S-F1d, S-F2a, S-F2b): which policy keys a real RabbitMQ 4.x accepts, which take effect for which target, what a
 * wrong value does, what a policy on an existing stream does to the stream list, and which of several policies applies. Findings:
 * docs/tasks/M9-00c-spike-results.md; the matrices: `PolicyKeyMatrix` (core-testing). A policy is applied by the broker a few seconds after the
 * PUT (about 6 s measured, quorum queues included), so every wait here is a bounded poll (up to 90 s), never a fixed sleep.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikePolicyKeysTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9SpikePolicyKeysTest {

    companion object {
        private val container: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val run = UUID.randomUUID().toString().take(6)
    private fun unique(base: String) = "m9c-$base-$run"
    private val cleanup = M9c.Cleanup(api)

    @AfterEach fun clean() = cleanup.run()

    private fun policyBody(pattern: String, definitionJson: String, applyTo: String = "all", priority: Int = 0) =
        """{"pattern":"$pattern","apply-to":"$applyTo","priority":$priority,"definition":$definitionJson}"""

    private fun putPolicy(name: String, pattern: String, definitionJson: String, applyTo: String = "all", priority: Int = 0, kind: String = "policies"): MgmtApi.Reply {
        val reply = api.put("/api/$kind/%2F/$name", policyBody(pattern, definitionJson, applyTo, priority))
        if (reply.ok) { if (kind == "policies") cleanup.policy(name) else cleanup.operatorPolicy(name) }
        return reply
    }

    private fun declare(name: String, type: String) {
        assertEquals(201, api.put("/api/queues/%2F/${cleanup.queue(name)}", """{"durable":true,"arguments":{"x-queue-type":"$type"}}""").status)
    }

    private fun effective(queue: String): JsonObject? =
        M9c.queueDetail(api, queue)["effective_policy_definition"]?.takeIf { it !is JsonNull }?.jsonObject

    private fun policyOf(queue: String): String? =
        (M9c.queueDetail(api, queue)["policy"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun valueOf(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json)

    @Test
    fun `The floating tag 4-management is a 4_x broker`() = M9c.assertFourX(api)

    @Test
    fun `S-F1a every key is accepted for every apply-to, an unknown key is the one refusal`() {
        val rows = PolicyKeyMatrix.keys.flatMap { key -> PolicyKeyMatrix.applyTos.map { key to it } }
        assertEquals(PolicyKeyMatrix.keys.size * PolicyKeyMatrix.applyTos.size, rows.size, "every key and apply-to pair of the matrix is a row")
        val sent = mutableSetOf<Pair<PolicyKeyMatrix.Key, String>>()
        val wrong = rows.withIndex().mapNotNull { (i, row) ->
            val (key, applyTo) = row
            sent += row
            val status = putPolicy(unique("acc-$i"), "^$run-nomatch$", """{"${key.name}":${key.sampleJson}}""", applyTo).status
            if (status != PolicyKeyMatrix.ACCEPT_STATUS) "${key.name}=${key.sampleJson} apply-to=$applyTo -> $status" else null
        }
        assertEquals(rows.toSet(), sent, "every accept row must be sent")
        assertTrue(wrong.isEmpty(), "the accept matrix (all 201) differs from the broker: $wrong")
        val unknown = api.put("/api/policies/%2F/${unique("unk")}", policyBody("^$run-nomatch$", """{"no-such-key":1}"""))
        assertEquals(PolicyKeyMatrix.UNKNOWN_KEY_STATUS, unknown.status, "positive control: an unknown key is refused: $unknown")
        assertTrue("not recognised policy settings" in unknown.body, "unexpected reason: $unknown")
    }

    @Test
    fun `S-F1b a key takes effect only on the queue types of the effect matrix`() {
        // One queue and one policy per (type, key, value), all created first so the broker applies the whole batch together.
        data class Probe(val target: Target, val key: PolicyKeyMatrix.Key, val queue: String)
        val probes = mutableListOf<Probe>()
        api.put("/api/exchanges/%2F/${cleanup.exchange("dlx")}", """{"type":"direct","durable":true}""")
        listOf(Target.QUORUM, Target.CLASSIC, Target.STREAM).forEach { target ->
            PolicyKeyMatrix.keys.forEachIndexed { i, key ->
                val queue = unique("fb-${target.queueType}-$i")
                declare(queue, target.queueType!!)
                assertEquals(201, putPolicy("p-$queue", "^$queue$", """{"${key.name}":${key.sampleJson}}""").status)
                probes += Probe(target, key, queue)
            }
        }
        assertTrue(PolicyKeyMatrix.effect.values.flatten().all { e -> PolicyKeyMatrix.keys.any { it.name == e } }, "an effect row names a key that is not in the key list")
        assertEquals(Target.entries.filter { it != Target.EXCHANGE }.size * PolicyKeyMatrix.keys.size, probes.size, "every (type, key) row of the effect matrix is probed")
        // The positive control of the "no effect" rows: every row that must take effect shows its key; then a settle window.
        val expectedEffect = probes.filter { PolicyKeyMatrix.takesEffect(it.target, it.key.name) }
        val expectedNoEffect = probes - expectedEffect.toSet()
        M9c.assertStaysAbsent(
            "keys that no queue of that type honours must not show up in effective_policy_definition",
            positive = { expectedEffect.all { effective(it.queue)?.containsKey(it.key.name) == true } },
            stillAbsent = { expectedNoEffect.all { effective(it.queue).isNullOrEmpty() && policyOf(it.queue) == null } },
        )
        expectedEffect.forEach {
            assertEquals(
                valueOf(it.key.sampleJson), effective(it.queue)!![it.key.name],
                "${it.target} ${it.key.name}: effective_policy_definition holds the key with the value sent",
            )
        }
        // overflow: all three values take effect on quorum and classic (rows above carry them), on no stream.
        assertEquals(PolicyKeyMatrix.overflowValues.toSet(), PolicyKeyMatrix.keys.filter { it.name == "overflow" }.map { it.sampleJson.trim('"') }.toSet())
    }

    @Test
    fun `S-F1b on an exchange the policy is set for every key but effective_policy_definition is not shown`() {
        val exchanges = PolicyKeyMatrix.keys.mapIndexed { i, key ->
            val name = cleanup.exchange(unique("fbx-$i"))
            assertEquals(201, api.put("/api/exchanges/%2F/$name", """{"type":"direct","durable":true}""").status)
            assertEquals(201, putPolicy("p-$name", "^$name$", """{"${key.name}":${key.sampleJson}}""").status)
            key to name
        }
        awaitUntil("every exchange shows its policy", 90) { exchanges.all { (_, n) -> api.get("/api/exchanges/%2F/$n").obj["policy"]?.jsonPrimitive?.content == "p-$n" } }
        exchanges.forEach { (key, n) ->
            val body = api.get("/api/exchanges/%2F/$n").obj
            assertFalse(body.containsKey("effective_policy_definition"), "${key.name}: an exchange GET has no effective_policy_definition")
        }
    }

    @Test
    fun `S-F1b a policy with one key the queue type ignores takes no effect at all`() {
        data class Mixed(val queue: String, val type: String, val definition: String, val applies: Boolean)
        val cases = listOf(
            Triple("quorum", """{"max-length":10,"max-age":"7D"}""", false),
            Triple("stream", """{"max-length-bytes":1000,"max-length":10}""", false),
            Triple("classic", """{"max-length":10,"delivery-limit":5}""", false),
            Triple("stream", """{"max-age":"7D","max-length-bytes":1000,"queue-leader-locator":"balanced"}""", true),
            Triple("quorum", """{"max-length":10,"delivery-limit":5}""", true),
        ).mapIndexed { i, (type, definition, applies) ->
            val queue = unique("mx-$i"); declare(queue, type)
            assertEquals(201, putPolicy("p-$queue", "^$queue$", definition).status)
            Mixed(queue, type, definition, applies)
        }
        val control = cases.filter { it.applies }
        M9c.assertStaysAbsent(
            "a policy with a key the type ignores is bound to nothing, even for its valid keys",
            positive = { control.all { policyOf(it.queue) != null } },
            stillAbsent = { cases.filter { !it.applies }.all { policyOf(it.queue) == null && effective(it.queue).isNullOrEmpty() } },
        )
        control.forEach { assertEquals(valueOf(it.definition), effective(it.queue), "${it.type}: all keys of the definition are in effect") }
    }

    @Test
    fun `S-F1c a value of the wrong type is refused and nothing is stored`() {
        val sent = mutableListOf<String>()
        val diffs = PolicyKeyMatrix.wrongTypeRows.mapNotNull { row ->
            sent += row.id
            val name = unique("wt-${row.id}")
            val reply = putPolicy(name, "^$run-nomatch$", """{"${row.key}":${row.valueJson}}""")
            val stored = api.get("/api/policies/%2F/$name").status == 200
            if (reply.status != row.status || stored != row.stored) "${row.id}: status ${reply.status} stored=$stored, table says ${row.status} stored=${row.stored}" else null
        }
        assertEquals(PolicyKeyMatrix.wrongTypeRows.map { it.id }, sent, "every wrong-type row must be sent")
        assertTrue(diffs.isEmpty(), "the wrong-type table differs from the broker: $diffs")
    }

    @Test
    fun `S-F1d retention set by policy on an existing stream does not appear in the stream arguments, so the stream list shows no retention`() {
        val stream = unique("ret")
        val profile = ConnectionProfile(
            name = "m9c", host = container.host, virtualHost = "/", username = container.adminUsername, tls = false,
            streamPort = container.getMappedPort(5552), managementPort = container.getMappedPort(15672),
        )
        StreamEnvironments(profile, container.adminPassword, BrokerTransport.Plain).use { envs ->
            assertEquals(StreamManagementResult.Success, runBlocking { RabbitMqStreamManager(envs).createStream("/", stream) })
            cleanup.queue(stream)
            assertEquals(201, putPolicy("p-$stream", "^$stream$", """{"max-age":"7D","max-length-bytes":1000000}""").status)
            awaitUntil("the policy is applied to the stream", 90) { policyOf(stream) != null }
            val detail = M9c.queueDetail(api, stream)
            assertEquals(valueOf("""{"max-age":"7D","max-length-bytes":1000000}"""), detail["effective_policy_definition"])
            assertFalse(detail.args().containsKey("x-max-age"), "the stream's own arguments carry no x-max-age: ${detail.args()}")
            assertFalse(detail.args().containsKey("x-max-length-bytes"), "nor x-max-length-bytes: ${detail.args()}")
            val summary = (ManagementJson.parseStreamDetail(api.get("/api/queues/%2F/$stream").body) as CatalogResult.Success).value
            assertEquals(null, summary.retention.maxAge, "what the app's stream list reads (arguments.x-max-age) stays empty")
            assertEquals(null, summary.retention.maxLengthBytes)
        }
    }

    // ---- S-F2a: which of several policies applies ---------------------------------------------------------------------------

    @Test
    fun `S-F2a only the policy of the highest priority applies, the keys of the other are lost`() {
        val queue = unique("f2-prio"); declare(queue, "quorum")
        putPolicy("lo-$queue", "^$queue$", """{"max-length":10}""", priority = 1)
        putPolicy("hi-$queue", "^$queue$", """{"message-ttl":60000}""", priority = 5)
        awaitUntil("hi policy applies", 90) { policyOf(queue) == "hi-$queue" }
        assertEquals(valueOf("""{"message-ttl":60000}"""), effective(queue), "no merge: max-length of the lower policy is not in effect")
        // The lower priority policy given the same key changes nothing either.
        val same = unique("f2-key"); declare(same, "classic")
        putPolicy("lo-$same", "^$same$", """{"max-length":10}""", priority = 1)
        putPolicy("hi-$same", "^$same$", """{"max-length":99}""", priority = 5)
        awaitUntil("hi policy applies to the second queue", 90) { policyOf(same) == "hi-$same" }
        assertEquals(valueOf("""{"max-length":99}"""), effective(same))
    }

    @Test
    fun `S-F2a with equal priority exactly one policy applies and nothing is merged, which one is not predictable`() {
        // Disjoint keys, both write orders, several queues: one of the two definitions is in effect, never both. WHICH one is not asserted: 12
        // probes (docs/tasks/M9-00c-spike-results.md) showed it follows neither the write order nor the name order, so a plan cannot predict it.
        val cases = (0 until 6).map { i ->
            val queue = unique("f2-tie$i"); declare(queue, "quorum")
            val (first, second) = if (i % 2 == 0) "zz" to "aa" else "aa" to "zz"
            putPolicy("$first-$queue", "^$queue$", """{"max-length":10}""", priority = 2)
            putPolicy("$second-$queue", "^$queue$", """{"message-ttl":60000}""", priority = 2)
            queue
        }
        awaitUntil("every tie queue is bound to a policy", 90) { cases.all { policyOf(it) != null } }
        cases.forEach { queue ->
            val eff = effective(queue)!!
            assertTrue(
                eff == valueOf("""{"max-length":10}""") || eff == valueOf("""{"message-ttl":60000}"""),
                "equal priority must apply ONE policy, not a merge; $queue got $eff",
            )
            assertTrue(policyOf(queue)!!.endsWith(queue))
        }
    }

    @Test
    fun `S-F2a a policy of higher priority that holds a key the type ignores does not hide the lower one, nor does another apply-to`() {
        val queue = unique("f2-hide"); declare(queue, "quorum")
        putPolicy("lo-$queue", "^$queue$", """{"max-length":10}""", priority = 1)
        putPolicy("hi-$queue", "^$queue$", """{"max-age":"7D"}""", priority = 9)
        val byType = unique("f2-type"); declare(byType, "quorum")
        putPolicy("st-$byType", "^$byType$", """{"max-length":10}""", applyTo = "streams", priority = 9)
        putPolicy("qq-$byType", "^$byType$", """{"message-ttl":60000}""", applyTo = "quorum_queues", priority = 1)
        awaitUntil("both queues get a policy", 90) { policyOf(queue) != null && policyOf(byType) != null }
        assertEquals("lo-$queue", policyOf(queue), "the higher-priority policy with max-age is bound to no quorum queue")
        assertEquals("qq-$byType", policyOf(byType), "apply-to streams never matches a quorum queue, whatever its priority")
    }

    @Test
    fun `S-F2b an operator policy combines with the policy, the smaller number wins and other keys are added`() {
        val a = unique("f2-opa"); declare(a, "quorum")
        putPolicy("pol-$a", "^$a$", """{"max-length":10,"message-ttl":60000}""")
        putPolicy("op-$a", "^$a$", """{"max-length":5}""", kind = "operator-policies")
        val b = unique("f2-opb"); declare(b, "quorum")
        putPolicy("pol-$b", "^$b$", """{"max-length":5}""")
        putPolicy("op-$b", "^$b$", """{"max-length":10}""", kind = "operator-policies")
        val c = unique("f2-opc"); declare(c, "quorum")
        putPolicy("pol-$c", "^$c$", """{"max-length":10}""")
        putPolicy("op-$c", "^$c$", """{"max-length-bytes":1000}""", kind = "operator-policies")
        awaitUntil("all three queues show both policies", 90) {
            listOf(a, b, c).all { policyOf(it) != null && M9c.queueDetail(api, it)["operator_policy"].let { op -> op != null && op !is JsonNull } }
        }
        assertEquals(valueOf("""{"max-length":5,"message-ttl":60000}"""), effective(a), "operator 5 against policy 10: the smaller, plus the policy's other key")
        assertEquals(valueOf("""{"max-length":5}"""), effective(b), "policy 5 against operator 10: still the smaller")
        assertEquals(valueOf("""{"max-length":10,"max-length-bytes":1000}"""), effective(c), "different keys are added")
        assertEquals("op-$a", M9c.queueDetail(api, a)["operator_policy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `The fixtures of S-F1 and S-F2 hold the answers the results file cites`() {
        val two = kotlinx.serialization.json.Json.parseToJsonElement(M9c.fixture("s-f2a-two-policies-and-operator-policy-queue.json")).jsonObject
        assertEquals("fx-hi", two["policy"]!!.jsonPrimitive.content, "the policy of the higher priority applies")
        assertEquals(valueOf("""{"max-length":5,"message-ttl":60000}"""), two["effective_policy_definition"], "operator 5 against the policy's own keys")
        val wrong = kotlinx.serialization.json.Json.parseToJsonElement(M9c.fixture("s-f1-wrong-type-answer.json")).jsonObject
        assertEquals("400", wrong["status"]!!.jsonPrimitive.content)
    }
}
