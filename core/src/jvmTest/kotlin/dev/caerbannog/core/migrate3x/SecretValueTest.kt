package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretValueTest {
    private val content = "SENTINEL-HASH-unit-abc123"

    @Test
    fun `toString is the constant`() {
        assertEquals("SecretValue(***)", SecretValue.of(content).toString())
        assertEquals("SecretValue(***)", SecretValue.of("").toString())
    }

    @Test
    fun `string templates and collections show no content`() {
        val secret = SecretValue.of(content)
        assertFalse("$secret".contains("SENTINEL"))
        assertEquals("SecretValue(***)", "$secret")
        assertFalse("${listOf(secret)}".contains("SENTINEL"))
        assertFalse("${mapOf("k" to secret)}".contains("SENTINEL"))
    }

    @Test
    fun `equality is identity and the hash code does not come from the content`() {
        val a = SecretValue.of(content)
        val b = SecretValue.of(content)
        assertNotEquals(a, b)
        assertEquals(a, a)
        assertNotEquals(a.hashCode(), b.hashCode())
        assertNotEquals(content.hashCode(), a.hashCode())
        assertNotEquals("x".hashCode(), SecretValue.of("x").hashCode())
    }

    @Test
    fun `matches compares content`() {
        assertTrue(SecretValue.of(content).matches(SecretValue.of(content)))
        assertFalse(SecretValue.of(content).matches(SecretValue.of("other")))
        assertFalse(SecretValue.of(content).matches(SecretValue.of(content + " ")))
    }

    @Test
    fun `expose returns the text`() = assertEquals(content, SecretValue.of(content).expose())

    @Test
    fun `it is not a data class and has no copy`() {
        // no kotlin-reflect in core (no build change allowed): a data class would have copy, componentN and a content toString.
        assertTrue(SecretValue::class.java.declaredMethods.none { it.name == "copy" || it.name.startsWith("component") })
        assertTrue(SecretValue::class.java.declaredMethods.any { it.name == "toString" && it.parameterCount == 0 })
        assertTrue(SecretValue::class.java.methods.none { it.name == "copy" || it.name.startsWith("component") })
        assertFalse(CharSequence::class.java.isAssignableFrom(SecretValue::class.java))
        assertFalse(Comparable::class.java.isAssignableFrom(SecretValue::class.java))
    }

    @Test
    fun `a user shows the redacted hash`() {
        val user = UserDefinition("svc", SecretValue.of(content), "sha256", listOf("management"), emptyMap())
        assertTrue(user.toString().contains("passwordHash=SecretValue(***)"), user.toString())
        assertFalse(user.toString().contains("SENTINEL"))
        assertFalse("$user".contains("SENTINEL"))
        assertFalse("${listOf(user)}".contains("SENTINEL"))
        assertFalse("${user.passwordHash}".contains("SENTINEL"))
    }

    @Test
    fun `text forms of a parsed shovel fixture hold neither hash nor password`() {
        val result = DefinitionsParser.parse(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER))
        val doc = (result as DefinitionsParseResult.Parsed).document
        val forms = listOf(doc.toString(), "$doc", doc.users.toString(), doc.users.single().toString(), doc.otherParameters.toString(), result.toString())
        forms.forEach {
            assertFalse(it.contains("SENTINEL-HASH"), "hash in: $it")
            assertFalse(it.contains("SENTINEL-PASSWORD"), "password in: $it")
        }
        assertTrue(doc.users.single().toString().contains("passwordHash=SecretValue(***)"))
    }

    @Test
    fun `text forms of the full fixture hold no hash`() {
        val doc = fullFixture()
        assertFalse(doc.toString().contains("SENTINEL-HASH"))
        assertFalse(doc.toString().contains("XXXXXXXX"))
    }
}
