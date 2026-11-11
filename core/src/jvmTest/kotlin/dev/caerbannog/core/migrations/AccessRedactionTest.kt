package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.SecretValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-12a: a hash is visible in the file and nowhere else (D4: the raw view shows "hash hidden"); `expose()` has a short list of callers. */
class AccessRedactionTest {
    private val hash = "SENTINEL-HASH-aGFzaA=="

    @Test
    fun `redactedRaw replaces the value of every passwordHash, pretty or compact, and keeps the rest`() {
        val pretty = """
            {
              "version": "0001",
              "up": [
                { "action": "declareUser", "name": "a", "passwordHash": "$hash", "hashingAlgorithm": "rabbit_password_hashing_sha256", "tags": ["administrator"] },
                { "action": "declareUser", "name": "b", "passwordHash": "${hash}2", "hashingAlgorithm": "rabbit_password_hashing_md5", "tags": [] }
              ]
            }
        """.trimIndent()
        val compact = pretty.replace(Regex("\\s+"), "")
        for (raw in listOf(pretty, compact)) {
            val shown = redactedRaw(raw)
            assertFalse(shown.contains("SENTINEL"), "a hash is still visible: $shown")
            assertEquals(2, Regex("hash hidden").findAll(shown).count(), shown)
            assertTrue(shown.contains("rabbit_password_hashing_sha256") && shown.contains("rabbit_password_hashing_md5") && shown.contains("\"a\"") && shown.contains("\"b\""), shown)
        }
    }

    @Test
    fun `a text without a hash is unchanged and the checksum stays over the real file`() {
        val raw = MigrationParser.exportToJson("0001", "d", listOf(MigrationAction.DeclareQueue("orders")))
        assertEquals(raw, redactedRaw(raw))
        val withHash = """{ "version": "0002", "up": [ { "action": "declareUser", "name": "a", "passwordHash": "$hash" } ] }"""
        val file = MigrationFile("0002", "", emptyList(), withHash, MigrationParser.computeChecksum(withHash))
        assertEquals(MigrationParser.computeChecksum(file.rawJson), file.checksum)
        assertFalse(redactedRaw(file.rawJson).contains(hash))
        assertTrue(file.rawJson.contains(hash), "the file itself keeps the real value")
    }

    @Test
    fun `SecretValue expose has only the allowed callers in every main source`() {
        val roots = listOf("src/commonMain", "../data-rabbit/src/main", "../mcp-server/src/main", "../ui/src", "../desktopApp/src/main")
            .map { File(it) }.filter { it.isDirectory }
        assertTrue(roots.any { it.path.contains("commonMain") }, "cannot find the core sources from ${File(".").absolutePath}")
        val allowed = setOf("SecretValue.kt", "ForwardingModel.kt", "AccessCodecs.kt", "AccessRequests.kt")
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" && "/test" !in it.path && "Test" !in it.name }.toList() }
            .filter { it.name !in allowed }
            .filter { f -> f.readLines().any { Regex("""\.expose\(\)""").containsMatchIn(it.substringBefore("//")) } }
        assertTrue(offenders.isEmpty(), "SecretValue.expose() is called from: ${offenders.map { it.path }} (allowed: $allowed)")
    }

    @Test
    fun `the codec that writes the hash is the one allowed caller and the scan can find a caller`() {
        val codec = File("src/commonMain/kotlin/dev/caerbannog/core/migrations/AccessCodecs.kt").readText()
        assertTrue(codec.contains(".expose()"), "the access codec writer must be the place that calls expose()")
        assertTrue(Regex("""\.expose\(\)""").containsMatchIn("val x = hash.expose()"))
    }
}
