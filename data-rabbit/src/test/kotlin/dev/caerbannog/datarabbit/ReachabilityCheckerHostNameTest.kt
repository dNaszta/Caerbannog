package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * M2-14c step 2c (ruling R1c, no Docker): Test connection, like Connect, does not verify the host name (D7). Both legs run against a local
 * HTTPS server whose certificate (CN and SAN `localhost`) does not list the host the profile uses (`127.0.0.1`: the certificate names only the DNS name `localhost`; `0.0.0.0` of the
 * brief's case 3 does not work here because the Check's stream leg opens a raw Socket, which this macOS JDK refuses on 0.0.0.0); the profile CA is that certificate. The stream leg already tolerates it (SSLSocket); the management leg
 * does not today, so this is red until step 3a. The server answers 200 to every path, so a passed handshake means BothReachable.
 */
class ReachabilityCheckerHostNameTest {

    @Test
    fun `the check tolerates a certificate for another host name on both endpoints`(@TempDir dir: Path) {
        TlsHttpsServer(dir).use { server ->
            val profile = ConnectionProfile(
                name = "hn-check", host = "127.0.0.1", virtualHost = "/", username = "svc",
                streamPort = server.port, managementPort = server.port, tls = true, caCertificatePath = server.certificatePath,
            )
            val result = runBlocking { StreamAndManagementReachabilityChecker(connectTimeoutMillis = 5_000).check(profile) }
            assertEquals(ConnectionCheckResult.BothReachable, result, "host name must not be verified (D7), got $result")
        }
    }
}
