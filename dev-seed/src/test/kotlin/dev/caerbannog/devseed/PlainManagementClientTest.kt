package dev.caerbannog.devseed

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M2-14b: with `--no-tls` the seed's management calls are plain HTTP to the management port (default 15672).
 * A JDK HttpServer on a free loopback port stands in for the plain listener, no Docker. Contract: ManagementClient
 * reads SeedArgs.tls; tls=false means `http://`, TLS stays `https://` (not tested here, no TLS server in this test).
 */
class PlainManagementClientTest {
    private class Seen(val method: String, val path: String, val authorization: String?, val body: String)

    private fun withServer(status: Int = 204, block: (port: Int, seen: List<Seen>) -> Unit) {
        val seen = CopyOnWriteArrayList<Seen>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            seen += Seen(ex.requestMethod, ex.requestURI.rawPath, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
        try {
            block(server.address.port, seen)
        } finally {
            server.stop(0)
        }
    }

    private fun args(port: Int) = SeedArgs.parse(
        listOf("--password", "dev-pw", "--user", "admin", "--host", "localhost", "--management-port", port.toString(), "--no-tls"),
    )

    @Test
    fun `with no-tls the definitions import is a plain HTTP POST with basic auth`() {
        withServer { port, seen ->
            ManagementClient(args(port)).use { it.importDefinitions("{\"queues\":[]}") }
            assertEquals(1, seen.size, "expected exactly one plain request, saw ${seen.size}")
            assertEquals("POST", seen[0].method)
            assertEquals("/api/definitions", seen[0].path)
            assertEquals("{\"queues\":[]}", seen[0].body)
            assertTrue(seen[0].authorization!!.startsWith("Basic "), "basic auth expected, was ${seen[0].authorization}")
        }
    }

    @Test
    fun `with no-tls the retention policy is a plain HTTP PUT`() {
        withServer { port, seen ->
            ManagementClient(args(port)).use { it.setRetentionPolicy("/", listOf("dev-orders"), "7D") }
            assertEquals("PUT", seen.single().method)
            assertEquals("/api/policies/%2F/dev-seed-retention", seen.single().path)
        }
    }

    @Test
    fun `a broker error over plain is reported with its status and without the password`() {
        withServer(status = 401) { port, _ ->
            val e = kotlin.test.assertFailsWith<SeedFailure> {
                ManagementClient(args(port)).use { it.importDefinitions("{}") }
            }
            assertTrue("401" in e.message!!, "message should carry the status: ${e.message}")
            assertTrue("dev-pw" !in e.message!!, "password leaked: ${e.message}")
        }
    }
}
