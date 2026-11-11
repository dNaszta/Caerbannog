package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunOutcome
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingSource
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.Prepared
import dev.caerbannog.core.forwarding.ShovelAddress
import dev.caerbannog.core.forwarding.ShovelNaming
import dev.caerbannog.core.forwarding.ShovelSourceCredentials
import dev.caerbannog.core.forwarding.SrcDeleteAfter
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StartResult
import dev.caerbannog.core.forwarding.StatusOutcome
import dev.caerbannog.core.migrate3x.SecretValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.runBlocking
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
import java.util.Collections

/**
 * M8-08 live tests (step 4 runs them): `ManagementShovelGateway` and `ForwardingService` against a real 3.13 broker (alias `old`) and a real 4.x broker
 * on one network. The source profile is plain (`tls = false`, D9a); amqps between containers is out of v1. Each test cleans its shovels and queues.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8Shovel*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8ShovelTest {

    companion object {
        private val network: Network = Network.newNetwork()
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management").withNetwork(network).withNetworkAliases("old")
        private val new: RabbitMQContainer = broker("rabbitmq:4-management").withNetwork(network).withNetworkAliases("new")
            .withPluginsEnabled("rabbitmq_shovel", "rabbitmq_shovel_management")
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi

        @JvmStatic @BeforeAll fun start() {
            old.start(); new.start()
            o = MgmtApi(old); n = MgmtApi(new)
            n.put("/api/users/svc", """{"password":"svc-pass","tags":"management,policymaker,monitoring"}""")
            n.put("/api/users/pm", """{"password":"pm-pass","tags":"management,policymaker"}""")
            for (u in listOf("svc", "pm")) n.put("/api/permissions/%2F/$u", """{"configure":".*","write":".*","read":".*"}""")
        }

        @JvmStatic @AfterAll fun stop() { new.stop(); old.stop(); network.close() }
    }

    /** Every request the gateway's client of the 4.x broker sent: "METHOD path". */
    private val sent: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val sourceProfile get() = ConnectionProfile("old", old.host, "/", "guest", managementPort = old.getMappedPort(15672), tls = false)
    private val secrets = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = if (profileName == "old") "guest" else null
        override fun delete(profileName: String) = Unit
    }

    private fun gateway(user: String = "svc", password: String = "svc-pass"): ManagementShovelGateway {
        val recorder = createClientPlugin("m8-08-recorder") { onRequest { request, _ -> sent += "${request.method.value} ${request.url.build().encodedPath}" } }
        val client = HttpClient(Java) { expectSuccess = false; install(recorder) }
        val requests = ManagementRequests("http://${new.host}:${new.getMappedPort(15672)}", BrokerTransport.Plain)
        return ManagementShovelGateway(ManagementHttp(client, user, password), requests, { if (it == "old") sourceProfile else null }, secrets)
    }

    private fun service(g: ManagementShovelGateway) = ForwardingService(g) { ForwardingContext(TopologyControlLevel.DECLARE_AND_EDIT, true) }

    private fun request(queue: String, credentials: ShovelSourceCredentials? = null, never: Boolean = false) = ForwardingRequest(
        ForwardingSource("old", ShovelAddress(false, "guest", "old", 5672, "/"), "/", queue, credentials), "new", "/", queue,
        if (never) SrcDeleteAfter.NEVER else SrcDeleteAfter.QUEUE_LENGTH,
    )

    private fun enc(name: String) = ManagementRequests.encode(name)
    private fun shovelExists(queue: String) = n.get("/api/parameters/shovel/%2F/${enc(ShovelNaming.nameFor(queue))}").status == 200

    private fun cleanup(vararg queues: String) {
        for (q in queues) {
            n.delete("/api/parameters/shovel/%2F/${enc(ShovelNaming.nameFor(q))}")
            n.delete("/api/queues/%2F/${enc(q)}"); o.delete("/api/queues/%2F/${enc(q)}")
        }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test
    fun `happy path - 3 messages move from the 3x classic queue to the 4x quorum queue, the source queue stays, no shovel remains`() {
        val q = "m8-08-happy"
        try {
            o.declareQueue(q); repeat(3) { o.publish(q, "m$it") }
            n.declareQueue(q, """{"x-queue-type":"quorum"}""")
            // the management statistics lag by seconds: wait until the 3.13 broker reports the 3 messages before the dry run reads them
            awaitUntil("the source reports 3 ready messages") { o.get("/api/queues/%2F/$q").body.contains("\"messages_ready\":3") }
            val g = gateway(); val svc = service(g); val req = request(q)
            val ready = run { svc.prepare(req) } as? Prepared.Ready ?: error("not ready: ${run { svc.prepare(req) }}")
            assertEquals(3L, ready.facts.source.ready)
            assertEquals(DestinationQueue.Exists(DestinationQueueType.QUORUM), ready.facts.destination)
            assertTrue(ready.facts.shovelPluginsOn)
            assertTrue("policymaker" in ready.facts.userTags)
            assertEquals(StartResult.Started, run { svc.start(req, q) })
            var state: ForwardingState = ForwardingState.Started()
            awaitUntil("the shovel finishes") { state = run { svc.poll(req, state) }; state == ForwardingState.Finished }
            awaitUntil("3 messages arrived") { n.peek(q).size == 3 }
            assertEquals(200, o.get("/api/queues/%2F/$q").status, "the source queue must still exist")
            assertFalse(shovelExists(q), "no shovel remains")
            assertTrue(sent.none { it.contains("/get") || it.contains("/purge") || (it.startsWith("GET ") && it.contains("/api/parameters")) }, "forbidden requests: $sent")
        } finally { cleanup(q) }
    }

    @Test
    fun `a wrong source password - Submitted, then Failed with a cleanup, the parameter is gone and the password is nowhere in the reason`() {
        val q = "m8-08-wrongpw"
        try {
            o.declareQueue(q); n.declareQueue(q, """{"x-queue-type":"quorum"}""")
            val g = gateway(); val svc = service(g)
            val req = request(q, ShovelSourceCredentials("guest", SecretValue.of("wrong-SENTINEL-password")))
            assertEquals(StartResult.Started, run { svc.start(req, q) })
            var state: ForwardingState = ForwardingState.Started()
            awaitUntil("the shovel is reported failed") { state = run { svc.poll(req, state) }; state is ForwardingState.Failed }
            val failed = state as ForwardingState.Failed
            assertTrue(failed.cleanedUp, "the failed shovel was not removed")
            assertFalse(failed.reason.contains("SENTINEL") || failed.reason.contains("guest:"), failed.reason)
            assertFalse(shovelExists(q), "the parameter must be gone")
        } finally { cleanup(q) }
    }

    @Test
    fun `a missing destination queue is reported by the dry run, start is Blocked and no shovel exists`() {
        val q = "m8-08-nodest"
        try {
            o.declareQueue(q)
            val svc = service(gateway()); val req = request(q)
            val blocked = run { svc.prepare(req) } as Prepared.Blocked
            assertTrue(ForwardingBlock.DestinationMissing in blocked.plan.blocks)
            assertEquals(DestinationQueue.Missing, blocked.facts?.destination)
            assertTrue(run { svc.start(req, q) } is StartResult.Blocked)
            assertFalse(shovelExists(q))
            assertEquals(404, n.get("/api/queues/%2F/$q").status, "the shovel must not have created the destination")
        } finally { cleanup(q) }
    }

    @Test
    fun `a policymaker without monitoring is blocked with CannotReadStatus, the real 401 of S-F`() {
        val q = "m8-08-pm"
        try {
            o.declareQueue(q); n.declareQueue(q, """{"x-queue-type":"quorum"}""")
            val svc = service(gateway("pm", "pm-pass"))
            val blocked = run { svc.prepare(request(q)) } as Prepared.Blocked
            assertTrue(ForwardingBlock.CannotReadStatus in blocked.plan.blocks, "blocks: ${blocked.plan.blocks}")
            assertFalse(shovelExists(q))
        } finally { cleanup(q) }
    }

    @Test
    fun `measurements - the shovel name limit, the absent grace, and a queue name with a space, a slash and a percent sign end to end`() {
        // (a) the longest accepted parameter name; the constant MAX_SHOVEL_NAME_LENGTH must be exactly that
        fun putNamed(length: Int): Int {
            val name = "n".repeat(length)
            val status = n.put(
                "/api/parameters/shovel/%2F/$name",
                """{"value":{"src-protocol":"amqp091","src-uri":"amqp://guest:guest@old:5672","src-queue":"m8-08-limit","dest-protocol":"amqp091","dest-uri":"amqp://","dest-queue":"m8-08-limit","src-delete-after":"never","ack-mode":"on-confirm"}}""",
            ).status
            n.delete("/api/parameters/shovel/%2F/$name")
            return status
        }
        // a name of length 4096 is the search ceiling; the constant must not be higher than what the broker accepts
        var lo = 1; var hi = 4096
        if (putNamed(hi) in 200..299) lo = hi else { while (hi - lo > 1) { val mid = (lo + hi) / 2; if (putNamed(mid) in 200..299) lo = mid else hi = mid } }
        println("M8-08 MEASURED longest accepted shovel name: $lo characters (search ceiling 4096)")
        assertTrue(MAX_SHOVEL_NAME_LENGTH <= lo, "MAX_SHOVEL_NAME_LENGTH $MAX_SHOVEL_NAME_LENGTH is above the measured limit $lo")

        // (b) the absent grace: polls that say Absent before the first Listed after a submitted PUT (never + empty source keeps it running)
        val grace = "m8-08-grace"
        try {
            o.declareQueue(grace); n.declareQueue(grace, """{"x-queue-type":"quorum"}""")
            val g = gateway(); val req = request(grace, never = true)
            assertEquals(StartOutcome.Submitted, run { g.start(req) })
            var absent = 0
            awaitUntil("the shovel is listed") { if (run { g.status(req.shovel) } is StatusOutcome.Listed) true else { absent++; false } }
            println("M8-08 MEASURED absent polls before the shovel was listed: $absent")
            assertTrue(absent <= 1, "the shovel was absent for $absent polls after the PUT; M8-07 allows one (architect: set Started.absentPolls < N)")
        } finally { cleanup(grace) }

        // (c) a queue name with a space, a slash and a percent sign, end to end
        val odd = "m8 08/%x"
        try {
            o.declareQueue(enc(odd)); repeat(2) { o.publish(odd, "m$it") }
            n.declareQueue(enc(odd), """{"x-queue-type":"quorum"}""")
            val svc = service(gateway()); val req = request(odd)
            assertEquals(StartResult.Started, run { svc.start(req, odd) })
            var state: ForwardingState = ForwardingState.Started()
            awaitUntil("the odd queue finishes") { state = run { svc.poll(req, state) }; state == ForwardingState.Finished }
            awaitUntil("2 messages arrived") { n.peek(enc(odd)).size == 2 }
        } finally { cleanup(odd) }
    }
}
