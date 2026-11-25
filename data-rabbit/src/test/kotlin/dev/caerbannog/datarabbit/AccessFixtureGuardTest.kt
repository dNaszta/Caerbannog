package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M10-00: no fixture under `fixtures/m10-00/` may hold a token, the spike's password or a key. Fast, no container.
 * A JWT starts with `eyJ` (the base64 of `{"`); the recorder replaces every one with `<JWT>` before it writes.
 */
class AccessFixtureGuardTest {
    private val dir = File("src/test/resources/fixtures/m10-00")

    @Test
    fun `no fixture of M10-00 contains a token, a password or a key`() {
        val files = dir.listFiles { f -> f.isFile }.orEmpty()
        val problems = files.flatMap { f -> findings(f.name, f.readText()) }
        assertTrue(problems.isEmpty(), "Secrets in fixtures:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the sanitiser removes a minted token, its payload, the password and a key`() {
        val token = accessToken("guard-user", listOf("rabbitmq.read:*/*"))
        val text = "token=$token payload=${token.split('.')[1]} password=$ACCESS_PASSWORD key=${AccessKeys.publicPem()} other=eyJhbGciOiJub25lIn0.e30.c2ln"
        val clean = AccessSecrets.sanitise(text)
        assertEquals(emptyList<String>(), findings("sample", clean))
    }

    @Test
    fun `the response scan finds key armour and key material`() {
        val before = AccessSecrets.leaks.size
        AccessSecrets.scan("armour", "x -----BEGIN PUBLIC KEY----- y")
        AccessSecrets.scan("material", "x " + AccessKeys.fingerprints.first() + " y")
        AccessSecrets.scan("clean", "nothing here")
        val added = AccessSecrets.leaks.drop(before)
        AccessSecrets.leaks.removeAll(added.toSet())
        assertEquals(listOf("armour: key armour (BEGIN ... KEY)", "material: signing key material"), added)
    }

    @Test
    fun `the guard itself flags each kind of secret`() {
        assertEquals(1, findings("x", "a eyJhbGciOi b").size)
        assertEquals(1, findings("x", "pw $ACCESS_PASSWORD").size)
        assertEquals(1, findings("x", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----").size)
    }

    private fun findings(name: String, text: String): List<String> = buildList {
        if ("eyJ" in text) add("$name: contains a JWT-shaped text (eyJ)")
        if (ACCESS_PASSWORD in text) add("$name: contains the spike password")
        if (Regex("-----BEGIN [A-Z ]*KEY-----").containsMatchIn(text)) add("$name: contains a key")
    }
}
