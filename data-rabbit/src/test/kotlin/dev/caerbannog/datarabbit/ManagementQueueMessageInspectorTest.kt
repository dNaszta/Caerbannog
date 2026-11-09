package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.topology.QueueInspectionResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * M7-08 safety half, no network: the queue inspector must always send the requeue request, clamp the count, and turn every
 * answer or failure of the management API into one of the typed outcomes.
 */
class ManagementQueueMessageInspectorTest {

    private class Seen(var method: HttpMethod? = null, var path: String? = null, var body: String? = null)

    private val profile = ConnectionProfile(
        name = "m7", host = "broker.example", virtualHost = "/", username = "svc",
        managementPort = 15672, streamPort = 5552, tls = false,
    )

    private fun inspect(
        queue: String = "orders",
        vhost: String = "/",
        count: Int = 10,
        seen: Seen = Seen(),
        engine: MockEngine,
    ): QueueInspectionResult {
        val http = ManagementHttp(HttpClient(engine), "svc", "pw")
        return http.use { runBlocking { ManagementQueueMessageInspector(it, ManagementRequests.forProfile(profile)).inspectQueueMessages(vhost, queue, count) } }
    }

    private fun answering(status: HttpStatusCode, body: String = "[]", seen: Seen = Seen()) = MockEngine { request ->
        seen.method = request.method
        seen.path = request.url.encodedPath
        seen.body = String(request.body.toByteArray())
        respond(body, status, headersOf("Content-Type", "application/json"))
    }

    @Test
    fun `the request is a POST to the queue get endpoint with ack_requeue_true, auto encoding and a 64 KB truncate`() {
        val seen = Seen()
        inspect(vhost = "/", queue = "orders", count = 7, engine = answering(HttpStatusCode.OK, seen = seen))

        assertEquals(HttpMethod.Post, seen.method)
        assertEquals("/api/queues/%2F/orders/get", seen.path)
        val body = Json.parseToJsonElement(seen.body!!).jsonObject
        assertEquals("ack_requeue_true", body["ackmode"]!!.jsonPrimitive.content)
        assertEquals("auto", body["encoding"]!!.jsonPrimitive.content)
        assertEquals(65536, body["truncate"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, body["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the count is clamped to 1 through 50 and the ack mode never changes`() {
        for ((asked, sent) in listOf(0 to 1, -5 to 1, 1 to 1, 50 to 50, 51 to 50, 9_999 to 50)) {
            val seen = Seen()
            inspect(count = asked, engine = answering(HttpStatusCode.OK, seen = seen))
            val body = Json.parseToJsonElement(seen.body!!).jsonObject
            assertEquals(sent, body["count"]!!.jsonPrimitive.content.toInt(), "asked for $asked")
            assertEquals("ack_requeue_true", body["ackmode"]!!.jsonPrimitive.content, "asked for $asked")
        }
    }

    @Test
    fun `a queue or vhost name with a space or slash is encoded in the path`() {
        val seen = Seen()
        inspect(vhost = "team a/b", queue = "my queue", engine = answering(HttpStatusCode.OK, seen = seen))
        assertEquals("/api/queues/team%20a%2Fb/my%20queue/get", seen.path)
    }

    @Test
    fun `200 with messages gives parsed messages, none acknowledged`() {
        val body = """[{"payload":"hello","payload_encoding":"string","payload_bytes":5,"redelivered":true,"exchange":"","routing_key":"orders",
            "message_count":4,"properties":{"content_type":"text/plain","delivery_mode":2,"headers":{"trace":"t-1","n":7}}}]"""
        val result = inspect(engine = answering(HttpStatusCode.OK, body))

        val messages = assertInstanceOf(QueueInspectionResult.Success::class.java, result).messages
        val m = messages.single()
        assertEquals("hello", m.payload.decodeToString())
        assertTrue(m.redelivered)
        assertEquals(4L, m.messageCount)
        assertEquals("text/plain", m.properties["content_type"])
        assertEquals("2", m.properties["delivery_mode"])
        assertEquals(mapOf("trace" to "t-1", "n" to "7"), m.headers)
        assertEquals(false, m.acknowledged)
    }

    @Test
    fun `200 with an empty list is a success with no messages`() {
        assertEquals(emptyList<Any>(), assertInstanceOf(QueueInspectionResult.Success::class.java, inspect(engine = answering(HttpStatusCode.OK, "[]"))).messages)
    }

    @Test
    fun `200 with a body that is not a message list is Failed, not a crash`() {
        assertInstanceOf(QueueInspectionResult.Failed::class.java, inspect(engine = answering(HttpStatusCode.OK, """{"error":"nope"}""")))
        assertInstanceOf(QueueInspectionResult.Failed::class.java, inspect(engine = answering(HttpStatusCode.OK, "not json at all")))
    }

    @Test
    fun `401 and 403 are Refused with the read-access message`() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            val result = inspect(engine = answering(status))
            val reason = assertInstanceOf(QueueInspectionResult.Refused::class.java, result, "$status").reason
            assertTrue(reason.contains("read access") && reason.contains("orders"), reason)
        }
    }

    @Test
    fun `404 is Refused as not found`() {
        val reason = assertInstanceOf(QueueInspectionResult.Refused::class.java, inspect(engine = answering(HttpStatusCode.NotFound))).reason
        assertTrue(reason.contains("not found") && reason.contains("orders"), reason)
    }

    @Test
    fun `any other status is Failed and names the status`() {
        val reason = assertInstanceOf(QueueInspectionResult.Failed::class.java, inspect(engine = answering(HttpStatusCode.InternalServerError))).reason
        assertTrue(reason.contains("500"), reason)
    }

    @Test
    fun `network and TLS failures map to Failed with their own message`() {
        val unreachable = inspect(engine = MockEngine { throw IOException("connection refused") })
        assertEquals("Broker management API unreachable", assertInstanceOf(QueueInspectionResult.Failed::class.java, unreachable).reason)

        val rejected = inspect(engine = MockEngine { throw SSLException("handshake", CertificateException("PKIX")) })
        assertEquals("Broker certificate rejected", assertInstanceOf(QueueInspectionResult.Failed::class.java, rejected).reason)

        val noTls = inspect(engine = MockEngine { throw SSLException("Unrecognized SSL message, plaintext connection?") })
        assertEquals("Broker refused TLS connection", assertInstanceOf(QueueInspectionResult.Failed::class.java, noTls).reason)
    }
}
