package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.PermissionCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.ServerSocket

/**
 * M1-03 @broker tests (docs/tasks/M1-03-permission-check.md): `ManagementApiPermissionChecker` against a
 * real TLS broker. Contract proposed by the test developer:
 *   ManagementApiPermissionChecker(connectTimeoutMillis: Long = 5_000) : PermissionChecker
 *   suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult
 * Spike result (see PermissionsEndpointSpikeTest): only an administrator can read permissions; every other
 * user gets 401 and must come back as Unreadable, never as an exception and never as unlocked.
 * Each test creates its own uniquely named vhost and users and removes them in teardown.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerPermissionCheckTest {

    companion object {
        private val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management")
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withRabbitMQConfig(MountableFile.forClasspathResource("tls/rabbitmq.conf"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/ca.pem"), "/etc/rabbitmq/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.pem"), "/etc/rabbitmq/tls/server.pem")
            .withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key"), "/etc/rabbitmq/tls/server.key")
            .apply { addExposedPorts(5551) }

        @JvmStatic @BeforeAll fun start() { rabbit.start() }
        @JvmStatic @AfterAll fun stop() { rabbit.stop() }
    }

    private val vhosts = mutableListOf<String>()
    private val users = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        vhosts.forEach { runCatching { ctl("delete_vhost", it) } }
        users.forEach { runCatching { ctl("delete_user", it) } }
        vhosts.clear(); users.clear()
    }

    private fun ctl(vararg args: String) {
        val r = rabbit.execInContainer("rabbitmqctl", *args)
        check(r.exitCode == 0) { "rabbitmqctl ${args.toList()} failed: ${r.stderr}" }
    }

    private fun vhost(): String = "perm-${System.nanoTime()}".also { ctl("add_vhost", it); vhosts += it }

    private fun user(tag: String?, password: String): String {
        val name = "u-${System.nanoTime()}"
        ctl("add_user", name, password)
        if (tag != null) ctl("set_user_tags", name, tag)
        users += name
        return name
    }

    private fun profile(vhost: String, username: String, managementPort: Int = rabbit.httpsPort) = ConnectionProfile(
        name = "perm-test",
        host = rabbit.host,
        virtualHost = vhost,
        username = username,
        streamPort = rabbit.getMappedPort(5551),
        managementPort = managementPort,
        caCertificatePath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath,
    )

    private fun check(profile: ConnectionProfile, password: String) =
        runBlocking { ManagementApiPermissionChecker().check(profile, password) }

    @Test
    fun `an administrator with full permissions gets topology unlocked and publishing enabled`() {
        val vh = vhost()
        val admin = user("administrator", "pw-admin")
        ctl("set_permissions", "-p", vh, admin, ".*", ".*", ".*")
        assertEquals(PermissionCheckResult.Checked(true, true), check(profile(vh, admin), "pw-admin"))
    }

    @Test
    fun `an administrator without configure gets topology locked`() {
        val vh = vhost()
        val admin = user("administrator", "pw-admin")
        ctl("set_permissions", "-p", vh, admin, "", ".*", ".*")
        assertEquals(PermissionCheckResult.Checked(false, true), check(profile(vh, admin), "pw-admin"))
    }

    @Test
    fun `an administrator with only a narrow configure pattern and no write`() {
        val vh = vhost()
        val admin = user("administrator", "pw-admin")
        ctl("set_permissions", "-p", vh, admin, "^orders\\..*", "", ".*")
        assertEquals(PermissionCheckResult.Checked(true, false), check(profile(vh, admin), "pw-admin"))
    }

    @Test
    fun `a non-administrator with full permissions is Unreadable and therefore locked`() {
        // The spike: neither untagged, management nor monitoring users may read permissions (401).
        for (tag in listOf(null, "management", "monitoring")) {
            val vh = vhost()
            val u = user(tag, "pw-user")
            ctl("set_permissions", "-p", vh, u, ".*", ".*", ".*")
            val result = check(profile(vh, u), "pw-user")
            assertTrue(result is PermissionCheckResult.Unreadable, "tag=$tag: expected Unreadable, got $result")
            assertFalse(result.topologyUnlocked, "tag=$tag")
            assertFalse(result.publishingEnabled, "tag=$tag")
        }
    }

    @Test
    fun `a wrong password is Unreadable and the reason does not contain the password`() {
        val vh = vhost()
        val admin = user("administrator", "pw-admin")
        ctl("set_permissions", "-p", vh, admin, ".*", ".*", ".*")
        val result = check(profile(vh, admin), "wrong-password-xyz")
        assertTrue(result is PermissionCheckResult.Unreadable, "got $result")
        assertFalse((result as PermissionCheckResult.Unreadable).reason.contains("wrong-password-xyz"))
        assertFalse(result.reason.contains("pw-admin"))
    }

    @Test
    fun `an administrator with no permissions on the vhost at all is not unlocked`() {
        val vh = vhost()
        val admin = user("administrator", "pw-admin")
        // no set_permissions: GET /api/permissions/{vhost}/{user} answers 404
        val result = check(profile(vh, admin), "pw-admin")
        assertFalse(result.topologyUnlocked, "got $result")
        assertFalse(result.publishingEnabled, "got $result")
    }

    @Test
    fun `a refused connection is Unreadable, not an exception`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val result = check(profile("/", "guest", managementPort = freePort), "guest")
        assertTrue(result is PermissionCheckResult.Unreadable, "got $result")
        assertFalse(result.topologyUnlocked)
    }
}
