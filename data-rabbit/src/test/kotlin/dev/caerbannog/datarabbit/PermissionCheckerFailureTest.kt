package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.UnreadableCause
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1-09 (docs/tasks/M1-09-permission-check-hardening.md): `ManagementApiPermissionChecker.check` never throws
 * (except CancellationException); every failure is an Unreadable. Plain unit test, no Docker, no real broker:
 * not tagged "broker", so it runs in the default `test` task.
 * A blackhole or non-routable address is deliberately not used: the outcome depends on the network.
 */
class PermissionCheckerFailureTest {

    private val password = "s3cr3t-pw-Zq9"
    private val base = ConnectionProfile(name = "p", host = "localhost", virtualHost = "/", username = "svc")

    private fun check(
        profile: ConnectionProfile,
        connectTimeoutMillis: Long = 2_000,
        requestTimeoutMillis: Long = connectTimeoutMillis,
    ): PermissionCheckResult =
        runBlocking {
            ManagementApiPermissionChecker(
                connectTimeoutMillis = connectTimeoutMillis,
                requestTimeoutMillis = requestTimeoutMillis,
            ).check(profile, password)
        }

    private fun assertUnreadable(expected: UnreadableCause, result: PermissionCheckResult) {
        assertTrue(result is PermissionCheckResult.Unreadable, "expected Unreadable($expected), got $result")
        assertEquals(expected, result.cause, "got $result")
        assertFalse(result.detail.orEmpty().contains(password), "password in detail: ${result.detail}")
        assertFalse(result.userMessage.contains(password))
        assertFalse(result.topologyUnlocked)
        assertFalse(result.publishingEnabled)
    }

    @Test
    fun `an invalid host is Unreadable UNREACHABLE, not an exception`() {
        assertUnreadable(UnreadableCause.UNREACHABLE, check(base.copy(host = "bad host")))
    }

    @Test
    fun `an out-of-range port is Unreadable UNREACHABLE, not an exception`() {
        assertUnreadable(UnreadableCause.UNREACHABLE, check(base.copy(managementPort = 70_000)))
    }

    @Test
    fun `a negative port is Unreadable UNREACHABLE, not an exception`() {
        assertUnreadable(UnreadableCause.UNREACHABLE, check(base.copy(managementPort = -1)))
    }

    @Test
    fun `an empty host is Unreadable UNREACHABLE, not an exception`() {
        assertUnreadable(UnreadableCause.UNREACHABLE, check(base.copy(host = "")))
    }

    @Test
    fun `a server that accepts and never answers is Unreadable TIMEOUT`() {
        ServerSocket(0).use { server ->
            val accepted = mutableListOf<java.net.Socket>()
            val acceptor = Thread { runCatching { while (true) accepted += server.accept() } }.apply {
                isDaemon = true
                start()
            }
            try {
                val result = check(
                    base.copy(host = "127.0.0.1", managementPort = server.localPort, tls = false),
                    connectTimeoutMillis = 5_000,
                    requestTimeoutMillis = 300,
                )
                assertUnreadable(UnreadableCause.TIMEOUT, result)
            } finally {
                server.close()
                acceptor.join(2_000)
                accepted.forEach { runCatching { it.close() } }
            }
        }
    }
}
