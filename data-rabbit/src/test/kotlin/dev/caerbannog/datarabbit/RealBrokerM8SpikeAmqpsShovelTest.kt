package dev.caerbannog.datarabbit

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile

/**
 * M8-13 spike S-Q (docs/tasks/M8-00-spike-results.md, section "S-Q"): a dynamic Shovel on a 4.x broker pulling from a 3.13
 * broker over `amqps` with the throwaway TLS fixtures (CN and SAN `localhost`, host alias `tlsold`, so the host name does
 * not match), the other `ack-mode` values and an integer `src-delete-after`. Documentation of measured behaviour only: the
 * product stays on `on-confirm`. The CA file is copied to the 4.x broker because `cacertfile` in a Shovel URI names a path on
 * the broker that runs the Shovel. Fixture credentials only (`guest:guest`).
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeAmqpsShovelTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeAmqpsShovelTest {

    companion object {
        private val network: Network = Network.newNetwork()
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management").withNetwork(network).withNetworkAliases("tlsold")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/amqps-only.conf"))
        private val new: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_shovel", "rabbitmq_shovel_management")
            .withNetwork(network).withNetworkAliases("new")
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi
        private var seq = 0

        @JvmStatic @BeforeAll fun start() {
            for (c in listOf(old, new)) c.withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            old.withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            old.withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            old.start(); new.start()
            o = MgmtApi(old); n = MgmtApi(new)
        }

        @JvmStatic @AfterAll fun stop() { new.stop(); old.stop(); network.close() }
    }

    private data class Result(val state: String, val forwarded: Int, val reason: String, val onSource: Int, val onDestination: Int) {
        /** The shovel removed its own parameter: it finished (`queue-length` or an integer reached). */
        val finished: Boolean get() = state == "gone"
        override fun toString() = "$state forwarded=$forwarded reason=$reason | source holds=$onSource, destination holds=$onDestination"
    }

    private fun shovelEntry(name: String) = n.get("/api/shovels").arr.map { it.jsonObject }.firstOrNull { it["name"]!!.jsonPrimitive.content == name }

    /**
     * Creates a shovel (JSON fragments given raw) over [count] fresh messages, waits until it is gone or terminated or [until] holds,
     * then until the two queues stop changing (peeks are exact, the statistics counters lag).
     */
    private fun run(
        label: String, srcUri: String, count: Int = 5, ackMode: String = "\"on-confirm\"", deleteAfter: String = "\"queue-length\"",
        destArguments: String = """{"x-queue-type":"quorum"}""", until: (String, Int) -> Boolean = { state, _ -> state == "gone" || state == "terminated" }, settle: Boolean = true,
    ): Result {
        val id = ++seq
        val src = "q$id"; val dst = "d$id"; val name = "s$id"
        o.declareQueue(src); n.declareQueue(dst, destArguments)
        repeat(count) { o.publish(src, "m$it") }
        awaitUntil("$count on the source") { o.peek(src).size == count }
        val put = n.put("/api/parameters/shovel/%2F/$name", """{"value":{"src-protocol":"amqp091","src-uri":"$srcUri","src-queue":"$src","dest-protocol":"amqp091","dest-uri":"amqp://","dest-queue":"$dst","src-delete-after":$deleteAfter,"ack-mode":$ackMode}}""")
        if (!put.ok) {
            println("S-Q [$label] PUT refused: ${put.status} ${put.reason()}")
            return Result("put-refused-${put.status}", 0, put.reason(), count, 0)
        }
        var state = "none"; var forwarded = 0; var reason = ""
        try {
            awaitUntil("[$label] the shovel did not reach the wanted state, last=$state", 45) {
                val e = shovelEntry(name)
                state = e?.get("state")?.jsonPrimitive?.content ?: "gone"
                forwarded = e?.get("forwarded")?.jsonPrimitive?.content?.toIntOrNull() ?: forwarded
                reason = e?.get("reason")?.jsonPrimitive?.content ?: reason
                until(state, forwarded)
            }
            var snapshot = -1 to -1; var stable = 0
            // A peek takes messages off the destination for a moment (and puts them back), which opens room under a length limit for a retrying shovel: no peeking while it runs.
            if (settle) awaitUntil("[$label] the queues did not settle", 45) {
                val now = o.peek(src).size to n.peek(dst).size
                stable = if (now == snapshot) stable + 1 else 0
                snapshot = now
                stable >= 10
            }
        } finally {
            if (shovelEntry(name) != null) n.delete("/api/parameters/shovel/%2F/$name")
        }
        if (!settle) {
            awaitUntil("[$label] the shovel is gone", 30) { shovelEntry(name) == null }
            var snapshot = -1 to -1; var stable = 0
            awaitUntil("[$label] the queues did not settle after the shovel was removed", 45) {
                val now = o.peek(src).size to n.peek(dst).size
                stable = if (now == snapshot) stable + 1 else 0
                snapshot = now
                stable >= 10
            }
        }
        val result = Result(state, forwarded, reason, o.peek(src).size, n.peek(dst).size)
        println("S-Q [$label] -> $result")
        return result
    }

    private val base = "amqps://guest:guest@tlsold:5671/%2F"
    private val plain = "amqp://guest:guest@tlsold:5672/%2F"
    private val ca = "/etc/rabbitmq/tls/ca.pem"

    private fun assertMoved(r: Result, count: Int = 5) { assertTrue(r.finished && r.onDestination == count && r.onSource == 0, r.toString()) }
    private fun assertRefused(r: Result) {
        assertEquals("terminated", r.state, r.toString())
        // 3.x and 4.0 to 4.3 send the reason Erlang-quoted ("\"failed...\""), 4.4 sends it plain; both hide the TLS detail
        assertEquals("failed to connect to source", r.reason.removeSurrounding("\""), "the status reason hides the TLS detail")
        assertTrue(r.onDestination == 0 && r.onSource == 5, r.toString())
    }

    @Test
    fun `S-Q the amqps URI the product builds today verifies the peer and fails against a private CA, the options that make it work are measured`() {
        assertRefused(run("amqps, no options (what the product builds today)", base))
        val log = new.logs.lines().filter { it.contains("tls_alert", true) || it.contains("hostname", true) || it.contains("unknown_ca", true) || it.contains("certificate", true) }
        println("S-Q broker log lines about the refused TLS connection: ${log.map { it.substringAfter("[") }.take(6)}")
        assertTrue(log.isNotEmpty(), "the broker log, not the status, carries the TLS detail")
        assertFalse(new.logs.contains("guest:guest"), "the password does not reach the broker log")

        assertMoved(run("verify=verify_none", "$base?verify=verify_none"))
        assertRefused(run("verify_peer without cacertfile", "$base?verify=verify_peer"))
        assertRefused(run("verify_peer + cacertfile, host name mismatch", "$base?verify=verify_peer&cacertfile=$ca"))
        assertRefused(run("cacertfile only, host name mismatch", "$base?cacertfile=$ca"))
        assertMoved(run("verify_peer + cacertfile + server_name_indication=localhost", "$base?verify=verify_peer&cacertfile=$ca&server_name_indication=localhost"))
        assertMoved(run("verify_peer + cacertfile + server_name_indication=disable", "$base?verify=verify_peer&cacertfile=$ca&server_name_indication=disable"))
        assertRefused(run("amqp (plain) to the TLS port", "amqp://guest:guest@tlsold:5671/%2F"))
        assertRefused(run("amqps to the plain port", "amqps://guest:guest@tlsold:5672/%2F"))
    }

    @Test
    fun `S-Q ack-mode values, a bogus one is refused at PUT and the three others behave the same on a healthy path`() {
        for (mode in listOf("on-confirm", "on-publish", "no-ack")) assertMoved(run("ack-mode $mode", plain, 5, "\"$mode\""))
        val bogus = run("ack-mode bogus", plain, 5, "\"bogus\"")
        assertEquals("put-refused-400", bogus.state)
        assertTrue(bogus.reason.contains("ack-mode should be one of"), bogus.reason)
    }

    @Test
    fun `S-Q ack-mode against a destination that rejects publishes, which mode keeps the messages`() {
        val rejecting = """{"x-max-length":2,"x-overflow":"reject-publish"}""" // a classic queue: reject-publish on the quorum queue did not limit it (5 of 5 arrived, run before)
        for (mode in listOf("on-confirm", "on-publish", "no-ack")) {
            val r = run("full destination, ack-mode $mode", plain, 5, "\"$mode\"", deleteAfter = "\"never\"", destArguments = rejecting, until = { _, forwarded -> forwarded >= 2 }, settle = false)
            if (mode == "on-confirm") {
                // The shovel keeps the 3 refused messages unacknowledged and retries; removing it hands them back to the source queue.
                assertEquals(2, r.onDestination, r.toString()); assertEquals(3, r.onSource, r.toString())
            } else {
                // Acknowledged at the source before (on-publish) or without (no-ack) the destination accepting them: 3 of 5 are gone for good.
                assertEquals(2, r.onDestination, r.toString()); assertEquals(0, r.onSource, r.toString())
            }
        }
    }

    @Test
    fun `S-Q src-delete-after as an integer moves that many messages and removes the shovel, a string or a never-reached count behaves as measured`() {
        val three = run("src-delete-after 3 of 10", plain, 10, deleteAfter = "3")
        assertTrue(three.finished && three.onDestination == 3 && three.onSource == 7, three.toString())
        val fifty = run("src-delete-after 50 of 10", plain, 10, deleteAfter = "50", until = { _, forwarded -> forwarded >= 10 })
        assertTrue(fifty.state == "running" && fifty.onDestination == 10 && fifty.onSource == 0, fifty.toString())
        val string = run("src-delete-after string 3", plain, 10, deleteAfter = "\"3\"")
        assertEquals("put-refused-400", string.state)
        assertTrue(string.reason.contains("src-delete-after should be a number"), string.reason)
        val zero = run("src-delete-after 0", plain, 10, deleteAfter = "0")
        println("S-Q zero: $zero")
    }
}
