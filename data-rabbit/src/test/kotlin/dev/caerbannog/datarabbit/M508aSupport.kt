package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.streams.StreamManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException

/** M5-08a test support: a Ktor mock engine in front of [FakeManagementBroker], and the wiring of a writer, read-back or executor over it. */
internal object M508a {

    val profile = ConnectionProfile(
        name = "m5-08a", host = "broker.example", virtualHost = "/", username = "svc",
        managementPort = 15672, streamPort = 5552, tls = false,
    )

    /** One request as the mock engine saw it (method, raw encoded path, header and body). */
    class Seen(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)

    /** The engine of the honest fake: every request goes to [broker]; a thrown [FakeManagementBroker.ForbiddenPath] is a test failure, not a reply. */
    fun engineOver(broker: FakeManagementBroker, seen: MutableList<Seen>? = null) = MockEngine { request ->
        val body = String(request.body.toByteArray())
        seen?.add(Seen(request.method.value, request.url.encodedPath, request.headers["Authorization"], request.headers["Content-Type"] ?: request.body.contentType?.toString(), body))
        val reply = try {
            broker.handle(request.method.value, request.url.encodedPath, body.ifEmpty { null })
        } catch (e: IOException) {
            throw e
        }
        respond(reply.body, HttpStatusCode.fromValue(reply.status), headersOf("Content-Type", "application/json"))
    }

    /** An engine that answers every request with [status] and [body] and records what it saw. */
    fun answering(status: Int, body: String = "", seen: MutableList<Seen>? = null) = MockEngine { request ->
        seen?.add(Seen(request.method.value, request.url.encodedPath, request.headers["Authorization"], request.headers["Content-Type"] ?: request.body.contentType?.toString(), String(request.body.toByteArray())))
        respond(body, HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
    }

    fun http(engine: MockEngine) = ManagementHttp(HttpClient(engine), "svc", "pw")
    fun requests() = ManagementRequests.forProfile(profile)

    fun executor(broker: FakeManagementBroker, streams: StreamManager = broker.streamManager(), delays: MutableList<Long> = mutableListOf(), seen: MutableList<Seen>? = null) =
        ManagementTopologyMigrationExecutor(http(engineOver(broker, seen)), requests(), streams) { delays += it }

    fun failedReason(outcome: WriteOutcome): String = (outcome as? WriteOutcome.Failed)?.reason ?: error("expected Failed but got $outcome")

    fun failedReason(result: ActionResult): String = (result as? ActionResult.Failed)?.reason ?: error("expected Failed but got $result")

    /** The fixed wording the brief pins (see MigrationWriter). */
    const val NOT_ALLOWED = "not allowed"
    const val NO_ANSWER = "the broker did not answer"
    const val REFUSED = "refused"
    fun conflict(name: String) = "$name exists with different settings"
}
