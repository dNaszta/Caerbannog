package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunOutcome
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.RemoveOutcome
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StatusOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI

/** M8-08 scope 1 to 3: the four operations over a MockEngine; what is sent, how replies map, and where the secrets go. */
class ManagementShovelGatewayTest {
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")
    private fun rig(dest: (String, String) -> Pair<Int, String>? = ShovelFx.healthy(), source: (String, String) -> Pair<Int, String>? = ShovelFx.sourceQueue) = ShovelRig(destination = dest, source = source)

    // ---- dry run ----

    @Test
    fun `a dry run reads whoami, the shovel list and the destination queue from the 4x broker and one queue from the source, GET only`() {
        val r = rig(); val gateway = r.gateway()
        val done = ShovelRig.run { gateway.dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()
        assertEquals(QueueCounts(3, 1), done.result.source)
        assertEquals(DestinationQueue.Exists(DestinationQueueType.QUORUM), done.result.destination)
        assertEquals(true, done.result.shovelPluginsOn)
        assertEquals(setOf("policymaker", "monitoring"), done.result.userTags)
        assertEquals(null, done.result.existingShovel)
        assertEquals(setOf("GET /api/whoami", "GET /api/shovels", "GET /api/queues/%2F/orders"), r.destinationSeen.map { "${it.method} ${it.path}" }.toSet())
        assertEquals(listOf("GET /api/queues/%2F/orders"), r.sourceSeen.map { "${it.method} ${it.path}" }, "the probe sends ONE GET")
    }

    @Test
    fun `the source probe uses the stored password of the source profile and is closed at once`() {
        val r = rig(); val gateway = r.gateway()
        ShovelRig.run { gateway.dryRun(ShovelFx.request()) }
        assertEquals(listOf("stored-source-pw"), r.sourcePasswordsGiven)
        val probe = r.sourceHttpOpened ?: error("the probe client was never opened")
        assertEquals(ManagementReply.Unreachable, ShovelRig.run { probe.get("http://legacy.example:15672/api/queues") }, "the one-shot probe client was not closed")
    }

    @Test
    fun `an existing shovel is found by vhost and name, another shovel is not`() {
        val mine = ShovelFx.shovelJson(state = "running", forwarded = "40", remaining = "80")
        val other = ShovelFx.shovelJson(name = "caerbannog-migrate-jobs")
        val elsewhere = ShovelFx.shovelJson(vhost = "other")
        val done = ShovelRig.run { rig(ShovelFx.healthy(shovels = "[$other,$elsewhere,$mine]")).gateway().dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()
        assertEquals(ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80), done.result.existingShovel)
        val none = ShovelRig.run { rig(ShovelFx.healthy(shovels = "[$other,$elsewhere]")).gateway().dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()
        assertEquals(null, none.result.existingShovel)
    }

    @Test
    fun `shovels 404 means the plugins are off, 401 means plugins assumed on and no shovel seen`() {
        fun with(status: Int): DryRunOutcome.Done {
            val base = ShovelFx.healthy()
            return ShovelRig.run { rig({ m, p -> if (p == "/api/shovels") status to "" else base(m, p) }).gateway().dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()
        }
        assertEquals(false, with(404).result.shovelPluginsOn)
        assertEquals(true, with(401).result.shovelPluginsOn)
        assertEquals(null, with(401).result.existingShovel)
    }

    @Test
    fun `the destination queue is Missing on 404 and its type is read from the queue json`() {
        fun type(json: String) = (ShovelRig.run { rig(ShovelFx.healthy(dest = json)).gateway().dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()).result.destination
        assertEquals(DestinationQueue.Exists(DestinationQueueType.STREAM), type(ShovelFx.queueJson("stream")))
        assertEquals(DestinationQueue.Exists(DestinationQueueType.CLASSIC), type(ShovelFx.queueJson("classic")))
        assertEquals(DestinationQueue.Exists(DestinationQueueType.OTHER), type(ShovelFx.queueJson("something-new")))
        val base = ShovelFx.healthy()
        val missing = ShovelRig.run { rig({ m, p -> if (p.startsWith("/api/queues/")) 404 to "" else base(m, p) }).gateway().dryRun(ShovelFx.request()) }.expect<DryRunOutcome.Done>()
        assertEquals(DestinationQueue.Missing, missing.result.destination)
    }

    @Test
    fun `null counts in the source json count as 0`() {
        val done = ShovelRig.run {
            rig(source = { _, _ -> 200 to """{"type":"classic","messages_ready":null,"messages_unacknowledged":null}""" }).gateway().dryRun(ShovelFx.request())
        }.expect<DryRunOutcome.Done>()
        assertEquals(QueueCounts(0, 0), done.result.source)
    }

    @Test
    fun `a refused whoami or destination is Failed with a fixed text, never the body`() {
        val base = ShovelFx.healthy()
        for (path in listOf("/api/whoami", "/api/queues/%2F/orders")) for (status in listOf(401, 403)) {
            val out = ShovelRig.run { rig({ m, p -> if (p == path) status to "BODY-with-secret" else base(m, p) }).gateway().dryRun(ShovelFx.request()) }
            val failed = out as? DryRunOutcome.Failed ?: error("expected Failed for $path $status, got $out")
            assertFalse(failed.reason.contains("BODY") || failed.reason.isBlank(), failed.reason)
        }
    }

    @Test
    fun `a destination that cannot be reached, a source that cannot, and a source that refuses`() {
        assertEquals(DryRunOutcome.DestinationUnreachable, ShovelRig.run { rig(ShovelRig.failing()).gateway().dryRun(ShovelFx.request()) })
        assertEquals(DryRunOutcome.SourceUnreachable("legacy-eu"), ShovelRig.run { rig(source = ShovelRig.failing()).gateway().dryRun(ShovelFx.request()) })
        assertEquals(DryRunOutcome.SourceNotPermitted, ShovelRig.run { rig(source = { _, _ -> 401 to "" }).gateway().dryRun(ShovelFx.request()) })
    }

    @Test
    fun `no stored source password or no source profile means the source is not permitted and the probe sends nothing`() {
        val noPassword = ShovelRig(sourcePassword = null, destination = ShovelFx.healthy(), source = ShovelFx.sourceQueue)
        assertEquals(DryRunOutcome.SourceNotPermitted, ShovelRig.run { noPassword.gateway().dryRun(ShovelFx.request()) })
        assertTrue(noPassword.sourceSeen.isEmpty())
        val noProfile = rig()
        assertEquals(DryRunOutcome.SourceNotPermitted, ShovelRig.run { noProfile.gateway { null }.dryRun(ShovelFx.request()) })
        assertTrue(noProfile.sourceSeen.isEmpty())
    }

    // ---- start ----

    private fun startBody(r: ShovelRig): JsonObject = Json.parseToJsonElement(r.destinationSeen.also { assertTrue(it.any { s -> s.method == "PUT" }, "no PUT was sent: $it") }.first { it.method == "PUT" }.body).jsonObject["value"]!!.jsonObject

    @Test
    fun `start sends one PUT of the measured body with ack-mode on-confirm and a credential-free destination`() {
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        assertEquals(StartOutcome.Submitted, ShovelRig.run { r.gateway().start(ShovelFx.request()) })
        assertEquals(1, r.destinationSeen.size, "requests: ${r.destinationSeen.map { it.method + it.path }}")
        val put = r.destinationSeen.single()
        assertEquals("PUT /api/parameters/shovel/%2F/caerbannog-migrate-orders", "${put.method} ${put.path}")
        assertTrue(put.contentType.orEmpty().startsWith("application/json"))
        val body = startBody(r)
        assertEquals(
            setOf("src-protocol", "src-uri", "src-queue", "dest-protocol", "dest-uri", "dest-queue", "src-delete-after", "ack-mode"), body.keys,
        )
        assertEquals(
            listOf("amqp091", "orders", "amqp091", "amqp://", "orders", "queue-length", "on-confirm"),
            listOf("src-protocol", "src-queue", "dest-protocol", "dest-uri", "dest-queue", "src-delete-after", "ack-mode").map { body[it]!!.jsonPrimitive.content },
        )
        assertEquals(listOf("PUT"), r.allSeen.map { it.method }, "a start sends exactly one request, the PUT")
    }

    @Test
    fun `never is src-delete-after never, and the source and destination queue names are the ones of the request`() {
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { r.gateway().start(ShovelFx.request(never = true, destinationQueue = "orders-v2")) }
        val body = startBody(r)
        assertEquals("never", body["src-delete-after"]!!.jsonPrimitive.content)
        assertEquals("orders-v2", body["dest-queue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the source uri uses amqps for TLS and amqp for a plain profile, never the other`() {
        for ((tls, scheme) in listOf(true to "amqps", false to "amqp")) {
            val r = ShovelRig(destination = { _, _ -> 201 to "" })
            ShovelRig.run { r.gateway().start(ShovelFx.request(tls = tls)) }
            assertTrue(startBody(r)["src-uri"]!!.jsonPrimitive.content.startsWith("$scheme://"), "tls=$tls")
        }
    }

    @Test
    fun `the source password comes from the request credentials, else from the secret store`() {
        val typed = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { typed.gateway().start(ShovelFx.request(credentials = ShovelFx.creds("mover", "typed-pw"))) }
        assertEquals("amqp://mover:typed-pw@legacy.example:5672/%2F", startBody(typed)["src-uri"]!!.jsonPrimitive.content)
        val stored = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { stored.gateway().start(ShovelFx.request()) }
        assertEquals("amqp://svc-reader:stored-source-pw@legacy.example:5672/%2F", startBody(stored)["src-uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing stored password is a Failed start with a fixed text and no request at all`() {
        val r = ShovelRig(sourcePassword = null, destination = { _, _ -> 201 to "" })
        val out = ShovelRig.run { r.gateway().start(ShovelFx.request()) }
        assertEquals(StartOutcome.Failed("The password of legacy-eu is not stored"), out)
        assertTrue(r.allSeen.isEmpty(), "a request was sent without a password: ${r.allSeen}")
    }

    @Test
    fun `user and password with at, colon, slash, question mark, hash, percent, space and non-ASCII survive the uri`() {
        val user = "a@b:c"; val password = "p/?#% ü€"
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { r.gateway().start(ShovelFx.request(credentials = ShovelFx.creds(user, password))) }
        val uri = startBody(r)["src-uri"]!!.jsonPrimitive.content
        val parsed = URI(uri)
        assertEquals("legacy.example", parsed.host)
        assertEquals(5672, parsed.port)
        assertEquals("/%2F", parsed.rawPath)
        val rawUserInfo = parsed.rawUserInfo
        assertEquals(1, rawUserInfo.count { it == ':' }, "exactly one colon separates user and password: $rawUserInfo")
        assertFalse(rawUserInfo.any { it in "@/?# " }, "a raw special character in the user info: $rawUserInfo")
        val (u, p) = rawUserInfo.split(':', limit = 2).map { ShovelFx.percentDecode(it) }
        assertEquals(user, u)
        assertEquals(password, p)
    }

    @Test
    fun `start maps 201 and 204 to Submitted, 401 and 403 to NotPermitted, 400 to a fixed text, a transport failure to Unreachable`() {
        fun start(status: Int, body: String = "") = ShovelRig.run { ShovelRig(destination = { _, _ -> status to body }).gateway().start(ShovelFx.request()) }
        assertEquals(StartOutcome.Submitted, start(201)); assertEquals(StartOutcome.Submitted, start(204))
        assertEquals(StartOutcome.NotPermitted, start(401)); assertEquals(StartOutcome.NotPermitted, start(403))
        val failed = start(400, """{"reason":"bad uri amqp://bob:BODY-secret@old"}""").expect<StartOutcome.Failed>()
        assertFalse(failed.reason.contains("BODY") || failed.reason.contains("amqp"), "the broker body leaked into: ${failed.reason}")
        assertEquals(StartOutcome.Unreachable, ShovelRig.run { ShovelRig(destination = ShovelRig.failing()).gateway().start(ShovelFx.request()) })
    }

    @Test
    fun `a queue name with a space, a slash and a percent sign is encoded in the path and literal in the body`() {
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        ShovelRig.run { r.gateway().start(ShovelFx.request(queue = "a b/c%d")) }
        assertEquals(listOf("/api/parameters/shovel/%2F/caerbannog-migrate-a%20b%2Fc%25d"), r.destinationSeen.map { it.path })
        assertEquals("a b/c%d", startBody(r)["src-queue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a queue name of 255 characters is sent, there is no name-length rule`() {
        val r = ShovelRig(destination = { _, _ -> 201 to "" })
        assertEquals(StartOutcome.Submitted, ShovelRig.run { r.gateway().start(ShovelFx.request(queue = "q".repeat(255))) })
        assertEquals(listOf("PUT"), r.allSeen.map { it.method }, "the PUT must be sent")
    }

    // ---- status ----

    private fun status(body: String, code: Int = 200) = ShovelRig.run { ShovelRig(destination = { _, p -> if (p == "/api/shovels") code to body else null }).gateway().status(ref) }

    @Test
    fun `status maps the listed shovel, its state, forwarded and remaining`() {
        assertEquals(StatusOutcome.Listed(ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80)), status("[${ShovelFx.shovelJson(forwarded = "40", remaining = "80")}]"))
        assertEquals(StatusOutcome.Listed(ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, null)), status("[${ShovelFx.shovelJson()}]"), "unlimited is null")
        assertEquals(ShovelRunState.STARTING, (status("[${ShovelFx.shovelJson(state = "starting")}]").expect<StatusOutcome.Listed>()).status.state)
        assertEquals(ShovelRunState.TERMINATED, status("[${ShovelFx.shovelJson(state = "terminated")}]").expect<StatusOutcome.Listed>().status.state)
        assertEquals(ShovelRunState.OTHER, status("[${ShovelFx.shovelJson(state = "whatever")}]").expect<StatusOutcome.Listed>().status.state)
    }

    @Test
    fun `a missing forwarded value is null, and the reason loses its surrounding quotes`() {
        val noNumbers = """[{"name":"caerbannog-migrate-orders","vhost":"/","type":"dynamic","state":"terminated","reason":"\"failed to connect to source\""}]"""
        val listed = status(noNumbers).expect<StatusOutcome.Listed>()
        assertEquals(null, listed.status.forwarded)
        assertEquals("failed to connect to source", listed.status.reason)
    }

    @Test
    fun `a reason is scrubbed of any uri with credentials`() {
        val json = """[{"name":"caerbannog-migrate-orders","vhost":"/","type":"dynamic","state":"terminated","reason":"failed amqp://bob:SENTINEL-in-reason@old:5672"}]"""
        val reason = status(json).expect<StatusOutcome.Listed>().status.reason.orEmpty()
        assertFalse(reason.contains("SENTINEL") || reason.contains("bob:"), reason)
    }

    @Test
    fun `no entry for this vhost and name is Absent, 401 NotPermitted, 404 plugins off, a transport failure Unreachable`() {
        assertEquals(StatusOutcome.Absent, status("[${ShovelFx.shovelJson(name = "other")},${ShovelFx.shovelJson(vhost = "x")}]"))
        assertEquals(StatusOutcome.Absent, status("[]"))
        assertEquals(StatusOutcome.NotPermitted, status("", 401))
        assertEquals(StatusOutcome.Failed("The shovel plugins are not enabled"), status("", 404))
        assertEquals(StatusOutcome.Unreachable, ShovelRig.run { ShovelRig(destination = ShovelRig.failing()).gateway().status(ref) })
    }

    @Test
    fun `status sends one GET of the shovel list and never a parameter endpoint`() {
        val r = ShovelRig(destination = { _, p -> if (p == "/api/shovels") 200 to "[]" else null })
        ShovelRig.run { r.gateway().status(ref) }
        assertEquals(listOf("GET /api/shovels"), r.allSeen.map { "${it.method} ${it.path}" })
    }

    // ---- remove ----

    private fun remove(code: Int) = ShovelRig.run { ShovelRig(destination = { _, _ -> code to "" }).gateway().remove(ref) }

    @Test
    fun `remove maps 204 Removed, 404 NotFound, 401 and 403 NotPermitted, a transport failure Unreachable`() {
        assertEquals(RemoveOutcome.Removed, remove(204)); assertEquals(RemoveOutcome.NotFound, remove(404))
        assertEquals(RemoveOutcome.NotPermitted, remove(401)); assertEquals(RemoveOutcome.NotPermitted, remove(403))
        assertEquals(RemoveOutcome.Unreachable, ShovelRig.run { ShovelRig(destination = ShovelRig.failing()).gateway().remove(ref) })
    }

    @Test
    fun `remove sends one DELETE of the shovel parameter with an encoded path and nothing else`() {
        val r = ShovelRig(destination = { _, _ -> 204 to "" })
        ShovelRig.run { r.gateway().remove(ShovelRef("/", "caerbannog-migrate-a b/c%d")) }
        assertEquals(listOf("DELETE /api/parameters/shovel/%2F/caerbannog-migrate-a%20b%2Fc%25d"), r.allSeen.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `every request of every operation passes the allow-list`() {
        val r = ShovelRig(destination = ShovelFx.healthy(), source = ShovelFx.sourceQueue)
        val gateway = r.gateway()
        ShovelRig.run { gateway.dryRun(ShovelFx.request()); gateway.start(ShovelFx.request()); gateway.status(ref); gateway.remove(ref) }
        assertTrue(r.allSeen.size >= 7, "the premise: the four operations sent requests, got ${r.allSeen.size}")
        r.allSeen.forEach { assertTrue(ShovelPathAllowList.isAllowed(it.method, it.path), "${it.method} ${it.path} is not on the allow-list") }
    }
}
