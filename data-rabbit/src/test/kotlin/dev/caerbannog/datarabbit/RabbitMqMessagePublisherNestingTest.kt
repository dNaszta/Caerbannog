package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.publish.PublishMessageRequest
import dev.caerbannog.core.publish.PublishOutcome
import dev.caerbannog.core.publish.PublishTarget
import dev.caerbannog.core.publish.PublishTargetKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M5-07 call site #7: the publish reply of the management API (HTTP 200) is parsed only to say "routed or not". The message is already
 * published, so a reply nested ten thousand levels deep keeps the existing fallback `Success("Message published")` and is never a
 * StackOverflowError (not caught here on purpose). Fake HTTP reply through ktor MockEngine; no broker.
 */
class RabbitMqMessagePublisherNestingTest {

    private val profile = ConnectionProfile(name = "p", host = "broker.example", virtualHost = "/", username = "svc", managementPort = 15672, tls = false)

    private fun publish(kind: PublishTargetKind, replyBody: String): PublishOutcome {
        val engine = MockEngine { respond(replyBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }
        val http = ManagementHttp(HttpClient(engine), "svc", "pw")
        val environments = StreamEnvironments(profile, "pw", BrokerTransport.Plain) { error("the stream client is not used for HTTP publishing") }
        val publisher = RabbitMqMessagePublisher(environments, http, ManagementRequests.forProfile(profile))
        return http.use {
            runBlocking {
                publisher.publish(PublishMessageRequest("/", PublishTarget(kind, "orders", "rk"), "hello".encodeToByteArray()))
            }
        }
    }

    @Test
    fun `a deep reply from an exchange publish keeps the plain Success message`() {
        assertEquals(PublishOutcome.Success("Message published"), publish(PublishTargetKind.EXCHANGE, DeepJson.arrays()))
        assertEquals(PublishOutcome.Success("Message published"), publish(PublishTargetKind.EXCHANGE, DeepJson.objects()))
    }

    @Test
    fun `a deep reply from a queue publish keeps the plain Success message`() {
        assertEquals(PublishOutcome.Success("Message published"), publish(PublishTargetKind.QUEUE, DeepJson.arrays()))
    }

    @Test
    fun `a deep value next to routed false is not trusted, the plain Success message stays`() {
        assertEquals(PublishOutcome.Success("Message published"), publish(PublishTargetKind.EXCHANGE, """{"routed":false,"x":${DeepJson.arrays()}}"""))
    }

    @Test
    fun `a normal reply still says where the message went`() {
        assertEquals(PublishOutcome.Success("Message published to exchange 'orders'"), publish(PublishTargetKind.EXCHANGE, """{"routed":true}"""))
        assertEquals(PublishOutcome.Success("Message published to exchange 'orders' (unrouted)"), publish(PublishTargetKind.EXCHANGE, """{"routed":false}"""))
    }
}
