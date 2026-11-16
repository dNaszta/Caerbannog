package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.planner.PlanCheck
import dev.caerbannog.core.planner.PlanCheckResult
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.StepFinding
import dev.caerbannog.core.planner.TopologyPlan
import io.ktor.client.request.HttpRequestPipeline
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M9-05 live test (`@broker @m9-05`), binds the scenario "The check agrees with a real broker before and after applying" of
 * `20_topology_planner_check.feature` by name: a real RabbitMQ 4 broker (Testcontainers, plugins rabbitmq_stream and rabbitmq_stream_management,
 * plain profile because the per-profile TLS switch is off, D9a), a plan with a direct exchange, a quorum queue with `x-delivery-limit` 5, a binding
 * between them, a stream and a policy. The REAL check (`ManagementDeclareCheck` behind a request recorder) says "Will be created" for every step;
 * the plan is saved and applied by the REAL runner and the REAL executor of the gateway's held connection; the check then says "Already there" for
 * every step. A recorder proves the check sent GET requests of the executor's allow-list only, and nothing under a queue's name.
 * Every name is unique and the test deletes what it created. NOT RUN at step 2 when no container runtime answered (see the task's Status).
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlanCheckTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPlanCheckTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5552) }
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }
    }

    private val run = UUID.randomUUID().toString().take(8)
    private val exchange = "m905-events-$run"
    private val queue = "m905-orders-$run"
    private val stream = "m905-telemetry-$run"
    private val policy = "m905-limits-$run"

    /** Every request the CHECK sent, as "METHOD /path". */
    private val sent = CopyOnWriteArrayList<String>()
    private val toClose = mutableListOf<AutoCloseable>()

    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = rabbit.adminPassword
        override fun delete(profileName: String) = Unit
    }

    private fun profile() = ConnectionProfile(
        name = "m9-05", host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
        topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    private fun recordingCheck(): ManagementDeclareCheck {
        val client = TlsSupport.buildHttpClient(BrokerTransport.Plain, 10_000)
        client.requestPipeline.intercept(HttpRequestPipeline.Before) { sent += "${context.method.value} ${context.url.build().encodedPath}" }
        val http = ManagementHttp(client, rabbit.adminUsername, rabbit.adminPassword).also { toClose += it }
        return ManagementDeclareCheck(ResourceReader(http, ManagementRequests("http://${rabbit.host}:${rabbit.getMappedPort(15672)}", BrokerTransport.Plain)))
    }

    private val actions = listOf(
        MigrationAction.DeclareExchange(exchange, "direct"),
        MigrationAction.DeclareQueue(queue, "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
        MigrationAction.DeclareBinding(exchange, queue, "order.created"),
        MigrationAction.DeclareStream(stream),
        MigrationAction.DeclarePolicy(policy, "^$queue$", mapOf("max-length" to ArgumentValue.Whole(1000))),
    )

    private fun context() = PlannerContext("m9-05", "/", TopologyControlLevel.FULL_CONTROL, true, null)

    private fun check(plan: TopologyPlan): List<StepFinding> = runBlocking {
        withTimeout(60_000) {
            val result = PlanCheck.run(plan, context(), recordingCheck()) { _, _ -> }
            assertInstanceOf(PlanCheckResult.Checked::class.java, result, "the check was refused: $result").let { (it as PlanCheckResult.Checked).steps.map { s -> s.finding } }
        }
    }

    @Test
    fun `The check agrees with a real broker before and after applying`() {
        val plan = TopologyPlan.of("m9-05 live", actions, "/")
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile()) }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        toClose += connection
        try {
            // the held connection of a 4.x broker brings the port, from the same overview read as its executor
            assertTrue(connection.declareCheck != null && connection.migrationExecutor != null, "a 4.x connection must bring a check and an executor")

            assertEquals(List(actions.size) { StepFinding.WillBeCreated }, check(plan), "before applying, nothing is on the broker")

            val file = (MigrationParser.parse(MigrationParser.exportToJson("0001", "m9-05 live", actions)) as MigrationValidationResult.Valid).migration
            val applied = runBlocking {
                withTimeout(120_000) {
                    MigrationRunner(InMemoryMigrationHistoryRepository(), connection.migrationExecutor!!)
                        .applyPending("m9-05", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
                }
            }
            assertInstanceOf(MigrationApplyResult.Success::class.java, applied, "the apply answered $applied")

            assertEquals(List(actions.size) { StepFinding.AlreadyThere }, check(plan), "after applying, every step is already there")

            // the connection's own port agrees with the recorded one
            val own = runBlocking { withTimeout(30_000) { connection.declareCheck!!.find(actions[1]) } }
            assertEquals(dev.caerbannog.core.migrations.BrokerFinding.Same, own)

            val bad = sent.filter { line ->
                val (method, path) = line.split(" ", limit = 2)
                val segments = path.removePrefix("/api/").split('/')
                method != "GET" || !ExecutorPathAllowList.isAllowed("GET", path) || (segments.first() == "queues" && segments.size != 3)
            }
            assertTrue(sent.isNotEmpty() && bad.isEmpty(), "the check sent a request that is not an allowed GET: $bad of $sent")
        } finally {
            toClose.reversed().forEach { runCatching { it.close() } }
            listOf("/api/policies/%2F/$policy", "/api/queues/%2F/$queue", "/api/queues/%2F/$stream", "/api/exchanges/%2F/$exchange").forEach { runCatching { api.delete(it) } }
        }
    }
}
