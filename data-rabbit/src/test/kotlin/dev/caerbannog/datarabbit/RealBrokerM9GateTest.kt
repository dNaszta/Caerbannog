package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.migrations.MigrationFolderPorts
import dev.caerbannog.core.migrations.MigrationFolderStore
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.planner.CheckState
import dev.caerbannog.core.planner.DeadLetterForm
import dev.caerbannog.core.planner.ExchangeForm
import dev.caerbannog.core.planner.BindingForm
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.KnownTopologyReader
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.MqttRouteForm
import dev.caerbannog.core.planner.PlanCheck
import dev.caerbannog.core.planner.PlanCheckResult
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.StepCheck
import dev.caerbannog.core.planner.StepFinding
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.planner.StreamForm
import dev.caerbannog.core.planner.TopologyPlan
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamListRequest
import io.ktor.client.request.HttpRequestPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M9-13, the M9 gate (`@gate-M9 @broker @m9-13`), binds the scenario "A plan built in the planner does on a real broker what its steps say" of
 * `21_topology_planner_recipes.feature`: a real RabbitMQ 4 broker (Testcontainers, plugins stream, stream management and MQTT, plain profile
 * because the per-profile TLS switch is off, D9a). The plan is built through the REAL `PlannerViewModel` and its forms, as a user would (a
 * direct exchange and a binding to the existing quorum queue `orders`; the dead-lettering recipe of `orders`; the stream `telemetry` with its
 * response stream and a retention; the MQTT route `sensors/+/temp` to `telemetry`), checked with the REAL `ManagementDeclareCheck` behind a
 * request recorder, saved into a temp folder by the REAL `FolderMigrationFileRepository`, applied by the REAL `MigrationRunner` and
 * `ManagementTopologyMigrationExecutor` (behind a recorder too), checked again, and then proved with messages: an MQTT publish arrives in
 * `telemetry` and only the matching topic does; a message that expires in `orders` arrives in `orders-dlq` (counted by the management API, its
 * content seen only by the requeue peek); `telemetry-dln` exists and is empty; nothing that existed before was deleted.
 *
 * Facts it relies on are measured ones only: the policy takes about 6 s to show on a queue (a bounded poll of up to 90 s, never a fixed delay),
 * the key `sensors.*.temp` (M9-00a), a message that expires arrives with `x-first-death-reason` `expired` (S-C1), the at-most-once recipe. It does
 * NOT rely on the claims `M9-08e` and `M9-09b` leave unmeasured (policy over argument precedence, at-least-once with reject-publish-dlx, the
 * live proof of the own-argument read). The statistics lag up to 5 s: every count is a bounded poll. The container is the clean-up.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*M9Gate*'`.
 */
@Tag("broker")
@Tag("gate-M9")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9GateTest {

    companion object {
        private val rabbit: RabbitMQContainer = m9SpikeBroker()
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { rabbit.start(); api = MgmtApi(rabbit) }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }

        private const val CONNECTION = "m9-13"
        private const val POLICY_WAIT_SECONDS = 90L
    }

    private val sentByCheck = CopyOnWriteArrayList<String>()
    private val sentByCheckAgain = CopyOnWriteArrayList<String>()
    private val sentByApply = CopyOnWriteArrayList<String>()
    private val toClose = mutableListOf<AutoCloseable>()

    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = rabbit.adminPassword
        override fun delete(profileName: String) = Unit
    }

    private fun profile() = ConnectionProfile(
        name = CONNECTION, host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false,
        topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    private fun requests() = ManagementRequests("http://${rabbit.host}:${rabbit.getMappedPort(15672)}", BrokerTransport.Plain)

    /** The app's own management HTTP, with every request it sends noted as "METHOD /path" in [into]. */
    private fun recordingHttp(into: MutableList<String>): ManagementHttp {
        val client = TlsSupport.buildHttpClient(BrokerTransport.Plain, 10_000)
        client.requestPipeline.intercept(HttpRequestPipeline.Before) { into += "${context.method.value} ${context.url.build().encodedPath}" }
        return ManagementHttp(client, rabbit.adminUsername, rabbit.adminPassword).also { toClose += it }
    }

    private fun recordingCheck(into: MutableList<String>): Pair<ManagementDeclareCheck, ManagementBrokerPolicyReader> {
        val http = recordingHttp(into)
        return ManagementDeclareCheck(ResourceReader(http, requests())) to ManagementBrokerPolicyReader(ResourceReader(http, requests()))
    }

    private fun context() = PlannerContext(CONNECTION, "/", TopologyControlLevel.FULL_CONTROL, true, null)

    /** The planner's read of the broker's names, as `CatalogKnownTopologyReader` of the app does it (the same catalogs of the held connection, list calls only). */
    private class HeldKnownTopologyReader(private val connection: HeldConnection) : KnownTopologyReader {
        override suspend fun read(vhost: String): KnownTopology? {
            val exchanges = (connection.topology.exchanges(vhost) as? CatalogResult.Success)?.value ?: return null
            val queues = linkedMapOf<String, KnownQueue>()
            var page = 1
            do {
                val result = (connection.topology.queues(vhost, page) as? CatalogResult.Success)?.value ?: return null
                result.rows.forEach { queues[it.name] = KnownQueue(it.type, it.policy) }
                page++
            } while (page <= result.pageCount)
            val streams = mutableSetOf<String>()
            page = 1
            do {
                val result = (connection.catalog.listStreams(StreamListRequest(vhost = vhost, page = page, pageSize = 500)) as? CatalogResult.Success)?.value ?: return null
                streams += result.rows.map { it.name }
                page++
            } while (page <= result.pageCount)
            return KnownTopology(
                exchanges = exchanges.filterNot { it.isDefault }.associate { it.name to it.type },
                queues = queues.keys, streams = streams,
                queueDetails = queues + streams.associateWith { KnownQueue("stream", null) },
            )
        }
    }

    // ---- small helpers: bounded polls on the planner's state and on the broker ----

    private fun queueRow(name: String): JsonObject? = api.get("/api/queues/%2F/$name").takeIf { it.ok }?.obj

    private fun count(queue: String): Int = queueRow(queue)?.get("messages")?.jsonPrimitive?.intOrNull ?: 0

    private fun policyOn(queue: String): String? = (queueRow(queue)?.get("policy") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun names(path: String): Set<String> = api.get(path).arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    private fun describe(checks: List<StepCheck>, plan: TopologyPlan): String =
        checks.joinToString("; ") { c -> "step ${c.stepId} [${plan.steps.first { it.id == c.stepId }.title}] -> ${c.finding}${c.blocked?.let { " (blocked: $it)" } ?: ""}" }

    private fun listFolder(folder: Path): List<String> = Files.list(folder).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `A plan built in the planner does on a real broker what its steps say`() {
        val folder = Files.createTempDirectory("m9-13-migrations")
        val draftDir = Files.createTempDirectory("m9-13-draft")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        try {
            // ---- Given: a real 4.x broker with the MQTT plugin and a quorum queue "orders" that already exists (test code, not the app),
            // plus a bystander of every kind the gate could damage: the "nothing was deleted" check compares with it.
            assertTrue(api.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content.startsWith("4."), "rabbitmq:4-management must be a 4.x broker")
            assertEquals(201, api.declareQueue("orders", """{"x-queue-type":"quorum"}""").status, "orders is created before the plan")
            assertEquals(201, api.declareQueue("bystander", """{"x-queue-type":"quorum"}""").status, "the bystander queue")
            assertEquals(201, api.put("/api/exchanges/%2F/bystander-x", """{"type":"direct","durable":true}""").status, "the bystander exchange")
            assertEquals(201, api.put("/api/policies/%2F/bystander-limit", """{"pattern":"^bystander$","apply-to":"quorum_queues","priority":0,"definition":{"max-length":100}}""").status, "the bystander policy")
            val queuesBefore = names("/api/queues/%2F")
            val exchangesBefore = names("/api/exchanges/%2F")
            val policiesBefore = names("/api/policies/%2F")
            val bystanderPolicyBefore = api.get("/api/policies/%2F/bystander-limit").obj["definition"]

            val connection = (runBlocking { RabbitMqConnectionGateway(secrets).open(profile()) }.also {
                assertInstanceOf(OpenResult.Opened::class.java, it, "the broker answered $it")
            } as OpenResult.Opened).connection
            toClose += connection
            assertTrue(connection.migrationExecutor != null && connection.declareCheck != null, "a 4.x connection must bring an executor and a check")

            // ---- And: the plan, built through the planner as a user would (the same wizard calls as the screen)
            val (check, policies) = recordingCheck(sentByCheck)
            val store = object : MigrationFolderStore { override var folder: String? = folder.toString() }
            val ports = MigrationFolderPorts(
                store, MigrationFolderChooser { null }, { FolderMigrationFileRepository(Path.of(it)) }, InMemoryMigrationHistoryRepository(),
            )
            val vm = PlannerViewModel(
                draftStore = FilePlanDraftStore(draftDir.resolve("planner-draft.json"), dev.caerbannog.core.migrations.NowProvider { 1_700_000_000_000L }),
                folderPorts = ports, settings = FakeAppSettings(), context = context(), scope = scope, descriptionDebounceMillis = 0,
                reader = HeldKnownTopologyReader(connection), declareCheck = check, clock = RefreshClock { 1_700_000_000_000L }, policies = policies,
            )
            fun state() = vm.state.value
            awaitUntil("the planner has read the broker's names (orders is a known quorum queue)", 60) {
                state().isLoaded && !state().knownPending && state().known?.queueDetails?.get("orders")?.type == "quorum"
            }

            fun add(kind: String, form: StepForm, adds: Int) {
                val before = state().plan.steps.size
                vm.openWizard(); vm.chooseKind(kind); vm.updateForm(form); vm.next(); vm.addToPlan()
                awaitUntil("the wizard added $adds step(s) for the $kind form $form (problems: ${(state().wizard as? dev.caerbannog.core.planner.WizardState.Details)?.problems})", 30) {
                    state().plan.steps.size == before + adds
                }
            }
            add("Exchange", ExchangeForm("events", "direct"), 1)
            add("Binding", BindingForm(exchange = "events", destination = "orders", routingKey = "order.created"), 1)
            add(dev.caerbannog.core.planner.PlannerTexts.DEAD_LETTER_KIND, DeadLetterForm(queue = "orders"), 4)
            add("Stream", StreamForm(name = "telemetry", withResponse = true, keepFor = "7D", keepAtMost = "1000000"), 3)
            add("MQTT route", MqttRouteForm("sensors/+/temp", "telemetry", MqttDestinationKind.Stream), 1)
            val plan = state().plan
            val built = plan.steps.map { it.action }
            // The words of the plan: what each wizard form built is what the steps say (names follow the naming style, kebab-case by default).
            val declared = built.mapNotNull {
                when (it) {
                    is MigrationAction.DeclareExchange -> it.name
                    is MigrationAction.DeclareQueue -> it.name
                    is MigrationAction.DeclareStream -> it.name
                    is MigrationAction.DeclarePolicy -> it.name
                    else -> null
                }
            }
            assertEquals(
                listOf("events", "orders-dlx", "orders-dlq", "orders-dlx", "telemetry", "telemetry-dln", "telemetry-retention"), declared,
                "the names the wizard forms built, in plan order; the plan is: ${plan.steps.map { it.title }}",
            )
            val deadLetterPolicy = built.filterIsInstance<MigrationAction.DeclarePolicy>().single { it.name == "orders-dlx" }
            assertEquals("^orders$", deadLetterPolicy.pattern, "the dead-letter policy binds exactly the source queue")
            assertEquals("quorum_queues", deadLetterPolicy.applyTo, "orders is a quorum queue the planner read from the broker")
            assertEquals(mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx")), deadLetterPolicy.definition, "at most once: dead-letter-exchange only")
            assertTrue(built.none { it is MigrationAction.DeclareQueue && it.name == "orders" }, "the plan never declares the existing queue orders")
            assertEquals(10, plan.steps.size, "steps of the plan: ${plan.steps.map { it.title }}")

            // ---- When the plan is checked / Then every new step "Will be created" and the check sent GET requests only
            vm.check()
            awaitUntil("the check finished", 60) { state().check is CheckState.Done }
            val first = (state().check as CheckState.Done).results
            assertEquals(plan.steps.map { it.id }, first.map { it.stepId }, "one result per step")
            assertEquals(List(plan.steps.size) { StepFinding.WillBeCreated }, first.map { it.finding }, "before applying nothing of the plan is on the broker: ${describe(first, plan)}")
            assertTrue(first.all { it.blocked == null }, "no step is blocked: ${describe(first, plan)}")
            assertOnlyReads("the check", sentByCheck)

            // ---- When the plan is saved as a migration
            vm.saveAsMigration()
            awaitUntil("the plan was saved (error: ${state().errorMessage}, gate: ${state().saveGate})", 30) { state().saved != null }
            val saved = state().saved!!
            assertEquals(listOf(saved.fileName), listFolder(folder), "exactly one file in the migrations folder")
            val raw = Files.readString(folder.resolve(saved.fileName))
            val parsed = assertInstanceOf(MigrationValidationResult.Valid::class.java, MigrationParser.parse(raw), "the saved file must parse: $raw").migration
            assertEquals(built, parsed.up, "the file holds the steps of the plan, in order")
            assertTrue(parsed.up.none { it.isBrokerLevel }, "the file holds no user and no permission step")
            assertTrue(!raw.contains("hash", ignoreCase = true) && !raw.contains("password", ignoreCase = true), "the file holds no password and no hash: $raw")

            // ---- And applied by the existing migration runner and executor (the executor behind a recorder)
            val http = recordingHttp(sentByApply)
            val executor = ManagementTopologyMigrationExecutor(http, requests(), connection.manager) { delay(it) }
            val history = InMemoryMigrationHistoryRepository()
            val applied = runBlocking {
                withTimeout(180_000) {
                    MigrationRunner(history, executor).applyPending(CONNECTION, listOf(parsed), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
                }
            }
            assertEquals(MigrationApplyResult.Success(listOf(parsed.version)), applied, "the runner applied the saved file")
            assertEquals(setOf(parsed.version), runBlocking { history.getAppliedMigrations(CONNECTION) }.keys, "the migration is recorded as applied")
            assertTrue(sentByApply.none { it.startsWith("DELETE") }, "the apply deleted nothing: $sentByApply")
            assertTrue(sentByApply.none { it.endsWith("/get") || it.contains("/publish") }, "the apply read or published no queue message: $sentByApply")

            // ---- Then the check shows "Already there" for every step (the plan is the one that was saved: the planner reset its own after the save)
            val (checkAgain, policiesAgain) = recordingCheck(sentByCheckAgain)
            val known = runBlocking { HeldKnownTopologyReader(connection).read("/") }
            val second = runBlocking {
                withTimeout(60_000) { PlanCheck.run(plan, context(), checkAgain, policiesAgain, known ?: KnownTopology.None) { _, _ -> } }
            }
            val again = assertInstanceOf(PlanCheckResult.Checked::class.java, second, "the second check was refused: $second").steps
            assertEquals(List(plan.steps.size) { StepFinding.AlreadyThere }, again.map { it.finding }, "after applying every step is already there: ${describe(again, plan)}")
            assertOnlyReads("the second check", sentByCheckAgain)

            // ---- the policies show on their targets after about 6 s: bounded polls, never a fixed delay
            awaitUntil("the dead-letter policy orders-dlx applies to the queue orders (now: ${policyOn("orders")})", POLICY_WAIT_SECONDS) { policyOn("orders") == "orders-dlx" }
            awaitUntil("the retention policy applies to the stream telemetry (now: ${policyOn("telemetry")})", POLICY_WAIT_SECONDS) { policyOn("telemetry") == "telemetry-retention" }
            awaitUntil("the retention policy applies to the response stream telemetry-dln (now: ${policyOn("telemetry-dln")})", POLICY_WAIT_SECONDS) { policyOn("telemetry-dln") == "telemetry-retention" }
            val retention = queueRow("telemetry")!!["effective_policy_definition"]?.jsonObject
            assertEquals("7D", retention?.get("max-age")?.jsonPrimitive?.content, "the retention reached the stream: $retention")
            assertEquals(1_000_000, retention?.get("max-length-bytes")?.jsonPrimitive?.intOrNull, "the byte limit reached the stream: $retention")
            assertEquals("bystander-limit", policyOn("bystander"), "the bystander policy still applies to its own queue only")

            // ---- And a message published over MQTT to sensors/room1/temp arrives in the stream telemetry (and only a matching topic does)
            M9SpikeMqttClient(rabbit, "m9-13-pub").connect().use { pub ->
                // Topics the filter must NOT match (M9-00a S-A2): another last level, two levels in the middle, no level in the middle.
                assertEquals(null, pub.publish("sensors/room1/hum", "NO-MATCH-OTHER-LEAF"), "publish of a topic with another last level")
                assertEquals(null, pub.publish("sensors/a/b/temp", "NO-MATCH-TWO-LEVELS"), "publish of a topic with two middle levels")
                assertEquals(null, pub.publish("sensors/temp", "NO-MATCH-NO-LEVEL"), "publish of a topic with no middle level")
                assertEquals(null, pub.publish("sensors/room1/temp", "MATCH"), "publish to sensors/room1/temp")
                assertEquals(null, pub.publish("sensors/room2/temp", "CONTROL"), "publish of the control message (another room, same route)")
            }
            val arrived = m9StreamUntilControl(rabbit, "telemetry", "CONTROL").map { it.payload }
            assertEquals(listOf("MATCH", "CONTROL"), arrived, "the stream telemetry holds the messages whose topic the route sensors/+/temp matches, and only those")
            awaitUntil("the management API counts both messages in telemetry (now ${count("telemetry")})", 30) { count("telemetry") == 2 }

            // ---- And a message that expires in orders arrives in the dead-letter queue
            val sentToOrders = api.publish("orders", "EXPIRES", """{"delivery_mode":2,"expiration":"1000"}""")
            assertTrue(sentToOrders.ok && sentToOrders.obj["routed"]?.toString() == "true", "the message was routed to orders: $sentToOrders")
            awaitUntil("the expired message arrives in orders-dlq (count now ${count("orders-dlq")}, orders ${count("orders")})", 60) { count("orders-dlq") == 1 }
            val dead = m9Peek(api, "orders-dlq").single()
            assertEquals("EXPIRES", dead.payload, "the dead letter is the message that expired")
            val reason = dead.properties["headers"]?.jsonObject?.get("x-first-death-reason")?.jsonPrimitive?.content
            assertEquals("expired", reason, "the death reason the broker recorded: ${dead.properties}")
            awaitUntil("orders is empty again (now ${count("orders")})", 30) { count("orders") == 0 }

            // ---- And the stream telemetry-dln exists and is empty; nothing was deleted
            val dln = queueRow("telemetry-dln")
            assertNotNull(dln, "the response stream telemetry-dln exists")
            assertEquals("stream", dln!!["type"]?.jsonPrimitive?.content, "telemetry-dln is a stream: $dln")
            assertEquals(0, count("telemetry-dln"), "the response stream is empty: nothing was routed into it")
            assertTrue(names("/api/queues/%2F").containsAll(queuesBefore), "queues that existed before still exist: before $queuesBefore, now ${names("/api/queues/%2F")}")
            assertTrue(names("/api/exchanges/%2F").containsAll(exchangesBefore), "exchanges that existed before still exist: before $exchangesBefore")
            assertTrue(names("/api/policies/%2F").containsAll(policiesBefore), "policies that existed before still exist: before $policiesBefore")
            assertEquals(bystanderPolicyBefore, api.get("/api/policies/%2F/bystander-limit").obj["definition"], "the bystander policy was not changed")
            assertEquals(0, count("bystander"), "the bystander queue got no message")
            assertTrue(
                sentByCheck.plus(sentByCheckAgain).plus(sentByApply).none { it.startsWith("DELETE") || it.endsWith("/get") || it.contains("/publish") },
                "the app sent no DELETE, no queue read and no publish: ${sentByCheck + sentByCheckAgain + sentByApply}",
            )
        } finally {
            scope.cancel()
            toClose.reversed().forEach { runCatching { it.close() } }
            runCatching { folder.toFile().deleteRecursively() }
            runCatching { draftDir.toFile().deleteRecursively() }
        }
    }

    /** The recorded requests of a check are GET only, on the paths of the executor's allow-list, and none of them is under a queue's name beyond its own item. */
    private fun assertOnlyReads(what: String, sent: List<String>) {
        val bad = sent.filter { line ->
            val (method, path) = line.split(" ", limit = 2)
            val segments = path.removePrefix("/api/").split('/')
            method != "GET" || !ExecutorPathAllowList.isAllowed("GET", path) || (segments.first() == "queues" && segments.size != 3)
        }
        assertTrue(sent.isNotEmpty() && bad.isEmpty(), "$what sent a request that is not an allowed GET: $bad of $sent")
    }
}
