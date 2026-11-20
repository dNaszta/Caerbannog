package dev.caerbannog.datarabbit

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.HttpURLConnection
import java.net.URI

/**
 * M1-01 skeleton spike, Acceptance item 5: "One Testcontainers test starts a RabbitMQ 4.x container
 * and reaches its management port; it is skipped cleanly (not failed, not hung) when Docker is
 * absent."
 *
 * Belongs to `data-rabbit`'s own test source set per the task brief's Design notes, since that is
 * where real broker calls will live from M1-02 onward. Tagged "broker" to mirror the BDD `@broker`
 * tag: excluded from the default fast test run, opted into only when Docker is present.
 *
 * `disabledWithoutDocker = true` is Testcontainers' own JUnit 5 extension mechanism for "skipped
 * cleanly (not failed, not hung) when Docker is absent" - verify this manually in the no-Docker case
 * once the dependency is wired (this sandbox always has Docker, so that path cannot be proven by a
 * second automated test here).
 *
 * Expected to fail to compile right now: `data-rabbit/build.gradle.kts` declares no Testcontainers
 * or JUnit 5 dependency yet.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RabbitMqContainerSpikeTest {

    companion object {
        // RabbitMQ 4.x per docs/spec.md "Broker requirements"; stream + stream_management plugins
        // enabled so the real stream client / management client can be spiked here in M1-02+.
        private val rabbit: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")

        @JvmStatic
        @BeforeAll
        fun startContainer() {
            rabbit.start()
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            rabbit.stop()
        }
    }

    @Test
    fun `management port answers after the container starts`() {
        val managementUrl = "http://${rabbit.host}:${rabbit.httpPort}/api/overview"
        val connection = URI(managementUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000

        try {
            // The management API answers with 401 for unauthenticated requests - that still proves
            // the plugin is up and listening, which is all this spike needs.
            assertEquals(401, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }
}
