package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamListRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M2-14a step 2 (@broker, needs Docker): the whole plain path against a real RabbitMQ 4.x with the plain listeners of the shared fixture
 * (`tls/rabbitmq.conf`: stream 5552, management 15672, next to the TLS ones): check connection, permission read, Connect, stream
 * list with first offsets, all with TLS off; and a profile with TLS ON pointed at the plain ports is refused (nothing falls back).
 * Both listener families live in the same container, so this is also where S-D is kept honest. Streams are created with unique names
 * and deleted at the end. Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlainTest*'`.
 * Red until M2-14a is built (the plain path does not exist yet).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPlainTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551, 5552) }

        @JvmStatic @BeforeAll fun start() = rabbit.start()

        @JvmStatic @AfterAll fun stop() = rabbit.stop()
    }

    private class Secrets(private val password: String) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = password
        override fun delete(profileName: String) = Unit
    }

    private fun plain(ca: String? = "/no/such/ca.pem") = ConnectionProfile(
        name = "real-plain", host = rabbit.host, virtualHost = "/", username = rabbit.adminUsername,
        streamPort = rabbit.getMappedPort(5552), managementPort = rabbit.getMappedPort(15672), tls = false, caCertificatePath = ca,
    )

    private fun tlsOnAtPlainPorts() = plain().copy(name = "real-tls-on-plain-port", tls = true, caCertificatePath = null)

    @Test
    fun `check connection over plain reports both endpoints reachable, the CA path is ignored`() = runBlocking<Unit> {
        assertEquals(ConnectionCheckResult.BothReachable, StreamAndManagementReachabilityChecker().check(plain()))
    }

    @Test
    fun `the permission check over plain reads the administrator's permissions`() = runBlocking<Unit> {
        val result = ManagementApiPermissionChecker().check(plain(), rabbit.adminPassword)
        assertEquals(PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true, userTags = setOf("administrator")), result)
    }

    @Test
    fun `Connect over plain holds a connection that lists streams with their first offsets`() = runBlocking<Unit> {
        val stream = "m214-plain-${System.nanoTime()}"
        val empty = "m214-plain-empty-${System.nanoTime()}"
        Environment.builder().host(rabbit.host).port(rabbit.getMappedPort(5552)).username(rabbit.adminUsername).password(rabbit.adminPassword).build().use { env ->
            env.streamCreator().stream(stream).create()
            env.streamCreator().stream(empty).create()
            try {
                val confirmed = CountDownLatch(3)
                env.producerBuilder().stream(stream).build().use { producer ->
                    repeat(3) { producer.send(producer.messageBuilder().addData(byteArrayOf(1)).build()) { confirmed.countDown() } }
                    assertTrue(confirmed.await(30, TimeUnit.SECONDS), "publish confirms did not arrive")
                }

                val opened = RabbitMqConnectionGateway(Secrets(rabbit.adminPassword)).open(plain())
                val held = assertInstanceOf(OpenResult.Opened::class.java, opened, "got $opened").connection
                try {
                    val page = withTimeout(60_000) {
                        var found: CatalogResult<dev.caerbannog.core.streams.StreamPage>
                        // The management stats are sampled: wait on the condition, bounded, never a fixed sleep.
                        while (true) {
                            found = held.catalog.listStreams(StreamListRequest(vhost = "/", nameFilter = "m214-plain-"))
                            val rows = (found as? CatalogResult.Success<dev.caerbannog.core.streams.StreamPage>)?.value?.rows.orEmpty()
                            if (rows.any { it.name == stream } && rows.any { it.name == empty }) break
                            kotlinx.coroutines.delay(500)
                        }
                        found
                    }
                    assertInstanceOf(CatalogResult.Success::class.java, page)
                    assertEquals(FirstOffset.Value(0), held.offsets.firstOffset("/", stream), "first offset of the stream with messages")
                    assertEquals(FirstOffset.Empty, held.offsets.firstOffset("/", empty), "an empty stream has no first offset")
                } finally {
                    held.close()
                }
            } finally {
                runCatching { env.deleteStream(stream) }
                runCatching { env.deleteStream(empty) }
            }
        }
    }

    @Test
    fun `a profile with TLS on pointed at the plain ports is refused with TlsRefused and no fallback`() = runBlocking<Unit> {
        assertEquals(ConnectionCheckResult.TlsRefused, StreamAndManagementReachabilityChecker().check(tlsOnAtPlainPorts()))
    }

    @Test
    fun `Connect with TLS on at the plain management port fails and nothing leaks the password`() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val result = RabbitMqConnectionGateway(Secrets(rabbit.adminPassword)) { lines += it }.open(tlsOnAtPlainPorts())
        // M2-14c: a plain broker is told apart from a wrong CA by the typed reply, on a real RabbitMQ (the real answer is a 400 to the ClientHello).
        assertEquals(OpenResult.Failed(dev.caerbannog.core.connections.ConnectionFailureReason.NO_TLS_ANSWER), result, "got $result")
        assertTrue(!(result.toString() + lines).contains(rabbit.adminPassword))
    }

    // The real-broker twin of PermissionCheckerPlainTest "TLS on against a plain management port": keeps the local fixture honest
    // (a real broker answers or closes on a ClientHello, it does not stay silent), so the result must be UNREACHABLE, never TIMEOUT.
    @Test
    fun `the permission check with TLS on at the plain management port is unreachable, not a timeout, and has no password`() = runBlocking<Unit> {
        val result = ManagementApiPermissionChecker().check(tlsOnAtPlainPorts(), rabbit.adminPassword)
        val unreadable = assertInstanceOf(PermissionCheckResult.Unreadable::class.java, result, "got $result")
        assertEquals(dev.caerbannog.core.connections.UnreadableCause.UNREACHABLE, unreadable.cause, "got $result")
        assertTrue(!result.toString().contains(rabbit.adminPassword))
    }
}
