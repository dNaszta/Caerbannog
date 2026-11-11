package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.ForwardingSource
import dev.caerbannog.core.forwarding.ShovelAddress
import dev.caerbannog.core.forwarding.ShovelSourceCredentials
import dev.caerbannog.core.forwarding.SrcDeleteAfter
import dev.caerbannog.core.migrate3x.SecretValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** M8-08: MockEngine rig for `ManagementShovelGateway`. The "scripted" replies are chosen by (method, encoded path); a thrown IOException is a transport failure. */
internal class ShovelRig(
    val sourcePassword: String? = "stored-source-pw",
    destination: (String, String) -> Pair<Int, String>? = { _, _ -> null },
    source: (String, String) -> Pair<Int, String>? = { _, _ -> null },
) {
    val destinationSeen = mutableListOf<M508a.Seen>()
    val sourceSeen = mutableListOf<M508a.Seen>()
    val sourcePasswordsGiven = mutableListOf<String>()
    var sourceHttpOpened: ManagementHttp? = null

    val store = object : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = if (profileName == "legacy-eu") sourcePassword else null
        override fun delete(profileName: String) = Unit
    }

    private fun engine(seen: MutableList<M508a.Seen>, script: (String, String) -> Pair<Int, String>?) = MockEngine { request ->
        val body = String(request.body.toByteArray())
        seen += M508a.Seen(request.method.value, request.url.encodedPath, request.headers["Authorization"], request.headers["Content-Type"] ?: request.body.contentType?.toString(), body)
        val reply = script(request.method.value, request.url.encodedPath) ?: (404 to "")
        respond(reply.second, HttpStatusCode.fromValue(reply.first), headersOf("Content-Type", "application/json"))
    }

    private var destinationScript = destination
    private var sourceScript = source
    fun destination(script: (String, String) -> Pair<Int, String>?) { destinationScript = script }
    fun source(script: (String, String) -> Pair<Int, String>?) { sourceScript = script }

    val profile = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", managementPort = 15672, tls = false)

    fun gateway(profiles: (String) -> ConnectionProfile? = { if (it == "legacy-eu") profile else null }): ManagementShovelGateway =
        ManagementShovelGateway(
            M508a.http(engine(destinationSeen) { m, p -> destinationScript(m, p) }), M508a.requests(), profiles, store,
        ) { _, password ->
            sourcePasswordsGiven += password
            ManagementHttp(HttpClient(engine(sourceSeen) { m, p -> sourceScript(m, p) }), "svc-reader", password).also { sourceHttpOpened = it }
        }

    val allSeen: List<M508a.Seen> get() = destinationSeen + sourceSeen

    companion object {
        fun failing(): (String, String) -> Pair<Int, String>? = { _, _ -> throw IOException("refused") }
        fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }
    }
}

internal object ShovelFx {
    const val SENTINEL = "SENTINEL-pw/?#%"

    fun request(
        queue: String = "orders",
        tls: Boolean = false,
        credentials: ShovelSourceCredentials? = null,
        never: Boolean = false,
        destinationQueue: String = queue,
    ) = ForwardingRequest(
        ForwardingSource("legacy-eu", ShovelAddress(tls, credentials?.username ?: "svc-reader", "legacy.example", if (tls) 5671 else 5672, "/"), "/", queue, credentials),
        "new-eu", "/", destinationQueue, if (never) SrcDeleteAfter.NEVER else SrcDeleteAfter.QUEUE_LENGTH,
    )

    fun creds(user: String = "mover", password: String = "typed-pw") = ShovelSourceCredentials(user, SecretValue.of(password))

    const val WHOAMI = """{"name":"svc","tags":["policymaker","monitoring"]}"""
    const val NO_SHOVELS = "[]"
    fun queueJson(type: String = "quorum", ready: String = "3", unacked: String = "1") = """{"name":"orders","type":"$type","messages_ready":$ready,"messages_unacknowledged":$unacked}"""
    fun shovelJson(name: String = "caerbannog-migrate-orders", vhost: String = "/", state: String = "running", forwarded: String = "40", remaining: String = "\"unlimited\"", reason: String? = null) =
        """{"name":"$name","vhost":"$vhost","type":"dynamic","state":"$state","forwarded":$forwarded,"remaining":$remaining${reason?.let { ""","reason":${kotlinx.serialization.json.JsonPrimitive(it)}""" } ?: ""}}"""

    /** The destination script of a healthy 4.x broker: whoami, shovel list, destination queue. */
    fun healthy(shovels: String = NO_SHOVELS, dest: String = queueJson("quorum", "0", "0")): (String, String) -> Pair<Int, String>? = { m, p ->
        when {
            m == "GET" && p == "/api/whoami" -> 200 to WHOAMI
            m == "GET" && p == "/api/shovels" -> 200 to shovels
            m == "GET" && p.startsWith("/api/queues/") -> 200 to dest
            else -> null
        }
    }

    val sourceQueue: (String, String) -> Pair<Int, String>? = { m, p -> if (m == "GET" && p == "/api/queues/%2F/orders") 200 to queueJson("classic", "3", "1") else null }

    fun percentDecode(text: String): String = java.net.URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8)
}

/** A cast that fails as an assertion with the actual value, so a stub or a wrong mapping reads "expected X but was Y". */
internal inline fun <reified T> Any?.expect(): T = this as? T ?: throw org.opentest4j.AssertionFailedError("expected ${T::class.simpleName} but was $this")
