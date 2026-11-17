package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.StreamCreator
import com.rabbitmq.stream.StreamException
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * M9-14c step 2b (no Docker): the settings reach the creator of the stream client through `StreamEnvironments(..., environmentFactory)`, the existing
 * seam. The creator is a recording proxy of the REAL `StreamCreator` interface of 1.12.0: it sees `stream(name)`, `argument(name, value)` and
 * `create()`, and ANY other creator call (`maxAge`, `maxLengthBytes`, `maxSegmentSizeBytes`, `leaderLocator`, `initialMemberCount` ...) is recorded as a
 * typed call, which the brief forbids (route T turns 7D into 604800s). A seam test CANNOT see a wrong protocol name on a real broker: that is
 * `RealBrokerM9StreamArgumentsTest` "a stream made by the real manager ..." (the one place that asserts the SHOWN text).
 */
class RabbitMqStreamManagerSettingsTest {

    private class Seen {
        val calls = mutableListOf<String>()
        val arguments = linkedMapOf<String, String>()
        var created: String? = null
        var failWith: Throwable? = null
        val typedCalls get() = calls.filter { it !in setOf("stream", "argument", "create") }
    }

    private fun creator(seen: Seen): StreamCreator {
        lateinit var self: StreamCreator
        self = Proxy.newProxyInstance(StreamCreator::class.java.classLoader, arrayOf(StreamCreator::class.java)) { _, method, args ->
            seen.calls += method.name
            when (method.name) {
                "stream" -> { seen.created = args[0] as String; self }
                "argument" -> { seen.arguments[args[0] as String] = args[1] as String; self }
                "create" -> { seen.failWith?.let { throw it }; null }
                "toString" -> "creator"
                "hashCode" -> 1
                "equals" -> args[0] === self
                else -> if (method.returnType == StreamCreator::class.java) self else null
            }
        } as StreamCreator
        return self
    }

    private fun environment(seen: Seen): Environment {
        val creator = creator(seen)
        return Proxy.newProxyInstance(Environment::class.java.classLoader, arrayOf(Environment::class.java)) { _, method, _ ->
            when (method.name) {
                "streamCreator" -> creator
                "close" -> null
                "toString" -> "environment"
                else -> throw UnsupportedOperationException("the manager used Environment.${method.name}")
            }
        } as Environment
    }

    private val profile = ConnectionProfile(name = "seam", host = "rabbit.example.com", virtualHost = "/", username = "svc", tls = false)

    private fun manager(seen: Seen, vhosts: MutableList<String> = mutableListOf()) =
        RabbitMqStreamManager(StreamEnvironments(profile, "not-a-real-secret", BrokerTransport.Plain) { vhost -> vhosts += vhost; environment(seen) })

    private fun create(settings: StreamSettings, seen: Seen = Seen(), name: String = "audit"): Pair<StreamManagementResult, Seen> =
        runBlocking { manager(seen).createStream("/", name, settings) } to seen

    @Test
    fun `settings none creates a plain stream, no argument at all`() {
        val (result, seen) = create(StreamSettings.NONE)
        assertEquals(StreamManagementResult.Success, result)
        assertEquals("audit", seen.created)
        assertEquals(emptyMap<String, String>(), seen.arguments)
        assertEquals(emptyList<String>(), seen.typedCalls)
        assertTrue("create" in seen.calls)
    }

    @Test
    fun `the call without settings is the same as NONE`() {
        val seen = Seen()
        assertEquals(StreamManagementResult.Success, runBlocking { manager(seen).createStream("/", "audit") })
        assertEquals(emptyMap<String, String>(), seen.arguments)
    }

    @Test
    fun `the creator receives exactly the protocol pairs of the settings, the age as the text written`() {
        val settings = StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, segmentSizeBytes = 100_000_000, leaderLocator = "client-local")
        val (result, seen) = create(settings)
        assertEquals(StreamManagementResult.Success, result)
        assertEquals(settings.protocolArguments(), seen.arguments)
        assertEquals(
            mapOf("max-age" to "7D", "max-length-bytes" to "1000000000", "stream-max-segment-size-bytes" to "100000000", "queue-leader-locator" to "client-local"),
            seen.arguments,
        )
    }

    @Test
    fun `no typed creator method is called, whatever the settings are`() {
        for (settings in listOf(StreamSettings.NONE, StreamSettings(maxAge = "7D"), StreamSettings(maxLengthBytes = 5), StreamSettings(segmentSizeBytes = 5), StreamSettings(leaderLocator = "balanced"))) {
            val (_, seen) = create(settings)
            assertEquals(emptyList<String>(), seen.typedCalls, "typed creator calls for $settings")
        }
    }

    @Test
    fun `no x- name reaches the creator`() {
        val (_, seen) = create(StreamSettings(maxAge = "7D", maxLengthBytes = 1, segmentSizeBytes = 2, leaderLocator = "random"))
        assertTrue(seen.arguments.keys.none { it.startsWith("x-") }, "${seen.arguments.keys}")
    }

    @Test
    fun `only the declared settings are sent`() {
        assertEquals(mapOf("max-age" to "90m"), create(StreamSettings(maxAge = "90m")).second.arguments)
        assertEquals(mapOf("queue-leader-locator" to "least-leaders"), create(StreamSettings(leaderLocator = "least-leaders")).second.arguments)
    }

    @Test
    fun `the stream is created in the vhost asked for, with the name given`() {
        val vhosts = mutableListOf<String>()
        val seen = Seen()
        runBlocking { manager(seen, vhosts).createStream("uat", "audit-1", StreamSettings(maxAge = "1D")) }
        assertEquals(listOf("uat"), vhosts)
        assertEquals("audit-1", seen.created)
    }

    @Test
    fun `a refusal of the broker is a Refused result, with or without settings, and the text of the broker is not the decision`() {
        for (settings in listOf(StreamSettings.NONE, StreamSettings(maxAge = "7D"))) {
            val seen = Seen().apply { failWith = StreamException("PRECONDITION_FAILED", 17.toShort()) }
            val (result, _) = create(settings, seen)
            assertTrue(result is StreamManagementResult.Refused, "for $settings got $result")
        }
    }
}
