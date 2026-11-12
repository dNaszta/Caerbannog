package dev.caerbannog.datarabbit

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

/**
 * M1-03 spike (docs/tasks/M1-03-permission-check.md): which management endpoints can a non-administrator
 * call to read their OWN permissions? Records the real status codes and bodies; the findings are
 * asserted in the test below so they cannot silently drift. Uses HTTPS (15671) like the product; the
 * trust-all SSLContext is spike-only test code against a throwaway container, never product code.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class PermissionsEndpointSpikeTest {

    companion object {
        private val rabbit: RabbitMQContainer = brokerWithPlugins("rabbitmq:4-management", "rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551) }

        @JvmStatic @BeforeAll fun start() { rabbit.start() }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }
    }

    private fun ctl(vararg args: String) {
        val r = rabbit.execInContainer("rabbitmqctl", *args)
        check(r.exitCode == 0) { "rabbitmqctl ${args.toList()} failed: ${r.stderr}" }
    }

    private val client: HttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        HttpClient.newBuilder().sslContext(ctx)
            .sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" }).build()
    }

    private fun status(user: String, pass: String, path: String): Int {
        val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
        val req = HttpRequest.newBuilder(URI("https://${rabbit.host}:${rabbit.httpsPort}$path"))
            .header("Authorization", "Basic $auth").GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }

    /**
     * Recorded result (RabbitMQ 4 image, 2026-11): only an administrator can read permissions, through
     * /api/permissions/{vhost}/{user} (JSON object with user, vhost, configure, write, read) or
     * /api/users/{user}/permissions (array of the same). Users tagged none, management or monitoring get 401
     * on both; /api/whoami works for management and monitoring users but carries only name and tags.
     */
    @Test
    fun `only an administrator can read permissions, whatever the tag of the user`() {
        ctl("add_user", "plainuser", "pw-plain")
        ctl("set_user_tags", "plainuser")
        ctl("set_permissions", "-p", "/", "plainuser", "^orders\\..*", "", ".*")
        ctl("add_user", "monuser", "pw-mon")
        ctl("set_user_tags", "monuser", "monitoring")
        ctl("set_permissions", "-p", "/", "monuser", ".*", ".*", ".*")
        ctl("add_user", "mgmtuser", "pw-mgmt")
        ctl("set_user_tags", "mgmtuser", "management")
        ctl("set_permissions", "-p", "/", "mgmtuser", "", "", ".*")

        for ((u, p) in listOf("plainuser" to "pw-plain", "monuser" to "pw-mon", "mgmtuser" to "pw-mgmt")) {
            assertEquals(401, status(u, p, "/api/permissions/%2F/$u"), "$u: /api/permissions/{vhost}/{user}")
            assertEquals(401, status(u, p, "/api/users/$u/permissions"), "$u: /api/users/{user}/permissions")
        }
        assertEquals(401, status("plainuser", "pw-plain", "/api/whoami"), "untagged user cannot even call whoami")
        assertEquals(200, status("monuser", "pw-mon", "/api/whoami"))
        assertEquals(200, status("mgmtuser", "pw-mgmt", "/api/whoami"))
        assertEquals(200, status(rabbit.adminUsername, rabbit.adminPassword, "/api/permissions/%2F/${rabbit.adminUsername}"))
        assertEquals(401, status("plainuser", "wrong", "/api/permissions/%2F/plainuser"), "wrong password")
    }
}
