package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * M2-14b: file-level check, no Docker. The dev compose file publishes the plain ports next to the TLS ones and the
 * shared broker config has the two plain listener lines (spike S-D). Gradle runs tests with the module directory as
 * working directory, so the repository root is its parent.
 */
class DevBrokerPlainListenersTest {
    private val root: Path = Path.of("").toAbsolutePath().parent
    private val compose = root.resolve("dev/broker/docker-compose.yml")
    private val conf = root.resolve("data-rabbit/src/test/resources/tls/rabbitmq.conf")

    private fun activeLines(path: Path): List<String> =
        Files.readAllLines(path).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun published(): List<String> =
        activeLines(compose).filter { it.startsWith("- \"") && ":" in it }.map { it.removePrefix("- ").trim('"') }

    @Test
    fun `compose publishes the plain stream and management ports`() {
        val ports = published()
        assertTrue("5552:5552" in ports, "5552:5552 not published, ports were $ports")
        assertTrue("15672:15672" in ports, "15672:15672 not published, ports were $ports")
    }

    @Test
    fun `compose still publishes the TLS ports and AMQP TLS`() {
        val ports = published()
        for (p in listOf("5551:5551", "15671:15671", "5671:5671")) assertTrue(p in ports, "$p missing, ports were $ports")
    }

    @Test
    fun `compose keeps the named volume for var lib rabbitmq`() {
        val lines = activeLines(compose)
        assertTrue("- rabbitmq-data:/var/lib/rabbitmq" in lines, "named volume for /var/lib/rabbitmq missing (erlang cookie eacces, S-D)")
        assertTrue("rabbitmq-data:" in lines, "top level volume rabbitmq-data missing")
    }

    @Test
    fun `the shared config has the plain listener lines and the TLS ones`() {
        val settings = activeLines(conf).filter { "=" in it }.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        assertEquals("5552", settings["stream.listeners.tcp.default"])
        assertEquals("15672", settings["management.tcp.port"])
        assertEquals("5551", settings["stream.listeners.ssl.default"])
        assertEquals("15671", settings["management.ssl.port"])
    }

    @Test
    fun `the dev compose mounts the shared config`() {
        assertTrue(activeLines(compose).any { "data-rabbit/src/test/resources/tls/rabbitmq.conf:" in it }, "shared conf not mounted")
    }
}
