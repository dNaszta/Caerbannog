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
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttRequest
import dev.caerbannog.core.planner.FormContext
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.NamingStyle
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
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * M9-07b step 2, the live proof of `@m9-07b @broker` scenario "A device answer published over MQTT lands in the response stream, with its correlation id
 * where the processing can read it" (bound by name). A throwaway current 4.x image (`BrokerImages.CURRENT_4X`) with `rabbitmq_stream` and `rabbitmq_mqtt` ([m9SpikeBroker]); a
 * fresh virtual host per test, unique names, all removed with the virtual host in `@AfterEach`.
 *
 * The plan is what the stream form with the pair tick and the answers tick builds: the two streams and the route `devices/+/answer` into the response
 * stream, made here from the real `StreamForm` (pair) and the real `MqttRouteForm` (the unit test `StreamFormMqttTicksTest` pins that the tick gives exactly that
 * route, so this class compiles and runs before the ticks exist), saved as migration JSON, parsed back and applied by the REAL [MigrationRunner]. The device
 * publishes with the app's own [RabbitMqMqttPublisher] (MQTT 5) to `devices/d42/answer` with the user property `correlation-id` = `c-1`.
 *
 * Counts only, from the management API; no queue message is read by anything. "The request stream holds none" is judged after a positive control: a second
 * answer (`devices/d43/answer`) is published and the test waits (bounded polling) until the response stream counts it, and only then asserts the request
 * stream is still empty. The one extra read is the app's own stream reader (unnamed, non-tracking consumer), as measured by M9-00a S-A6: the user property
 * arrives as the APPLICATION property `correlation-id` (text) and the standard AMQP correlation-id stays empty.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlannerIotRouteTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPlannerIotRouteTest {

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
        val vhost = "m907b-$run-${counter++}"
        assertTrue(api.put("/api/vhosts/$vhost", "{}").ok, "vhost $vhost")
        assertTrue(api.put("/api/permissions/$vhost/guest", """{"configure":".*","write":".*","read":".*"}""").ok, "permissions on $vhost")
        vhosts += vhost
        return vhost
    }

    private fun profile(vhost: String) = ConnectionProfile(
        name = "m9-07b", host = rabbit.host, virtualHost = vhost, username = "guest",
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
        mqttPort = rabbit.getMappedPort(1883), topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    /** MQTT reaches a virtual host other than the default one through the user name "vhost:user". */
    private fun mqttProfile(vhost: String) = profile(vhost).copy(username = "$vhost:guest")

    private fun publisher(vhost: String) = RabbitMqMqttPublisher(mqttProfile(vhost), "guest", BrokerTransport.Plain)

    private fun publish(vhost: String, topic: String, payload: String, headers: List<MqttHeader> = emptyList()) {
        val outcome = runBlocking { publisher(vhost).publish(PublishMqttRequest(topic, payload.encodeToByteArray(), MqttQos.AT_LEAST_ONCE, false, headers)) }
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
        val json = MigrationParser.exportToJson("0001", "m9-07b live", built.actions)
        val file = (MigrationParser.parse(json) as? MigrationValidationResult.Valid)?.migration ?: error("the saved plan is not a valid migration: $json")
        assertEquals(built.actions, file.up, "the file holds the plan's actions as built")
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile(built.vhost)) }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        toClose += connection
        val applied = runBlocking {
            withTimeout(120_000) {
                MigrationRunner(InMemoryMigrationHistoryRepository(), connection.migrationExecutor!!)
                    .applyPending("m9-07b", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = built.vhost)
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
    // ---- the scenario ----

    private fun requestStream() = "device-commands-$run"
    private fun responseStream() = "${requestStream()}-dln"

    @Test
    fun `A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it`() {
        val vhost = newVhost()
        val built = build(
            vhost,
            StreamForm(requestStream(), withResponse = true),
            MqttRouteForm("devices/+/answer", responseStream(), MqttDestinationKind.Stream),
        )
        assertEquals(listOf(requestStream(), responseStream()), built.actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name })
        assertEquals(listOf("amq.topic" to "devices.*.answer"), built.actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.exchange to it.routingKey })
        saveAndApply(built)

        val before = count(vhost, responseStream())
        assertEquals(0, before, "an empty response stream before the answer")
        assertEquals(0, count(vhost, requestStream()), "an empty request stream before the answer")

        publish(vhost, "devices/d42/answer", "answer-1", listOf(MqttHeader("correlation-id", "c-1")))
        awaitCount(vhost, responseStream(), before + 1, "the response stream holds the answer of d42")

        // positive control, then the negative: a second answer is counted in the response stream, and only then is the request stream judged
        publish(vhost, "devices/d43/answer", "answer-2")
        awaitCount(vhost, responseStream(), before + 2, "the control answer of d43 is counted in the response stream")
        assertEquals(0, count(vhost, requestStream()), "an answer must not reach the request stream: only the answers route exists")

        val answer = readStream(vhost, responseStream()).single { it.payload.decodeToString() == "answer-1" }
        assertEquals("c-1", answer.applicationProperties["correlation-id"]?.toString(), "the correlation id is the application property: ${answer.applicationProperties}")
        assertNull(answer.properties["correlationId"], "the standard correlation-id stays empty (S-A6): ${answer.properties}")
        assertEquals(emptyMap<String, Any?>(), answer.properties.filterKeys { it.contains("correlation", ignoreCase = true) })
        assertEquals("devices.d42.answer", answer.routingKey, "x-routing-key is the topic with / as .")
        assertEquals("amq.topic", answer.exchange)
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
