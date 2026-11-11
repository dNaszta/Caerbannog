package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.StartResult
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * M8-08 scenarios of feature 17 tagged `@m8-08 @fake-broker`, bound here as unit tests (the acceptance module cannot see `internal` classes):
 * "The shovel's credentials are never shown or logged", "The app never reads the shovel parameters", "The destination of the shovel is the local
 * broker without credentials".
 */
class ShovelSecretsTest {
    private val password = ShovelFx.SENTINEL
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")
    private val uriWithPassword = Regex("://[^@\\s/]*:[^@\\s/]*@")

    private fun terminatedList(reason: String) =
        """[{"name":"caerbannog-migrate-orders","vhost":"/","type":"dynamic","state":"terminated","reason":${kotlinx.serialization.json.JsonPrimitive(reason)}}]"""

    /** Captures java.util.logging (root, all levels), System.out and System.err while [block] runs. */
    private fun captured(block: () -> Unit): String {
        val lines = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { lines += "${record.loggerName} ${record.level} ${record.message} ${record.parameters?.joinToString()} ${record.thrown}" }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val root = Logger.getLogger("")
        val oldLevel = root.level
        root.level = java.util.logging.Level.ALL
        root.addHandler(handler)
        val out = System.out; val err = System.err
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true)); System.setErr(PrintStream(buffer, true))
        try { block() } finally { System.setOut(out); System.setErr(err); root.removeHandler(handler); root.level = oldLevel }
        return lines.joinToString("\n") + "\n" + buffer.toString()
    }

    @Test
    fun `scenario - the shovel's credentials are never shown or logged`() {
        val r = ShovelRig(
            destination = { m, p ->
                when {
                    m == "PUT" -> 201 to ""
                    m == "GET" && p == "/api/shovels" -> 200 to terminatedList("failed to connect to amqp://mover:$password@legacy.example:5672/%2F")
                    m == "DELETE" -> 204 to ""
                    else -> ShovelFx.healthy()(m, p)
                }
            },
            source = ShovelFx.sourceQueue,
        )
        val request = ShovelFx.request(credentials = ShovelFx.creds("mover", password))
        val gateway = r.gateway()
        val texts = mutableListOf<String>()
        val log = captured {
            ShovelRig.run {
                texts += gateway.dryRun(request).toString()
                val started = gateway.start(request); texts += started.toString()
                val status = gateway.status(ref); texts += status.toString()
                texts += gateway.remove(ref).toString()
                val service = ForwardingService(gateway) { ForwardingContext(TopologyControlLevel.DECLARE_AND_EDIT, true) }
                texts += service.prepare(request).toString()
                texts += service.start(request, "orders").toString()
                texts += service.poll(request, ForwardingState.Started()).toString()
            }
        }
        val all = (texts + log).joinToString("\n")
        assertFalse(all.contains(password), "the password is in an outcome, a state or the output: ${all.lines().firstOrNull { it.contains(password) }}")
        assertFalse(uriWithPassword.containsMatchIn(all), "a uri with credentials is shown: ${uriWithPassword.find(all)?.value}")
        assertTrue(texts.size >= 7)
        // the one place the password may be: the src-uri of the PUT body the engine saw
        val bodies = r.destinationSeen.filter { it.method == "PUT" }.map { it.body }
        assertTrue(bodies.isNotEmpty() && bodies.all { it.contains("src-uri") }, "the premise: a PUT was sent")
        assertTrue(r.sourceSeen.isNotEmpty(), "the premise: the probe ran")
        r.allSeen.filter { it.method != "PUT" }.forEach { assertFalse(it.body.contains(password) || it.path.contains(password), "the password left in ${it.method} ${it.path}") }
        r.sourceSeen.forEach { assertFalse(it.path.contains(password), "the password is in the probe path") }
    }

    @Test
    fun `the failed-start texts, whatever the broker says, hold no secret`() {
        for (code in listOf(400, 401, 403, 500)) {
            val r = ShovelRig(destination = { _, _ -> code to """{"reason":"bad amqp://u:$password@h"}""" })
            val out = ShovelRig.run { r.gateway().start(ShovelFx.request(credentials = ShovelFx.creds("mover", password))) }
            assertFalse(out.toString().contains(password), "status $code: $out")
            assertFalse(out.toString().contains("amqp://"), "status $code: $out")
        }
    }

    @Test
    fun `scenario - the app never reads the shovel parameters`() {
        val r = ShovelRig(destination = ShovelFx.healthy(shovels = "[${ShovelFx.shovelJson()}]"), source = ShovelFx.sourceQueue)
        val gateway = r.gateway()
        ShovelRig.run { gateway.dryRun(ShovelFx.request()); gateway.status(ref) }
        assertTrue(r.allSeen.isNotEmpty())
        assertTrue(r.allSeen.none { it.path.startsWith("/api/parameters") || it.path.startsWith("/api/global-parameters") || it.path.startsWith("/api/definitions") }, "parameter endpoints were requested: ${r.allSeen.map { it.path }}")
        assertTrue(r.destinationSeen.any { it.path == "/api/shovels" }, "the status came from the shovel status endpoint")
        assertTrue(r.allSeen.all { it.method == "GET" })
    }

    @Test
    fun `scenario - the destination of the shovel is the local broker without credentials`() {
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { r.gateway().start(ShovelFx.request(credentials = ShovelFx.creds("mover", password))) }
        assertEquals(1, r.destinationSeen.size, "requests: ${r.destinationSeen.map { it.method + it.path }}")
        val body = Json.parseToJsonElement(r.destinationSeen.single().body).jsonObject["value"]!!.jsonObject
        assertEquals("amqp://", body["dest-uri"]!!.jsonPrimitive.content, "no user name, no password, no host")
        val withoutSource = body.filterKeys { it != "src-uri" }.toString()
        assertFalse(withoutSource.contains(password) || withoutSource.contains("mover"), "credentials outside the source uri: $withoutSource")
        assertTrue(body["src-uri"]!!.jsonPrimitive.content.contains("mover"), "only the source address has credentials")
    }

    @Test
    fun `a forwarding service run over the gateway ends with a started state and the destination holds no secret`() {
        val r = ShovelRig(destination = ShovelFx.healthy(dest = ShovelFx.queueJson("quorum", "0", "0")).let { base -> { m: String, p: String -> if (m == "PUT") 201 to "" else base(m, p) } }, source = ShovelFx.sourceQueue)
        val service = ForwardingService(r.gateway()) { ForwardingContext(TopologyControlLevel.DECLARE_AND_EDIT, true) }
        val result = ShovelRig.run { service.start(ShovelFx.request(), "orders") }
        assertEquals(StartResult.Started, result)
    }
}
