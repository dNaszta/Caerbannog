package dev.caerbannog.datarabbit

import com.sun.net.httpserver.HttpServer
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.forwarding.UnavailableShovelGateway
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** M8-09 wiring: the real gateway hands a `ManagementShovelGateway` to a 4.x held connection and the unavailable gateway to a 3.x one (a source only, never a shovel destination). */
class RabbitMqConnectionGatewayShovelsTest {
    private class Store : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "pw"
        override fun delete(profileName: String) = Unit
    }

    private var server: HttpServer? = null
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach fun stop() { opened.forEach { runCatching { it.close() } }; server?.stop(0) }

    private fun open(version: String): HeldConnection {
        server?.stop(0)
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10)
        s.createContext("/") { ex ->
            val (status, body) = when (ex.requestURI.rawPath) {
                "/api/whoami" -> 200 to """{"name":"svc","tags":["administrator"]}"""
                "/api/overview" -> 200 to """{"rabbitmq_version":${JsonPrimitive(version)},"erlang_version":"26"}"""
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        val profile = ConnectionProfile(name = "held", host = "127.0.0.1", virtualHost = "/", username = "svc", streamPort = closedLocalPort(), managementPort = s.address.port, tls = false)
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Store()).open(profile) } }
        return (result as OpenResult.Opened).connection.also { opened += it }
    }

    @Test
    fun `a 4x connection gets the management shovel gateway`() {
        for (version in listOf("4.0.2", "4.4.0")) assertInstanceOf(ManagementShovelGateway::class.java, open(version).shovels, version)
    }

    @Test
    fun `a 3x connection gets the unavailable gateway, so it can never be a shovel destination`() {
        for (version in listOf("3.13.7", "3.9")) assertSame(UnavailableShovelGateway, open(version).shovels, version)
    }
}
