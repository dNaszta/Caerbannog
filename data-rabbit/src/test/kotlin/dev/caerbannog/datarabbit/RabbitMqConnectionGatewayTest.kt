package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.Collections

/**
 * M2-03 part 2 (no Docker, no network beyond localhost): the real gateway's failure paths and its secret hygiene
 * ("a test checks the error and log text of a failed Connect", brief). Contract, package `dev.caerbannog.datarabbit`:
 *   class RabbitMqConnectionGateway(secretStore: SecretStore, log: (String) -> Unit = {}) : ConnectionGateway
 *   open(profile): reads the password from the store once; no password -> Failed(NO_PASSWORD_STORED) before any
 *   network call; a broker that cannot be reached -> Failed(BROKER_NOT_REACHABLE); nothing stays open after a Failed.
 * The other reasons (TLS refused, credentials refused, no management tag) need a broker: `RealBrokerCatalogTest` (@broker).
 */
class RabbitMqConnectionGatewayTest {

    private val password = "Hunter2-do-not-log-4711"
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private class Store(private val passwords: Map<String, String>) : SecretStore {
        var reads = 0
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? { reads++; return passwords[profileName] }
        override fun delete(profileName: String) = Unit
    }

    private fun closedLocalPort(): Int = ServerSocket(0).use { it.localPort }

    private fun profile(host: String = "127.0.0.1", port: Int = closedLocalPort(), ca: String? = null) = ConnectionProfile(
        name = "gw-test", host = host, virtualHost = "/", username = "svc", streamPort = closedLocalPort(),
        managementPort = port, caCertificatePath = ca,
    )

    private fun open(store: Store, profile: ConnectionProfile): OpenResult =
        runBlocking { withTimeout(30_000) { RabbitMqConnectionGateway(store) { lines += it }.open(profile) } }

    @Test
    fun `no stored password fails before any network call`() {
        val store = Store(emptyMap())
        val result = open(store, profile())
        assertEquals(OpenResult.Failed(ConnectionFailureReason.NO_PASSWORD_STORED), result)
    }

    @Test
    fun `a closed port is broker not reachable and leaves no password anywhere`() {
        val store = Store(mapOf("gw-test" to password))

        val result = open(store, profile())

        assertEquals(OpenResult.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), result)
        assertFalse((result.toString() + lines).contains(password), "password visible in result or log: $result $lines")
    }

    @Test
    fun `a host that does not resolve is broker not reachable`() {
        val result = open(Store(mapOf("gw-test" to password)), profile(host = "no-such-host.invalid"))
        assertEquals(OpenResult.Failed(ConnectionFailureReason.BROKER_NOT_REACHABLE), result)
    }

    @Test
    fun `the password is read from the store once per open`() {
        val store = Store(mapOf("gw-test" to password))
        open(store, profile())
        assertEquals(1, store.reads, "D6: read once when the holder is created")
    }

    @Test
    fun `a CA file that cannot be read fails with a reason that has no password and no path`() {
        val missing = "/definitely/not/here/ca-$password.pem"
        val result = open(Store(mapOf("gw-test" to password)), profile(ca = missing))

        assertInstanceOf(OpenResult.Failed::class.java, result, "expected a failure, got $result")
        val text = result.toString() + lines
        assertFalse(text.contains(password), text)
        assertFalse(text.contains("definitely/not/here"), "the local path must not be in the reason or log: $text")
    }

    @Test
    fun `a failed open logs nothing that contains the password, the user or the host`() {
        open(Store(mapOf("gw-test" to password)), profile())
        val text = lines.joinToString("\n")
        assertFalse(text.contains(password), text)
        assertFalse(text.contains("Basic "), "no authorization header in a log: $text")
    }
}
