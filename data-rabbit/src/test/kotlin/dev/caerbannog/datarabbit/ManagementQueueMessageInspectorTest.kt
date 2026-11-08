package dev.caerbannog.datarabbit

import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.QueueInspectionResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class ManagementQueueMessageInspectorTest {

    @Test
    fun `getQueueMessages URL encodes vhost and queue name and targets the get endpoint`() {
        val requests = ManagementRequests("https://rabbit.example.com:15671")
        assertEquals(
            "https://rabbit.example.com:15671/api/queues/%2F/orders/get",
            requests.getQueueMessages("/", "orders"),
        )
        assertEquals(
            "https://rabbit.example.com:15671/api/queues/my%20vhost/q%2F1/get",
            requests.getQueueMessages("my vhost", "q/1"),
        )
    }

    @Test
    fun `inspectQueueMessages sends POST with ack_requeue_true and parses string and base64 messages as not acknowledged`() {
        val binaryBytes = byteArrayOf(0x01, 0x02, 0x7F, 0x45)
        val base64Payload = Base64.getEncoder().encodeToString(binaryBytes)
        val responseJson = """
            [
              {
                "payload_bytes": 27,
                "redelivered": true,
                "exchange": "events",
                "routing_key": "order.created",
                "message_count": 4,
                "properties": {
                  "message_id": "msg-001",
                  "content_type": "application/json",
                  "headers": {
                    "region": "eu-central"
                  }
                },
                "payload": "{\"orderId\":\"ORD-99\"}",
                "payload_encoding": "string"
              },
              {
                "payload_bytes": 4,
                "redelivered": false,
                "exchange": "",
                "routing_key": "orders",
                "message_count": 3,
                "properties": {},
                "payload": "$base64Payload",
                "payload_encoding": "base64"
              }
            ]
        """.trimIndent()

        PlainManagementServer(mapOf("/api/queues/%2F/orders/get" to (200 to responseJson))).use { server ->
            val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "svc", "secret")
            val requests = ManagementRequests("http://127.0.0.1:${server.port}", BrokerTransport.Plain)
            try {
                val inspector = ManagementQueueMessageInspector(http, requests)
                val result = runBlocking { withTimeout(10_000) { inspector.inspectQueueMessages("/", "orders", count = 10) } }

                val success = assertInstanceOf(QueueInspectionResult.Success::class.java, result)
                assertEquals(2, success.messages.size)

                // Verify the HTTP request sent POST with ackmode = "ack_requeue_true"
                val seen = server.seen.single()
                assertEquals("POST", seen.method)
                assertEquals("/api/queues/%2F/orders/get", seen.rawPath)
                val sentJson = Json.parseToJsonElement(seen.body).jsonObject
                assertEquals(QueueInspectionConstants.ACK_REQUEUE_TRUE, sentJson["ackmode"]?.jsonPrimitive?.content)
                assertEquals(10, sentJson["count"]?.jsonPrimitive?.intOrNull)

                // Verify parsed message 1
                val m1 = success.messages[0]
                assertEquals(1, m1.index)
                assertEquals("""{"orderId":"ORD-99"}""", m1.payload.decodeToString())
                assertTrue(m1.redelivered)
                assertEquals("events", m1.exchange)
                assertEquals("order.created", m1.routingKey)
                assertEquals("msg-001", m1.properties["message_id"])
                assertEquals("application/json", m1.properties["content_type"])
                assertEquals("eu-central", m1.headers["region"])
                assertFalse(m1.acknowledged)
                assertEquals("Not acknowledged (requeued)", m1.acknowledgementStatusText)

                // Verify parsed message 2 (base64 decoded)
                val m2 = success.messages[1]
                assertEquals(2, m2.index)
                assertTrue(binaryBytes.contentEquals(m2.payload))
                assertFalse(m2.acknowledged)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun `inspectQueueMessages maps 401 and 403 to Refused`() {
        PlainManagementServer(mapOf("/api/queues/%2F/orders/get" to (401 to """{"error":"not_authorized"}"""))).use { server ->
            val http = ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.Plain, 5_000), "svc", "secret")
            val requests = ManagementRequests("http://127.0.0.1:${server.port}", BrokerTransport.Plain)
            try {
                val inspector = ManagementQueueMessageInspector(http, requests)
                val result = runBlocking { inspector.inspectQueueMessages("/", "orders", count = 5) }
                assertInstanceOf(QueueInspectionResult.Refused::class.java, result)
            } finally {
                http.close()
            }
        }
    }
}
