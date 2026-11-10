package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.UnavailableStreamManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M8-02b scope 2 (and step 4 ruling F1): `MigrationAccess` keeps the displayable version text of the ONE `GET /api/overview` and decides the
 * executor from that same value. An odd text (a space inside, a line break, over 32 characters) is "unknown" everywhere: no executor, the
 * could-not-be-read reason, version null, so no 3.x claim is ever made from it.
 */
class MigrationAccessVersionTest {

    private val paths = CopyOnWriteArrayList<String>()

    private fun access(versionText: String): MigrationAccess {
        val body = """{"rabbitmq_version":${kotlinx.serialization.json.JsonPrimitive(versionText)},"erlang_version":"27"}"""
        val engine = MockEngine { request ->
            paths += request.method.value + " " + request.url.encodedPath
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        return ManagementHttp(HttpClient(engine), "svc", "pw").use { http ->
            runBlocking { withTimeout(10_000) { MigrationAccess.forBroker(http, M508a.requests(), UnavailableStreamManager) } }
        }
    }

    @Test
    fun `3_13_7 is a migration source and the version is kept`() {
        val access = access("3.13.7")
        assertNull(access.executor)
        assertEquals(MigrationAccess.THREE_X, access.unavailableReason)
        assertEquals("3.13.7", access.version)
    }

    @Test
    fun `4_4_0 gives an executor and the version`() {
        val access = access("4.4.0")
        assertNotNull(access.executor)
        assertNull(access.unavailableReason)
        assertEquals("4.4.0", access.version)
    }

    @Test
    fun `the version text is trimmed and a suffix is kept`() {
        assertEquals("3.13.7", access(" 3.13.7 ").version)
        assertEquals("3.13.7-rc.1", access("3.13.7-rc.1").version)
        assertEquals("4.0.0+build", access("4.0.0+build").version)
    }

    @Test
    fun `an odd version text is unknown everywhere, also when it starts with a 3`() {
        val odd33 = "3." + "1".repeat(31) // 33 characters
        assertEquals(33, odd33.length)
        for (text in listOf("3 anything\nx", "3.13.7\nx", odd33, "x", "", "v4.1", "4 beta", "3.13.7\u0000")) {
            val access = access(text)
            assertNull(access.executor, "no executor for [$text]")
            assertEquals(MigrationAccess.UNKNOWN_VERSION, access.unavailableReason, "reason for [$text]")
            assertNull(access.version, "no version for [$text]: an odd text is never a 3.x claim")
        }
    }

    @Test
    fun `the overview is read once`() {
        access("3.13.7")
        assertEquals(listOf("GET /api/overview"), paths.toList())
    }
}
