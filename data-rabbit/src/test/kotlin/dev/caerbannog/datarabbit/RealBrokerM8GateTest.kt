package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingSource
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.Prepared
import dev.caerbannog.core.forwarding.ShovelAddress
import dev.caerbannog.core.forwarding.ShovelNaming
import dev.caerbannog.core.forwarding.SrcDeleteAfter
import dev.caerbannog.core.forwarding.StartResult
import dev.caerbannog.core.migrate3x.CompatibilityReportJson
import dev.caerbannog.core.migrate3x.CompatibilityReportText
import dev.caerbannog.core.migrate3x.DecisionRefusal
import dev.caerbannog.core.migrate3x.DecisionResult
import dev.caerbannog.core.migrate3x.GenerationRequest
import dev.caerbannog.core.migrate3x.GenerationResult
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.NotMigratedReason
import dev.caerbannog.core.migrate3x.analyseLive
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.redactedRaw
import dev.caerbannog.core.streams.UnavailableStreamManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * M8-11, the M8 gate (`@gate-M8 @broker-3x @m8-11`): the whole flow with the app's real classes on a real 3.13 and a real 4.x broker on one network.
 * Live analysis of a 3.13 topology, review (Skip only what cannot be accepted), generate (access file and topology file), apply through the real
 * runner and executor, move the message of the queue `orders` with a Shovel (`ForwardingService` over `ManagementShovelGateway`), a migrated user logs in.
 * One happy path with the safeguard assertions; the failure cases live in the M8-08, M8-12c and spike classes. The 4.x container's administrator is
 * `guest` (the image default, administrator tag), which is also the pair the shovel classes use between the containers.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8GateTest*'`.
 */
@Tag("broker")
@Tag("broker-3x")
@Tag("gate-M8")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8GateTest {

    companion object {
        private val network: Network = Network.newNetwork()
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management").withNetwork(network).withNetworkAliases("old")
        private val new: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_shovel", "rabbitmq_shovel_management").withNetwork(network).withNetworkAliases("new")
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi

        @JvmStatic @BeforeAll fun start() { old.start(); new.start(); o = MgmtApi(old); n = MgmtApi(new) }
        @JvmStatic @AfterAll fun stop() { new.stop(); old.stop(); network.close() }

        private const val SVC_PASSWORD = "svc-SENTINEL-password-7"
        private const val OLD_HASH = "U0VOVElORUwtYmNyeXB0LWhhc2gtb2YtdXNlci1vbGQtMDEyMzQ1Njc4OWFiY2RlZg=="
    }

    private val sent3: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val sent4: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun recording(into: MutableList<String>, name: String) = HttpClient(Java) {
        expectSuccess = false
        install(createClientPlugin(name) { onRequest { request, _ -> into += "${request.method.value} ${request.url.build().encodedPath}" } })
    }

    private val sourceProfile get() = ConnectionProfile("old", old.host, "/", "guest", managementPort = old.getMappedPort(15672), tls = false)
    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = if (profileName == "old") "guest" else null
        override fun delete(profileName: String) = Unit
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(120_000) { block() } }

    private class Capture : Handler() {
        val lines = Collections.synchronizedList(ArrayList<String>())
        override fun publish(record: LogRecord) { lines += record.message.orEmpty() + record.thrown?.toString().orEmpty() }
        override fun flush() = Unit
        override fun close() = Unit
    }

    @Test
    fun `a 3_13 topology is analysed, reviewed, generated, applied to 4x, the queue moves with a shovel and a migrated user logs in`() {
        // ---- the fixture, on 3.13, through its management API (test code, not the app)
        assertTrue(o.put("/api/policies/%2F/ha-all", """{"pattern":".*","definition":{"ha-mode":"all"},"apply-to":"queues"}""").ok, "policy")
        assertTrue(o.declareQueue("orders").ok, "orders"); assertTrue(o.declareQueue("temp", durable = false).ok, "temp")
        assertTrue(o.put("/api/exchanges/%2F/events", """{"type":"direct","durable":true}""").ok, "exchange")
        assertTrue(o.post("/api/bindings/%2F/e/events/q/orders", """{"routing_key":"order.created"}""").ok, "binding")
        assertTrue(o.publish("orders", "hello").ok, "publish")
        assertTrue(o.put("/api/users/svc", """{"password":"$SVC_PASSWORD","tags":"management"}""").ok, "svc")
        assertTrue(o.put("/api/permissions/%2F/svc", """{"configure":".*","write":".*","read":".*"}""").ok, "svc permissions")
        val imported = o.post("/api/definitions", """{"users":[{"name":"old","password_hash":"$OLD_HASH","hashing_algorithm":"rabbit_password_hashing_bcrypt","tags":[]}]}""")
        assertTrue(imported.ok, "the user old with an unsupported hash: $imported")
        awaitUntil("3.13 reports the message and the users") { o.get("/api/queues/%2F/orders").body.contains("\"messages_ready\":1") && o.get("/api/users/old").ok }
        val svcHash = o.get("/api/users/svc").obj["password_hash"]!!.jsonPrimitive.content
        val sentinels = listOf(SVC_PASSWORD, svcHash, OLD_HASH)
        val credentialsInUri = Regex("""amqps?://[^/\s@]+:[^@\s]+@""")

        // ---- capture logs and stdout/stderr for the whole run
        val capture = Capture(); val root = Logger.getLogger("")
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val realOut = System.out; val realErr = System.err
        root.addHandler(capture); System.setOut(PrintStream(out, true)); System.setErr(PrintStream(err, true))
        val observed = ArrayList<String>()
        try {
            // ---- live analysis (the app's real reads, every request recorded)
            val version = o.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content
            val reads = ManagementThreeXReads(
                ManagementHttp(recording(sent3, "gate-3x"), "guest", "guest"), ManagementRequests("http://${old.host}:${old.getMappedPort(15672)}", BrokerTransport.Plain),
            )
            val analysed = run { analyseLive(LiveDefinitionsSource("old", version, reads) { delay(it) }) }
            val held = (analysed as? LiveAnalysisOutcome.Analysed ?: error("the live analysis did not finish: $analysed")).held
            assertTrue(held.report.findings.any { it.findingClass == dev.caerbannog.core.migrate3x.FindingClass.MessageLoss && it.kind == ItemKind.Queue && it.name == "orders" }, "the report must carry a message-loss finding for orders")
            observed += held.toString() + CompatibilityReportJson.encode(held.report) + CompatibilityReportText.render(held.report)

            // ---- review: accept every proposal that can be accepted, skip only the rest
            val review = held.review
            val skipped = ArrayList<String>()
            for (item in review.items) {
                var result = review.decide(item, ItemDecision.Accept())
                val refusal = (result as? DecisionResult.Refused)?.reason
                if (refusal is DecisionRefusal.TickMissing) result = review.decide(item, ItemDecision.Accept(acknowledged = refusal.missing))
                if (result is DecisionResult.Refused) { review.decide(item, ItemDecision.Skip); skipped += "${item.kind} ${item.name}: ${result.reason}" }
            }
            assertTrue(skipped.any { it.startsWith("${ItemKind.Queue} temp") }, "the transient queue temp must be the item that cannot be accepted; skipped: $skipped")
            assertFalse(skipped.any { it.startsWith("${ItemKind.Queue} orders") }, "orders must be accepted; skipped: $skipped")
            observed += review.toString() + skipped

            // ---- generate: access file and topology file
            val generated = review.generate(GenerationRequest(emptySet(), "old")) as? GenerationResult.Generated ?: error("generate did not produce files")
            observed += generated.toString() + generated.migrations.joinToString { it.toString() }
            val access = generated.migrations.single { it.access }.file
            val topology = generated.migrations.filter { !it.access }.map { it.file }
            assertEquals(1, topology.size, "one topology file for the one virtual host")
            val declared = access.up.filterIsInstance<MigrationAction.DeclareUser>().map { it.name }
            assertEquals(listOf("svc"), declared, "the access file declares svc only")
            assertEquals(setOf("old", "guest"), generated.notMigrated.map { it.name }.toSet(), "old and guest are listed as not migrated")
            assertTrue(generated.notMigrated.single { it.name == "guest" }.reason == NotMigratedReason.Guest)
            for (file in listOf(access) + topology) observed += redactedRaw(file.rawJson) + file.toString()
            assertTrue(sentinels.none { redactedRaw(access.rawJson).contains(it) }, "the raw view of the access file shows a secret")

            // ---- apply to 4.x through the real runner and executor (administrator profile at Full control)
            val tags = n.get("/api/whoami").obj["tags"].toString()
            assertTrue("administrator" in tags, "the 4.x user must be an administrator: $tags")
            val executor = ManagementTopologyMigrationExecutor.overClient(
                recording(sent4, "gate-4x"), "http://${new.host}:${new.getMappedPort(15672)}", "guest", "guest", UnavailableStreamManager,
            ) { delay(it) }
            val history = InMemoryMigrationHistoryRepository()
            val runner = MigrationRunner(history, executor)
            val files: List<MigrationFile> = listOf(access) + topology
            for (file in files) {
                val dry = runner.dryRun(file, true, TopologyControlLevel.FULL_CONTROL, "/", setOf("administrator"))
                assertTrue(dry.canApply, "dry run of ${file.version}: ${dry.blockedReason}")
                observed += dry.toString()
            }
            val applied = run { runner.applyPending("new", files, true, TopologyControlLevel.FULL_CONTROL, "/", setOf("administrator")) }
            observed += applied.toString()
            assertEquals(MigrationApplyResult.Success(files.map { it.version }.sorted()), applied, "the first apply: $applied")

            // ---- forwarding: one queue with a Shovel
            val service = ForwardingService(
                ManagementShovelGateway(
                    ManagementHttp(recording(sent4, "gate-4x-shovel"), "guest", "guest"),
                    ManagementRequests("http://${new.host}:${new.getMappedPort(15672)}", BrokerTransport.Plain),
                    { if (it == "old") sourceProfile else null }, secrets,
                ) { profile, password -> ManagementHttp(recording(sent3, "gate-3x-source"), profile.username, password) },
            ) { ForwardingContext(TopologyControlLevel.FULL_CONTROL, true) }
            val request = ForwardingRequest(
                ForwardingSource("old", ShovelAddress(false, "guest", "old", 5672, "/"), "/", "orders", null), "new", "/", "orders", SrcDeleteAfter.QUEUE_LENGTH,
            )
            val ready = run { service.prepare(request) } as? Prepared.Ready ?: error("not ready: ${run { service.prepare(request) }}")
            observed += ready.toString()
            assertEquals(1L, ready.facts.source.ready, "one ready message at the source")
            assertEquals(DestinationQueue.Exists(DestinationQueueType.QUORUM), ready.facts.destination)
            assertTrue(ready.confirmation.notEncrypted, "a plain source says Not encrypted")
            assertEquals(StartResult.Started, run { service.start(request, ready.confirmation.requiredText) })
            var state: ForwardingState = ForwardingState.Started()
            awaitUntil("the shovel finishes (last state $state)", 90) { state = run { service.poll(request, state) }; observed += state.toString(); state == ForwardingState.Finished }

            // ---- assertions
            val dest = n.get("/api/queues/%2F/orders").obj
            assertEquals("quorum", dest["type"]!!.jsonPrimitive.content, "orders on 4.x is a quorum queue")
            awaitUntil("the message arrived on 4.x") { n.peek("orders") == listOf("hello") }
            assertEquals(listOf("hello"), n.peek("orders"), "the body arrived intact")
            assertEquals(200, o.get("/api/queues/%2F/orders").status, "orders still exists on 3.13")
            val source = o.get("/api/queues/%2F/orders").obj
            assertEquals("classic", source["type"]?.jsonPrimitive?.content ?: "classic", "the source queue is unchanged")
            assertTrue(source["durable"]!!.jsonPrimitive.content == "true", "the source queue is still durable")
            awaitUntil("the moved message left the source (a move, the queue stays)") { o.peek("orders").isEmpty() }
            assertEquals(404, n.get("/api/parameters/shovel/%2F/${ManagementRequests.encode(ShovelNaming.nameFor("orders"))}").status, "the shovel parameter is gone")
            assertTrue(sent3.isNotEmpty() && sent3.any { it.contains("/api/definitions") }, "the analysis reached 3.13: $sent3")
            assertTrue(sent3.all { it.startsWith("GET ") }, "a request to 3.13 that is not a GET: ${sent3.filter { !it.startsWith("GET ") }}")
            assertTrue(sent4.none { it.contains("/get") || it.contains("/purge") }, "a message path was used on 4.x: ${sent4.filter { it.contains("/get") || it.contains("/purge") }}")
            assertEquals(200, n.get("/api/whoami", "svc", SVC_PASSWORD).status, "svc must log in on 4.x with the old password")
            assertEquals(404, n.get("/api/users/old").status, "old must not exist on 4.x")
            assertTrue(access.up.none { it is MigrationAction.DeclareUser && it.name == "guest" }, "guest is never declared (the 4.x guest is the container's own administrator)")

            // ---- a second apply writes nothing
            val before = sent4.size
            assertEquals(MigrationApplyResult.Success(emptyList()), run { runner.applyPending("new", files, true, TopologyControlLevel.FULL_CONTROL, "/", setOf("administrator")) }, "the runner reports everything as applied")
            assertEquals(before, sent4.size, "an already applied run sent requests: ${sent4.drop(before)}")
            // a forced re-run of the executor: the access actions are guarded by the hash and write nothing; the topology actions are idempotent declarations
            val accessResults = access.up.map { a -> run { executor.execute(a) } }
            assertTrue(accessResults.all { it == ActionResult.Success }, "a forced re-run of the access actions: $accessResults")
            assertEquals(emptyList<String>(), sent4.drop(before).filter { !it.startsWith("GET ") }, "the forced re-run of the access file wrote")
            val topologyResults = topology.flatMap { f -> f.up.map { a -> run { executor.execute(a) } } }
            assertTrue(topologyResults.all { it == ActionResult.Success }, "a forced re-run of the topology actions: $topologyResults")
            assertEquals("quorum", n.get("/api/queues/%2F/orders").obj["type"]!!.jsonPrimitive.content, "the re-run changed the queue")
            assertEquals(listOf("hello"), n.peek("orders"), "the re-run changed the messages")

            observed += capture.lines + out.toString() + err.toString()
        } finally {
            System.setOut(realOut); System.setErr(realErr); root.removeHandler(capture)
        }
        for (text in observed) {
            sentinels.forEach { assertFalse(text.contains(it), "a secret is in an observed text: ${text.take(200)}") }
            assertFalse(credentialsInUri.containsMatchIn(text), "amqp credentials in an observed text: ${text.take(200)}")
        }
    }
}
