package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-16 (b): `TlsSupport` sets `jdk.httpclient.keepalive.timeout` in its initialiser. The JDK reads that property once, when its HTTP client class
 * loads, so it only works if no other code creates a JDK client first. The main sources of the five shipped modules (core, data-rabbit, ui,
 * desktopApp, mcp-server) therefore create a JDK `HttpClient`, directly or through Ktor's Java engine, in `TlsSupport.kt` and nowhere else.
 *
 * `dev-seed` is a developer tool that is not shipped, so it is left out on purpose; third-party libraries are not covered (the stream client is
 * Netty, not the JDK client).
 *
 * What counts as "creates" (see [createsJdkClient]): Ktor's `HttpClient(Java)` and the import `io.ktor.client.engine.java`; the type
 * `java.net.http.HttpClient` (not `java.net.http.HttpTimeoutException`, which `ManagementHttp` imports legitimately); `HttpClient.newBuilder` and
 * `newHttpClient`; and a Ktor `HttpClient(` or `HttpClient {` call with no engine argument, because with `ktor-client-java` as the only engine on the
 * classpath Ktor picks the Java engine by itself. A word boundary keeps `TlsSupport.buildHttpClient(` and the `client: HttpClient` parameter out.
 */
class JdkHttpClientCreationGuardTest {

    private val shippedModules = listOf("core", "data-rabbit", "ui", "desktopApp", "mcp-server")

    @Test
    fun `only TlsSupport creates a JDK HttpClient in the shipped modules`() {
        val root = File("..")
        val moduleDirs = shippedModules.map { File(root, it) }
        val missing = moduleDirs.filter { !File(it, "build.gradle.kts").exists() }.map { it.name }
        assertTrue(missing.isEmpty(), "module folders not found from ${File(".").absoluteFile}: $missing; the guard must not pass by finding nothing")
        val sources = moduleDirs.flatMap { module ->
            listOf("src/main", "src/commonMain", "src/jvmMain").map { File(module, it) }.filter { it.isDirectory }
        }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
        assertTrue(sources.size > 100, "scanned only ${sources.size} production files")

        val allowed = File(root, "data-rabbit/src/main/kotlin/dev/caerbannog/datarabbit/TlsSupport.kt").canonicalFile
        assertTrue(allowed.isFile, "the one allowed creator moved or was renamed: $allowed (update this guard together with the move)")
        assertTrue(allowed.readLines().any(::createsJdkClient), "TlsSupport.kt no longer creates a JDK client by a pattern this guard knows: the guard would miss a copy of it")

        val offenders = sources.filter { it.canonicalFile != allowed }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) -> createsJdkClient(line) }.map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
        }
        assertEquals(
            emptyList<String>(), offenders,
            "a JDK HttpClient is created outside TlsSupport.kt; it could load the JDK class before the keep-alive property is set (M2-16 (b)). Use TlsSupport.buildHttpClient",
        )
    }

    // ---- the matcher itself, so the guard above cannot pass by matching nothing ----------------------------------------------------

    @Test
    fun `the matcher flags every way to create a JDK client`() {
        val creating = listOf(
            "    ): HttpClient = HttpClient(Java) {",
            "val c = HttpClient(Java)",
            "val c = HttpClient(engineFactory = Java) { }",
            "import io.ktor.client.engine.java.Java",
            "import io.ktor.client.engine.java.*",
            "import java.net.http.HttpClient",
            "private val c: java.net.http.HttpClient = x",
            "val c = HttpClient.newBuilder().build()",
            "val c = HttpClient . newBuilder()",
            "val c = java.net.http.HttpClient.newHttpClient()",
            "val c = newHttpClient()",
            "val c = HttpClient {",
            "val c = HttpClient()",
            "val c = HttpClient() { expectSuccess = false }",
            "val c = HttpClient({ expectSuccess = false })",
            "listOf(HttpClient { }, 1)",
        )
        val missed = creating.filterNot(::createsJdkClient)
        assertEquals(emptyList<String>(), missed, "the guard would not notice these lines")
    }

    @Test
    fun `the matcher flags neither the factory call nor the type nor a comment`() {
        val harmless = listOf(
            "val c = TlsSupport.buildHttpClient(transport, 5_000)",
            "    fun buildHttpClient(transport: BrokerTransport): HttpClient = build(transport)",
            "import java.net.http.HttpTimeoutException",
            "} catch (e: HttpTimeoutException) {",
            "import io.ktor.client.HttpClient",
            "    private val client: HttpClient,",
            "    client: HttpClient,",
            "fun overClient(client: HttpClient): Executor {",
            "private fun build(): HttpClient {",
            "val c = HttpClient(engine)",
            "val c = HttpClient(engine) { expectSuccess = false }",
            "import io.ktor.client.engine.javascript.Js",
            "// HttpClient(Java) is created in TlsSupport",
            "    // val c = java.net.http.HttpClient.newBuilder()",
            "     * Uses HttpClient.newBuilder() under the hood, see [newHttpClient].",
            "/* HttpClient(Java) */",
            "val x = 1 // HttpClient(Java)",
            "val url = \"http://broker.example/api\" // HttpClient.newBuilder()",
        )
        val flagged = harmless.filter(::createsJdkClient)
        assertEquals(emptyList<String>(), flagged, "the guard would raise a false alarm on these lines")
    }

    @Test
    fun `a URL in a string does not hide a creation after it on the same line`() {
        assertTrue(createsJdkClient("""val c = listOf("http://broker.example", HttpClient.newBuilder().build())"""))
        assertFalse(createsJdkClient("""val u = "http://broker.example""""))
    }
}

private val jdkClientPatterns = listOf(
    Regex("""\bHttpClient\s*\(\s*(engineFactory\s*=\s*)?Java\b"""),
    Regex("""io\.ktor\.client\.engine\.java\b"""),
    Regex("""java\.net\.http\.HttpClient\b"""),
    Regex("""\bHttpClient\s*\.\s*newBuilder\b"""),
    Regex("""\bnewHttpClient\b"""),
)

/** A Ktor `HttpClient(` or `HttpClient {` call with no engine argument; `HttpClient(engine)` has one and is not this. */
private val engineLessHttpClient = Regex("""(?<![\w.])HttpClient\s*(\(\s*\)|\{|\(\s*\{)""")

/** `//` starts a comment unless it follows a colon (`http://`), so a URL in a string does not cut the line short. */
private val lineComment = Regex("""(?<!:)//.*$""")

/** True when [line] of a main source creates a JDK `HttpClient` by any of the ways named in the class comment. Comment text never counts. */
private fun createsJdkClient(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
    val code = lineComment.replace(line, "")
    if (jdkClientPatterns.any { it.containsMatchIn(code) }) return true
    // `fun f(): HttpClient {` names the type before a function body; that is not a call.
    return engineLessHttpClient.findAll(code).any { match -> !code.substring(0, match.range.first).trimEnd().endsWith(":") }
}
