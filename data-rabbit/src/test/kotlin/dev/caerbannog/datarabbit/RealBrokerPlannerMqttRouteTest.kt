package dev.caerbannog.datarabbit

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.planner.FormContext
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.QueueForm
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * M9-10 step 2, the live proof of `@m9-10` scenario "A message published over MQTT to a planned route reaches a stream and a queue" (bound by name).
 * A throwaway `rabbitmq:4-management` with `rabbitmq_stream` and `rabbitmq_mqtt` ([m9SpikeBroker]); a fresh virtual host per test, unique names, all removed
 * with the virtual host in `@AfterEach`.
 *
 * The plan is built with the REAL forms (`StreamForm`, `QueueForm`, `MqttRouteForm`), saved as migration JSON, parsed back and applied by the REAL
 * [MigrationRunner] over the executor of the real gateway connection. Messages are published through the app's own [RabbitMqMqttPublisher] (MQTT 5).
 *
 * Assertions are COUNTS and never bodies: the stream's and the queue's `messages` from the management API (`GET /api/queues/{vhost}/{name}`, which is
 * what the Topology screen reads); the app's own code reads no queue message and this test does not either. Every "did not arrive" is judged after a
 * positive control: a matching control message is published after the non-matching one, the test waits (bounded polling, a clear failure text) until the
 * control is counted, and only then asserts that the non-matching count did not move. The one extra assertion reads a stream message with the app's own
 * stream reader and checks that it shows the route (`routingKey`, `exchange`) and no date that nobody set.
 *
 * Rows pinned (M9-00a, RabbitMQ 4.4.0): S-A1 (the table), S-A2 (`alarms/#` also receives `alarms`; `+` one level; stream and quorum queue alike), S-A3
 * (a space and a leading `$` carried; the refusals are unit-tested, the broker is not asked), S-A4 (QoS 0, QoS 1 and retained arrive). NOT pinned: a
 * whole-level `*` on the broker, case, topic length above 255 bytes, non-ASCII, QoS 2, a custom MQTT exchange (S-B1, S-B2).
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlannerMqttRouteTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPlannerMqttRouteTest {

    companion object {
        private val rabbit: RabbitMQContainer = m9SpikeBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit); m9AssertBrokerIs4x(api) }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private var counter = 0
    private val vhosts = mutableListOf<String>()
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanUp() {
        toClose.asReversed().forEach { runCatching { it.close() } }; toClose.clear()
        vhosts.forEach { runCatching { api.delete("/api/vhosts/$it") } }; vhosts.clear()
    }

    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "guest"
        override fun delete(profileName: String) = Unit
    }

    /** One fresh virtual host the guest user may configure, write and read; removed with everything in it. */
    private fun newVhost(): String {
        val vhost = "m910-$run-${counter++}"
        assertTrue(api.put("/api/vhosts/$vhost", "{}").ok, "vhost $vhost")
        assertTrue(api.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""").ok, "permissions on $vhost")
        vhosts += vhost
        return vhost
    }

    private fun profile(vhost: String) = ConnectionProfile(
        name = "m9-10", host = rabbit.host, virtualHost = vhost, username = "guest",
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
        mqttPort = rabbit.getMappedPort(1883), topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    /** MQTT reaches a virtual host other than the default one through the user name "vhost:user". */
    private fun mqttProfile(vhost: String) = profile(vhost).copy(username = "$vhost:guest")

    private fun publisher(vhost: String) = RabbitMqMqttPublisher(mqttProfile(vhost), "guest", BrokerTransport.Plain)

    private fun publish(vhost: String, topic: String, payload: String, qos: MqttQos = MqttQos.AT_LEAST_ONCE, retain: Boolean = false) {
        val outcome = runBlocking { publisher(vhost).publish(PublishMqttRequest(topic, payload.encodeToByteArray(), qos, retain)) }
        assertTrue(outcome is PublishOutcome.Success, "publish to $topic was not accepted: $outcome")
    }

    // ---- the plan, built by the real forms ----

    private class Built(val vhost: String, val actions: List<MigrationAction>, val problems: List<String>)

    private fun build(vhost: String, vararg forms: StepForm): Built {
        var plan = TopologyPlan(vhost = vhost)
        val problems = mutableListOf<String>()
        for (form in forms) {
            val ctx = FormContext(plan, NamingStyle.KEBAB)
            problems += form.problems(ctx).map { "$form: ${it.message}" }
            plan = plan.append(form.actions(ctx))
        }
        return Built(vhost, plan.steps.map { it.action }, problems)
    }

    /** Saves the plan as migration JSON, parses it back and applies it with the real runner over the real connection's executor. */
    private fun saveAndApply(built: Built) {
        assertEquals(emptyList<String>(), built.problems, "a form of the plan has a problem")
        val json = MigrationParser.exportToJson("0001", "m9-10 live", built.actions)
        val file = (MigrationParser.parse(json) as? MigrationValidationResult.Valid)?.migration ?: error("the saved plan is not a valid migration: $json")
        assertEquals(built.actions, file.up, "the file holds the plan's actions as built")
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile(built.vhost)) }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        toClose += connection
        val applied = runBlocking {
            withTimeout(120_000) {
                MigrationRunner(InMemoryMigrationHistoryRepository(), connection.migrationExecutor!!)
                    .applyPending("m9-10", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = built.vhost)
            }
        }
        assertInstanceOf(MigrationApplyResult.Success::class.java, applied, "the apply answered $applied")
    }

    // ---- counts, from the management API ----

    private fun count(vhost: String, queue: String): Int {
        val reply = api.get("/api/queues/$vhost/$queue")
        assertTrue(reply.ok, "management does not list $queue in $vhost: $reply")
        return (reply.obj["messages"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    }

    /** Bounded polling (the management statistics move every few seconds) until the queue holds [expected] messages. */
    private fun awaitCount(vhost: String, queue: String, expected: Int, what: String) {
        var last = -1
        awaitUntil("$what: $queue should hold $expected message(s)", timeoutSeconds = 60) { last = count(vhost, queue); last == expected }
        assertEquals(expected, last, what)
    }

    private fun Built.stream(): String = (actions.first { it is MigrationAction.DeclareStream } as MigrationAction.DeclareStream).name
    private fun Built.queue(): String = (actions.first { it is MigrationAction.DeclareQueue } as MigrationAction.DeclareQueue).name

    private fun streamName() = "telemetry-$run"
    private fun queueName() = "alarms-$run"

    private fun twoRoutes(vhost: String) = build(
        vhost,
        StreamForm(streamName()), QueueForm(queueName(), "quorum"),
        MqttRouteForm("sensors/+/temp", streamName(), MqttDestinationKind.Stream),
        MqttRouteForm("alarms/#", queueName(), MqttDestinationKind.Queue),
    )

    // ---- the scenario ----

    @Test
    fun `A message published over MQTT to a planned route reaches a stream and a queue`() {
        val vhost = newVhost()
        val built = twoRoutes(vhost)
        // S-A1: the key the forms wrote
        val keys = built.actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.exchange to it.routingKey }
        assertEquals(listOf("amq.topic" to "sensors.*.temp", "amq.topic" to "alarms.#"), keys)
        saveAndApply(built)
        val stream = built.stream()
        val queue = built.queue()

        // positive: both counts start at 0 (the read itself works), then one message each
        assertEquals(0, count(vhost, stream), "an empty stream before publishing")
        assertEquals(0, count(vhost, queue), "an empty queue before publishing")
        publish(vhost, "sensors/room1/temp", "t1")
        publish(vhost, "alarms/fire/floor2", "a1")
        awaitCount(vhost, stream, 1, "the stream holds the message of sensors/room1/temp")
        awaitCount(vhost, queue, 1, "the queue holds the message of alarms/fire/floor2")

        // negative with controls: sensors/room1/humidity matches neither route
        publish(vhost, "sensors/room1/humidity", "h1")
        publish(vhost, "sensors/room2/temp", "control-stream")
        publish(vhost, "alarms/control", "control-queue")
        awaitCount(vhost, stream, 2, "the control for the stream is counted")
        awaitCount(vhost, queue, 2, "the control for the queue is counted")
        assertEquals(2, count(vhost, stream), "the humidity message must not be in the stream (one message of sensors/room1/temp plus the control)")
        assertEquals(2, count(vhost, queue), "the humidity message must not be in the queue")

        // S-A2: a trailing hash also matches the parent topic, `alarms` itself; the stream route does not
        publish(vhost, "alarms", "parent")
        publish(vhost, "sensors/room3/temp", "control-stream-2")
        awaitCount(vhost, queue, 3, "the queue holds the message of the parent topic alarms")
        awaitCount(vhost, stream, 3, "the second control for the stream is counted")
        assertEquals(3, count(vhost, stream), "the topic alarms must not reach the stream route sensors/+/temp")
    }

    @Test
    fun `A plus route receives a topic of one level and not a topic of two`() {
        val vhost = newVhost()
        val built = build(vhost, StreamForm(streamName()), MqttRouteForm("+", streamName(), MqttDestinationKind.Stream))
        assertEquals("*", (built.actions.last() as MigrationAction.DeclareBinding).routingKey)
        saveAndApply(built)
        publish(vhost, "one", "m1")
        awaitCount(vhost, built.stream(), 1, "a one-level topic reaches the + route")
        publish(vhost, "one/two", "m2")
        publish(vhost, "three", "control")
        awaitCount(vhost, built.stream(), 2, "the control is counted")
        assertEquals(2, count(vhost, built.stream()), "a two-level topic must not reach the + route")
    }

    @Test
    fun `A plain filter reaches a stream and a quorum queue alike`() {
        val vhost = newVhost()
        val built = build(
            vhost,
            StreamForm(streamName()), QueueForm(queueName(), "quorum"),
            MqttRouteForm("a/b/c", streamName(), MqttDestinationKind.Stream),
            MqttRouteForm("a/b/c", queueName(), MqttDestinationKind.Queue),
        )
        saveAndApply(built)
        publish(vhost, "a/b/c", "m1")
        awaitCount(vhost, built.stream(), 1, "a stream matches a/b/c")
        awaitCount(vhost, built.queue(), 1, "a quorum queue matches a/b/c")
        publish(vhost, "a/b", "no")
        publish(vhost, "a/b/c", "control")
        awaitCount(vhost, built.stream(), 2, "the control is counted in the stream")
        awaitCount(vhost, built.queue(), 2, "the control is counted in the queue")
        assertEquals(2, count(vhost, built.stream()), "a/b must not reach the stream")
        assertEquals(2, count(vhost, built.queue()), "a/b must not reach the queue")
    }

    @Test
    fun `A space and a leading dollar are carried and arrive`() {
        val vhost = newVhost()
        val built = build(
            vhost,
            StreamForm(streamName()), QueueForm(queueName(), "quorum"),
            MqttRouteForm("sensors/a b/temp", streamName(), MqttDestinationKind.Stream),
            MqttRouteForm("\$SYS/#", queueName(), MqttDestinationKind.Queue),
        )
        assertEquals(listOf("sensors.a b.temp", "\$SYS.#"), built.actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.routingKey })
        saveAndApply(built)
        publish(vhost, "sensors/a b/temp", "space")
        publish(vhost, "\$SYS/x", "dollar")
        awaitCount(vhost, built.stream(), 1, "the topic with a space reaches its route")
        awaitCount(vhost, built.queue(), 1, "the \$SYS topic reaches the \$SYS/# route")
    }

    @Test
    fun `QoS 0, QoS 1 and a retained publish all arrive`() {
        val vhost = newVhost()
        val built = build(vhost, StreamForm(streamName()), MqttRouteForm("qos/#", streamName(), MqttDestinationKind.Stream))
        saveAndApply(built)
        publish(vhost, "qos/zero", "q0", MqttQos.AT_MOST_ONCE)
        awaitCount(vhost, built.stream(), 1, "a QoS 0 publish arrives")
        publish(vhost, "qos/one", "q1", MqttQos.AT_LEAST_ONCE)
        awaitCount(vhost, built.stream(), 2, "a QoS 1 publish arrives")
        publish(vhost, "qos/retained", "r", MqttQos.AT_LEAST_ONCE, retain = true)
        awaitCount(vhost, built.stream(), 3, "a retained publish arrives")
        // positive control, then the exact total: nothing else arrived
        publish(vhost, "qos/control", "control")
        awaitCount(vhost, built.stream(), 4, "the control is counted")
        assertEquals(4, count(vhost, built.stream()), "exactly the four published messages")
    }

    @Test
    fun `The stream message shows the route and no date nobody set`() {
        val vhost = newVhost()
        val built = build(vhost, StreamForm(streamName()), MqttRouteForm("sensors/+/temp", streamName(), MqttDestinationKind.Stream))
        saveAndApply(built)
        publish(vhost, "sensors/room1/temp", "plain-payload")
        awaitCount(vhost, built.stream(), 1, "the message reached the stream")
        val messages = readStream(vhost, built.stream())
        val message = messages.single()
        assertEquals("plain-payload", message.payload.decodeToString())
        assertEquals("sensors.room1.temp", message.routingKey, "x-routing-key is the topic with / as .")
        assertEquals("amq.topic", message.exchange, "x-exchange is the exchange")
        assertNull(message.properties["creation-time"], "no creation-time was set by the publisher: ${message.properties}")
        assertNull(message.properties["absolute-expiry-time"], "no absolute-expiry-time was set by the publisher: ${message.properties}")
    }

    /** The app's own stream reader (unnamed, non-tracking consumer) on the virtual host of the test. */
    private fun readStream(vhost: String, stream: String): List<StreamMessage> = runBlocking {
        val profile = profile(vhost)
        val transport = BrokerTransport.from(profile)
        StreamEnvironments(profile, "guest", transport).use { envs ->
            val http = ManagementHttp(TlsSupport.buildHttpClient(transport, 15_000), "guest", "guest")
            http.use {
                val reader = RabbitMqStreamMessageReader(envs, ManagementStreamCatalog(http, ManagementRequests.forProfile(profile)))
                val result = reader.readPage(vhost, stream, BrowseStart.First, pageSize = 100)
                assertInstanceOf(BrowseResult.Success::class.java, result, "got $result").messages
            }
        }
    }
}
