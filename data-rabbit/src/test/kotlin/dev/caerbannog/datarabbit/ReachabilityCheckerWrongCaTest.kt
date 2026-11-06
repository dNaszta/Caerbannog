package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * M2-14f step 2: The Check reports an untrusted CA as CertificateRejected ("The certificate was not trusted"),
 * not TlsRefused ("may not use TLS"). Tested against a local HTTPS server presenting a certificate
 * untrusted by the profile's CA.
 */
class ReachabilityCheckerWrongCaTest {

    @Test
    fun `an untrusted certificate is reported as CertificateRejected on the Check`(@TempDir dir: Path) {
        val serverDir = dir.resolve("server")
        serverDir.toFile().mkdirs()
        val clientDir = dir.resolve("client")
        clientDir.toFile().mkdirs()

        // Server presents serverDir certificate
        TlsHttpsServer(serverDir).use { server ->
            // Client uses another independent certificate as CA, so server's cert is untrusted
            TlsHttpsServer(clientDir).use { other ->
                val profile = ConnectionProfile(
                    name = "wrong-ca-check",
                    host = "127.0.0.1",
                    virtualHost = "/",
                    username = "svc",
                    streamPort = server.port,
                    managementPort = server.port,
                    tls = true,
                    caCertificatePath = other.certificatePath,
                )

                val result = runBlocking {
                    StreamAndManagementReachabilityChecker(connectTimeoutMillis = 5_000).check(profile)
                }

                assertEquals(ConnectionCheckResult.CertificateRejected, result)
            }
        }
    }
}
