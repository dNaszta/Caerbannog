package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.SecretValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-12c: the redacting definitions reader keeps ONLY the users, in memory, with the hash as a secret; and the source has no stub left. */
class AccessRedactedDefinitionsTest {
    private val defs = """
        {"rabbit_version":"4.1.0",
         "users":[{"name":"svc","password_hash":"HASH-1","hashing_algorithm":"rabbit_password_hashing_sha256","tags":"management,policymaker"},
                  {"name":"other","password_hash":"HASH-2","hashing_algorithm":"rabbit_password_hashing_md5","tags":""}],
         "parameters":[{"component":"shovel","name":"s","vhost":"/","value":{"src-uri":"amqp://u:SHOVEL-PW@old:5672"}},
                       {"component":"federation-upstream","name":"f","vhost":"/","value":{"uri":"amqp://x:FED-PW@far"}}]}
    """.trimIndent()

    @Test
    fun `it keeps the users with hash, algorithm and tags and nothing else`() {
        val r = assertNotNull(RedactedDefinitions.parse(defs), "the definitions were not read")
        val svc = assertNotNull(r.user("svc"))
        assertTrue(svc.hash!!.matches(SecretValue.of("HASH-1")))
        assertEquals("rabbit_password_hashing_sha256", svc.algorithm)
        assertEquals(listOf("management", "policymaker"), svc.tags)
        assertEquals(emptyList(), r.user("other")?.tags)
        assertNull(r.user("nobody"))
    }

    @Test
    fun `no text of it holds a hash or a parameter value`() {
        val r = assertNotNull(RedactedDefinitions.parse(defs))
        val text = listOf(r.toString(), r.user("svc").toString(), r.user("svc")!!.hash.toString())
        text.forEach { assertFalse(it.contains("HASH-") || it.contains("SHOVEL-PW") || it.contains("FED-PW"), it) }
    }

    @Test
    fun `it holds no field that could keep the raw text or a json tree`() {
        val types = RedactedDefinitions::class.java.declaredFields.map { it.type.name }
        assertTrue(types.none { it == "java.lang.String" || it.contains("JsonElement") || it.contains("JsonObject") }, "fields: $types")
    }

    @Test
    fun `a text that is not a definitions export is not read`() {
        assertNull(RedactedDefinitions.parse("not json"))
        assertNull(RedactedDefinitions.parse("[1,2]"))
    }

    @Test
    fun `an export without users has no users`() {
        val r = assertNotNull(RedactedDefinitions.parse("""{"rabbit_version":"4.1.0","queues":[]}"""))
        assertNull(r.user("svc"))
    }

    @Test
    fun `AccessRequests is the one data-rabbit caller of expose and no access stub is left in main`() {
        val dir = File("src/main/kotlin/dev/caerbannog/datarabbit")
        assertTrue(dir.isDirectory)
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val callers = files.filter { f -> f.readLines().any { Regex("""\.expose\(\)""").containsMatchIn(it.substringBefore("//")) } }.map { it.name }
        assertEquals(listOf("AccessRequests.kt"), callers, "expose() callers in data-rabbit main")
        val stubs = files.filter { f -> f.readLines().any { it.contains("M8-12a STUB") || it.contains("M8-12c STUB") } }.map { it.name }
        assertTrue(stubs.isEmpty(), "stub markers left in $stubs")
    }
}
