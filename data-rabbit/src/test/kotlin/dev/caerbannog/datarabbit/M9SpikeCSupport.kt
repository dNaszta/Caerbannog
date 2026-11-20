package dev.caerbannog.datarabbit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.util.concurrent.TimeUnit

/**
 * M9-00c spike support (the three `RealBrokerM9Spike*` classes of docs/tasks/M9-00c-spike-policies-equivalence-names.md). Uses `MgmtApi` and
 * `awaitUntil` of `M8SpikeSupport.kt` and `brokerWithPlugins` of `RabbitContainers.kt`, unchanged.
 */
internal object M9c {
    val IMAGE: String = BrokerImages.CURRENT_4X

    /** A throwaway 4.x broker with the stream plugins (the stream manager of S-G3 and S-H needs the stream port, so 5552 is exposed). */
    fun newBroker(): RabbitMQContainer =
        brokerWithPlugins(IMAGE, "rabbitmq_stream", "rabbitmq_stream_management").also { it.addExposedPort(5552) }

    /** The first test of each class: the current 4.x image still is a 4.x (the facts are measured on 4.x only). */
    fun assertFourX(api: MgmtApi) {
        val version = api.get("/api/overview").obj["rabbitmq_version"]!!.jsonPrimitive.content
        assertTrue(version.startsWith("4."), "${BrokerImages.CURRENT_4X} answered version $version; the M9-00c facts are measured on 4.x")
    }

    fun fixture(name: String): String =
        checkNotNull(M9c::class.java.getResource("/fixtures/m9-00/$name")) { "missing fixture $name" }.readText()

    fun queueDetail(api: MgmtApi, name: String): JsonObject = api.get("/api/queues/%2F/$name").obj

    /** Every resource a test created, deleted in reverse at the end (a policy first, then queues and exchanges). */
    class Cleanup(private val api: MgmtApi) {
        private val paths = mutableListOf<String>()
        fun queue(name: String): String = name.also { paths += "/api/queues/%2F/$it" }
        fun exchange(name: String): String = name.also { paths += "/api/exchanges/%2F/$it" }
        fun policy(name: String): String = name.also { paths += "/api/policies/%2F/$it" }
        fun operatorPolicy(name: String): String = name.also { paths += "/api/operator-policies/%2F/$it" }
        fun run() { paths.asReversed().forEach { runCatching { api.delete(it) } }; paths.clear() }
    }

    /**
     * A bounded wait for a state that must NOT show up (a policy that takes no effect): it first waits until [positive] is true (the positive
     * control: a policy of the same batch that does take effect, so the broker is known to have applied the batch), then lets [settleSeconds]
     * pass on the clock, polling [stillAbsent]. Fails with [what] if [stillAbsent] turns false.
     */
    fun assertStaysAbsent(what: String, settleSeconds: Long = 8, positive: () -> Boolean, stillAbsent: () -> Boolean) {
        awaitUntil("positive control for: $what", timeoutSeconds = 90, condition = positive)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(settleSeconds)
        awaitUntil("settle window for: $what", timeoutSeconds = settleSeconds + 30) {
            check(stillAbsent()) { "it appeared although it must not: $what" }
            System.nanoTime() >= end
        }
    }

}

internal fun JsonObject.args(): JsonObject = this["arguments"]!!.jsonObject
