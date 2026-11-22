package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile

/**
 * docs/tasks/M1-02-connection-profiles.md, Acceptance: "a @Tag("broker") JUnit test in data-rabbit
 * proves a real BrokerReachabilityChecker reports both endpoints reachable against a real,
 * TLS-enabled Testcontainers RabbitMQ 4.x broker." This is the concrete artifact that closes M1's
 * spec.md gate ("Connects to a local broker").
 *
 * Deliberately a plain JUnit `@Tag("broker")` test, not a Cucumber scenario - see the task brief's
 * Design notes: this is the highest-unknown part of the task (TLS cert plumbing into a Testcontainers
 * RabbitMQ instance), kept isolated so a surprise here does not also block the `@fake-broker`
 * scenarios in acceptance-tests, which need no Docker at all. Mirrors M1-01's own
 * RabbitMqContainerSpikeTest in structure (same @Tag("broker") / @Testcontainers(disabledWithoutDocker
 * = true) pattern, excluded from the default `test` task, opted into via `:data-rabbit:brokerTest`,
 * which already pins the `api.version=1.43` Docker workaround this machine needs - see that task's
 * build.gradle.kts).
 *
 * TLS setup (test-developer's note on how far this got, for the handover report): RabbitMQContainer
 * (testcontainers-rabbitmq 1.21.3) has no built-in helper for stream-protocol or management-API TLS
 * (its own `withSSL(...)` only wires classic AMQP TLS on 5671 via RABBITMQ_SSL_* env vars - confirmed
 * by inspecting its bytecode, since no sources jar is cached locally). Instead this test uses the
 * lower-level `withRabbitMQConfig(MountableFile)` (confirmed present and functional in 1.21.3 by the
 * same inspection: it mounts the given file at /etc/rabbitmq/rabbitmq-custom.conf and sets
 * RABBITMQ_CONFIG_FILE accordingly) to supply a full custom rabbitmq.conf
 * (src/test/resources/tls/rabbitmq.conf) that turns on `stream.listeners.ssl.default` (5551) and
 * `management.ssl.port` (15671) explicitly, pointing both at a throwaway self-signed CA/server cert
 * pair (src/test/resources/tls/{ca.pem,server.pem,server.key}, generated once with openssl for this
 * fixture only - see this task's handover notes for the exact commands, not reproduced here). Port
 * 5551 is not exposed by RabbitMQContainer's constructor by default (only 5672/5671/15672/15671 are,
 * confirmed the same way), so it is added explicitly below.
 *
 * NOT yet proven end-to-end by the test developer: this test has not been run against a live Docker
 * daemon as part of writing it (that is the senior developer's job once the real
 * BrokerReachabilityChecker exists to make it pass) - it is expected to fail to COMPILE right now
 * (missing dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker, the real
 * BrokerReachabilityChecker implementation this task adds to data-rabbit). If the TLS config above
 * turns out to be wrong in some way only a running broker reveals (e.g. a RabbitMQ 4.x config key
 * rename), that is exactly the "genuinely hard TLS Testcontainers surprise" the brief asked to be
 * flagged back rather than guessed around - see the handover report.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerReachabilityTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551) }

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
    fun `a real BrokerReachabilityChecker reports both endpoints reachable over TLS`() = runBlocking {
        val profile = ConnectionProfile(
            name = "testcontainers-broker",
            host = rabbit.host,
            virtualHost = "/",
            username = rabbit.adminUsername,
            streamPort = rabbit.getMappedPort(5551),
            managementPort = rabbit.httpsPort,
            // Our server cert is signed by a throwaway CA the system trust store does not know about;
            // point the checker at it explicitly - the same "custom CA certificate" path
            // 01_connections.feature's own "Use a custom CA certificate" scenario exercises with a fake.
            caCertificatePath = javaClass.classLoader.getResource("tls/ca.pem")!!.toURI().let { java.io.File(it).absolutePath },
        )

        val checker = StreamAndManagementReachabilityChecker()
        val result = checker.check(profile)

        assertEquals(ConnectionCheckResult.BothReachable, result)
    }

    // --- M2-14f: Wrong CA reports CertificateRejected on the real broker Check ---

    @Test
    fun `a real BrokerReachabilityChecker reports CertificateRejected for a wrong CA`() = runBlocking {
        val wrongCaPath = javaClass.classLoader.getResource("certs/valid-certificate.pem")!!.toURI().let { java.io.File(it).absolutePath }
        val profile = ConnectionProfile(
            name = "testcontainers-broker-wrong-ca",
            host = rabbit.host,
            virtualHost = "/",
            username = rabbit.adminUsername,
            streamPort = rabbit.getMappedPort(5551),
            managementPort = rabbit.httpsPort,
            caCertificatePath = wrongCaPath,
        )

        val checker = StreamAndManagementReachabilityChecker()
        val result = checker.check(profile)

        assertEquals(ConnectionCheckResult.CertificateRejected, result)
    }
}

