package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import java.security.cert.CertificateException
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * M5-08a scope 1: `ManagementHttp.put` and `delete` ride the same transport rules as `get` and `post` (Basic auth, the profile's transport,
 * the same error classification, never a copied body), and ONLY the migration executor may call them (a source grep, CLAUDE.md safety rule:
 * management PUT and DELETE only from the migration executor, with a path allow-list).
 */
class ManagementHttpWriteTest {

    private val url = "http://broker.example:15672/api/queues/%2F/orders"

    private fun <T> run(engine: MockEngine, block: suspend (ManagementHttp) -> T): T =
        M508a.http(engine).use { http -> runBlocking { withTimeout(10_000) { block(http) } } }

    // ---- what is sent ----------------------------------------------------------------------------------------------------

    @Test
    fun `put sends a PUT with the JSON body, Basic auth and a JSON content type`() {
        val seen = mutableListOf<M508a.Seen>()
        val reply = run(M508a.answering(201, "", seen)) { it.put(url, """{"durable":true}""") }
        assertEquals(ManagementReply.Response(201, ""), reply)
        val request = seen.single()
        assertEquals("PUT", request.method)
        assertEquals("/api/queues/%2F/orders", request.path)
        assertEquals("""{"durable":true}""", request.body)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:pw".toByteArray()), request.authorization)
        assertTrue(request.contentType.orEmpty().startsWith("application/json"), "content type: ${request.contentType}")
    }

    @Test
    fun `delete sends a DELETE with no body and Basic auth`() {
        val seen = mutableListOf<M508a.Seen>()
        val reply = run(M508a.answering(204, "", seen)) { it.delete(url) }
        assertEquals(ManagementReply.Response(204, ""), reply)
        val request = seen.single()
        assertEquals("DELETE", request.method)
        assertEquals("/api/queues/%2F/orders", request.path)
        assertEquals("", request.body)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:pw".toByteArray()), request.authorization)
    }

    @Test
    fun `the status and body come back as a value for any status`() {
        for (status in listOf(200, 204, 400, 401, 403, 404, 409, 500, 503)) {
            assertEquals(ManagementReply.Response(status, "x"), run(M508a.answering(status, "x")) { it.put(url, "{}") }, "put $status")
            assertEquals(ManagementReply.Response(status, "x"), run(M508a.answering(status, "x")) { it.delete(url) }, "delete $status")
        }
    }

    // ---- classification, the same as get ---------------------------------------------------------------------------------

    private fun failures(): Map<String, Pair<Throwable, ManagementReply>> = mapOf(
        "refused connection" to (IOException("connection refused") to ManagementReply.Unreachable),
        "socket timeout" to (SocketTimeoutException("slow") to ManagementReply.Unreachable),
        "http timeout" to (HttpTimeoutException("slow") to ManagementReply.Unreachable),
        "request timeout" to (HttpRequestTimeoutException("http://x", 1) to ManagementReply.Unreachable),
        "bad address" to (IllegalArgumentException("bad url") to ManagementReply.Unreachable),
        "untrusted certificate" to (SSLException("handshake", CertificateException("PKIX")) to ManagementReply.CertificateRejected),
        "plain broker" to (SSLException("Unrecognized SSL message, plaintext connection?") to ManagementReply.NoTlsAnswer),
    )

    @Test
    fun `put and delete classify failures exactly like get`() {
        for ((name, pair) in failures()) {
            val (failure, expected) = pair
            assertEquals(expected, run(MockEngine { throw failure }) { it.get(url) }, "get: $name")
            assertEquals(expected, run(MockEngine { throw failure }) { it.put(url, "{}") }, "put: $name")
            assertEquals(expected, run(MockEngine { throw failure }) { it.delete(url) }, "delete: $name")
        }
    }

    @Test
    fun `a closed holder answers Unreachable and sends nothing`() {
        val seen = mutableListOf<M508a.Seen>()
        val http = M508a.http(M508a.answering(204, "", seen))
        http.close()
        runBlocking {
            assertEquals(ManagementReply.Unreachable, http.put(url, "{}"))
            assertEquals(ManagementReply.Unreachable, http.delete(url))
        }
        assertTrue(seen.isEmpty(), "a closed connection sent ${seen.size} request(s)")
    }

    @Test
    fun `the credential is not part of any reply value`() {
        val replies = listOf(
            run(M508a.answering(401, "")) { it.put(url, "{}") },
            run(MockEngine { throw IOException("refused for svc:pw") }) { it.delete(url) },
        )
        replies.forEach { assertTrue(!it.toString().contains("pw") || it is ManagementReply.Response, "reply $it") }
        assertEquals(ManagementReply.Unreachable, replies[1], "an exception text is never kept, it could carry a URL or secret")
    }

    // ---- the transport of the profile (D9a): real sockets, TLS on and off -----------------------------------------------------

    private val caPath = File(javaClass.classLoader.getResource("tls/ca.pem")!!.toURI()).absolutePath

    private fun tlsProfile(port: Int, ca: String?) = M508a.profile.copy(host = "127.0.0.1", managementPort = port, tls = true, caCertificatePath = ca)

    private fun <T> overTls(port: Int, ca: String?, block: suspend (ManagementHttp) -> T): T =
        ManagementHttp(TlsSupport.buildHttpClient(BrokerTransport.from(tlsProfile(port, ca)), 10_000), "svc", "pw").use { runBlocking { withTimeout(30_000) { block(it) } } }

    @Test
    fun `over TLS with the profile CA a put reaches the server, with another CA it is CertificateRejected`(@TempDir dir: Path) {
        TlsHttpsServer(dir).use { server ->
            assertEquals(ManagementReply.CertificateRejected, overTls(server.port, caPath) { it.put("https://127.0.0.1:${server.port}/api/queues/%2F/q", "{}") })
            assertEquals(ManagementReply.CertificateRejected, overTls(server.port, null) { it.delete("https://127.0.0.1:${server.port}/api/queues/%2F/q") })
            val reply = overTls(server.port, server.certificatePath) { it.put("https://127.0.0.1:${server.port}/api/queues/%2F/q", "{}") }
            assertEquals(200, (reply as ManagementReply.Response).status)
        }
    }

    @Test
    fun `a TLS profile never falls back to plain, a plain port gives NoTlsAnswer and no HTTP reaches it`() {
        PlainManagementServer(answerGarbageWith400 = true).use { server ->
            assertEquals(ManagementReply.NoTlsAnswer, overTls(server.port, null) { it.put("https://127.0.0.1:${server.port}/api/queues/%2F/q", "{}") })
            assertEquals(ManagementReply.NoTlsAnswer, overTls(server.port, null) { it.delete("https://127.0.0.1:${server.port}/api/queues/%2F/q") })
            assertEquals(emptyList<PlainManagementServer.Seen>(), server.seen, "the credential or body reached a plain port")
        }
    }

    // ---- the caller guard ------------------------------------------------------------------------------------------------

    /** The only classes of `src/main` allowed to call `put` or `delete` of ManagementHttp: the migration executor and its writer. */
    private val allowedCallers = setOf("MigrationWriter.kt", "ManagementTopologyMigrationExecutor.kt")

    @Test
    fun `only the migration executor calls ManagementHttp put or delete`() {
        val dir = File("src/main/kotlin/dev/caerbannog/datarabbit")
        assertTrue(dir.isDirectory, "cannot find ${dir.absolutePath}; the guard must not pass by finding nothing")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 10, "found only ${files.size} sources")
        // a call of put or delete on anything that holds a ManagementHttp, or a ManagementHttp.put/delete reference
        val call = Regex("""\b(http|management\w*|managementHttp|api)\.(put|delete)\s*\(|ManagementHttp\.(put|delete)\b""", RegexOption.IGNORE_CASE)
        val offenders = files.filter { it.name !in allowedCallers && it.name != "ManagementHttp.kt" && it.name != "M508aTestStubs.kt" }
            .filter { f -> f.readLines().any { call.containsMatchIn(it.substringBefore("//")) } }
        assertTrue(offenders.isEmpty(), "management PUT or DELETE from a class that is not the migration executor: ${offenders.map { it.name }}")
    }

    @Test
    fun `the guard itself finds a caller when there is one`() {
        val call = Regex("""\b(http|management\w*|managementHttp|api)\.(put|delete)\s*\(|ManagementHttp\.(put|delete)\b""", RegexOption.IGNORE_CASE)
        assertTrue(call.containsMatchIn("val r = http.put(url, body)"))
        assertTrue(call.containsMatchIn("return managementHttp.delete(url)"))
        assertTrue(!call.containsMatchIn("val x = map.put(key, value)"))
    }

    @Test
    fun `the M508a compile stubs of data-rabbit are gone`() {
        val dir = File("src/main/kotlin/dev/caerbannog/datarabbit")
        val stubs = dir.walkTopDown().filter { it.isFile && it.name == "M508aTestStubs.kt" }.toList()
        assertTrue(stubs.isEmpty(), "delete the test-first stub file: ${stubs.map { it.path }}")
        val markers = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f -> f.readLines().any { it.contains("M5-08a STUB") } }.toList()
        assertTrue(markers.isEmpty(), "stub markers left in ${markers.map { it.name }}")
    }
}
