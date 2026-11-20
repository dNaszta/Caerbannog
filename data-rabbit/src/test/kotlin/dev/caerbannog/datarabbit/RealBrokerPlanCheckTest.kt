package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import kotlinx.serialization.json.JsonArray
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
        private val rabbit: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")
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

    // ---- M9-05b -----------------------------------------------------------------------------------------------------------------

    private fun text(v: String) = ArgumentValue.Text(v)

    /** What the broker holds for a queue, without the statistics that move: this is what "unchanged" compares. */
    private fun queueState(name: String): String = api.get("/api/queues/%2F/$name").obj.let { o ->
        listOf("type", "durable", "auto_delete", "arguments").joinToString("|") { "$it=${o[it]}" }
    }

    private fun declare(action: MigrationAction.DeclareQueue) {
        val request = checkNotNull(MigrationRequests.requestFor(action))
        val reply = api.put(request.path, request.body.toString())
        assertEquals(201, reply.status, "the queue ${action.name} must be created first: $reply")
    }

    private fun applyOne(connection: dev.caerbannog.core.connections.HeldConnection, action: MigrationAction): MigrationApplyResult {
        val file = (MigrationParser.parse(MigrationParser.exportToJson("0001", "m9-05b", listOf(action))) as MigrationValidationResult.Valid).migration
        return runBlocking {
            withTimeout(120_000) {
                MigrationRunner(InMemoryMigrationHistoryRepository(), connection.migrationExecutor!!)
                    .applyPending("m9-05", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
            }
        }
    }

    /** One row of the outline "What the check calls different is what a real broker refuses". */
    private class Row(
        val onBroker: Map<String, ArgumentValue>, val inPlan: Map<String, ArgumentValue>, val shown: StepFinding,
        /** null: the apply succeeds and changes nothing; otherwise a text the failure reason must contain ("" = any failure). */
        val applyStopsWith: String?,
    )

    @Test
    fun `What the check calls different is what a real broker refuses`() {
        val rows = listOf(
            Row(mapOf("x-delivery-limit" to ArgumentValue.Whole(3)), mapOf("x-delivery-limit" to ArgumentValue.Whole(5)), StepFinding.Different, "exists with different settings"),
            Row(mapOf("x-delivery-limit" to ArgumentValue.Whole(3)), emptyMap(), StepFinding.Different, "exists with different settings"),
            Row(mapOf("x-delivery-limit" to ArgumentValue.Whole(3)), mapOf("x-delivery-limit" to ArgumentValue.Whole(3)), StepFinding.AlreadyThere, null),
            // The scenario names "x-max-priority" 3 here, but a real 4.4.0 broker REFUSES the FIRST declare of it on a quorum queue
            // (400 invalid arg 'x-max-priority'; measured 2026-11-16), so that row cannot be set up. `x-foo` is the one argument a quorum queue
            // accepts, stores and does not compare (reported to the architect: the feature row needs the architect's word).
            Row(mapOf("x-foo" to ArgumentValue.Whole(3)), emptyMap(), StepFinding.AlreadyThere, null),
            Row(emptyMap(), mapOf("x-max-priority" to ArgumentValue.Whole(3)), StepFinding.Different, ""),
        )
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile()) }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        toClose += connection
        val created = mutableListOf<String>()
        val problems = mutableListOf<String>()
        try {
            rows.forEachIndexed { index, row ->
                val name = "m905b-orders-$run-$index"
                created += name
                declare(MigrationAction.DeclareQueue(name, "quorum", arguments = row.onBroker))
                val before = queueState(name)
                val declared = MigrationAction.DeclareQueue(name, "quorum", arguments = row.inPlan)
                val shown = check(TopologyPlan.of("m9-05b row ${index + 1}", listOf(declared), "/")).single()
                if (shown != row.shown) problems += "row ${index + 1}: the check showed $shown, expected ${row.shown}"
                val applied = applyOne(connection, declared)
                when {
                    row.applyStopsWith == null -> if (applied !is MigrationApplyResult.Success) problems += "row ${index + 1}: the apply should succeed, it answered $applied"
                    applied !is MigrationApplyResult.ActionFailed -> problems += "row ${index + 1}: the apply should stop at the step, it answered $applied"
                    applied.index != 1 || !applied.reason.contains(row.applyStopsWith) ->
                        problems += "row ${index + 1}: the apply stopped at step ${applied.index} with [${applied.reason}], expected step 1 with [${row.applyStopsWith}]"
                }
                if (queueState(name) != before) problems += "row ${index + 1}: the queue on the broker changed: before [$before], after [${queueState(name)}]"
            }
        } finally {
            toClose.reversed().forEach { runCatching { it.close() } }
            created.forEach { runCatching { api.delete("/api/queues/%2F/$it") } }
        }
        assertTrue(problems.isEmpty(), "what the check calls different is not what the broker refuses:\n${problems.joinToString("\n")}")
    }

    /** What a real broker, a classic, a quorum and a stream-type queue made by the product's builder, say about "Already there" right after the apply. */
    @Test
    fun `A queue made by the builder reads already there after the apply, for every queue type`() {
        val opened = runBlocking { RabbitMqConnectionGateway(secrets).open(profile()) }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
        toClose += connection
        val made = listOf(
            MigrationAction.DeclareQueue("m905b-classic-$run", "classic"),
            MigrationAction.DeclareQueue("m905b-classic-args-$run", "classic", arguments = mapOf("x-max-length" to ArgumentValue.Whole(10))),
            MigrationAction.DeclareQueue("m905b-quorum-$run", "quorum"),
            MigrationAction.DeclareQueue("m905b-quorum-args-$run", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5))),
            MigrationAction.DeclareQueue("m905b-streamq-$run", "stream"),
            MigrationAction.DeclareQueue("m905b-streamq-args-$run", "stream", arguments = mapOf("x-max-length-bytes" to ArgumentValue.Whole(1000))),
            MigrationAction.DeclareStream("m905b-stream-$run"),
        )
        val names = made.map { (it as? MigrationAction.DeclareQueue)?.name ?: (it as MigrationAction.DeclareStream).name }
        try {
            val plan = TopologyPlan.of("m9-05b builder", made, "/")
            assertEquals(List(made.size) { StepFinding.WillBeCreated }, check(plan), "before applying, nothing is on the broker")
            val file = (MigrationParser.parse(MigrationParser.exportToJson("0001", "m9-05b builder", made)) as MigrationValidationResult.Valid).migration
            val applied = runBlocking {
                withTimeout(120_000) {
                    MigrationRunner(InMemoryMigrationHistoryRepository(), connection.migrationExecutor!!)
                        .applyPending("m9-05", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
                }
            }
            assertInstanceOf(MigrationApplyResult.Success::class.java, applied, "the apply answered $applied")
            val found = check(plan)
            // STOP condition of the brief: a broker default that is an honoured argument (for example x-queue-version) turns these into Different
            val held = names.joinToString { "$it=" + api.get("/api/queues/%2F/$it").obj["arguments"] }
            assertEquals(List(made.size) { StepFinding.AlreadyThere }, check(plan), "STOP AND REPORT TO THE ARCHITECT: a queue made by the builder is not 'Already there' after the apply; the broker holds $held")
        } finally {
            toClose.reversed().forEach { runCatching { it.close() } }
            names.forEach { runCatching { api.delete("/api/queues/%2F/$it") } }
        }
    }

    @Test
    fun `A name that the management API answers with a list is not read as a queue`() {
        val names = listOf(".", "..")
        val actions = names.flatMap { listOf(MigrationAction.DeclareQueue(it), MigrationAction.DeclareExchange(it)) }
        val raw = names.flatMap { n -> listOf("queues", "exchanges").map { kind -> "$kind/$n" to api.get("/api/$kind/%2F/${MigrationRequests.encode(n)}") } }
        raw.forEach { (what, reply) -> assertTrue(reply.status == 200 && reply.json is JsonArray, "the premise: GET $what answers a 200 list, it answered $reply") }
        val found = check(TopologyPlan.of("m9-05b dots", actions, "/"))
        assertEquals(List(actions.size) { StepFinding.NotChecked(ExecutorReasons.NO_ANSWER) }, found, "every step must read 'Could not be checked'")
        val bad = sent.filter { it.substringBefore(' ') != "GET" }
        assertTrue(sent.isNotEmpty() && bad.isEmpty(), "the check sent something other than GET: $bad")
    }

    /** `...` was not measured by the spike: this does not claim an answer, it asks the broker and requires the check to agree with it. */
    @Test
    fun `The three dots name is read the way the broker answers it`() {
        val reply = api.get("/api/queues/%2F/${MigrationRequests.encode("...")}")
        val expected: BrokerFinding = when {
            reply.status == 404 -> BrokerFinding.Absent
            reply.status == 200 && reply.json is JsonArray -> BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER)
            else -> fail("an answer the check has no row for: $reply")
        }
        println("M9-05b: GET queue named ... answers ${reply.status} ${reply.body.take(60)}")
        val found = runBlocking { withTimeout(30_000) { recordingCheck().find(MigrationAction.DeclareQueue("...")) } }
        assertEquals(expected, found, "the broker answered ${reply.status} ${reply.body.take(80)} for the name ...")
    }
}
