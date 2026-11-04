package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.serialization.SerializationException
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * docs/tasks/M1-02-connection-profiles.md, Scope: "a JSON-file-backed implementation [of
 * ConnectionProfileRepository] in data-rabbit (jvmMain). File location: ~/.caerbannog/connections.json
 * (engineering call, see Open points)." Definition of done: "No profile password ever appears in the
 * JSON file or in logs."
 *
 * Why this test exists separately from the BDD layer: 01_connections.feature's own scenarios run
 * against an in-memory fake repository (no real file I/O, see acceptance-tests' Design notes), so the
 * "profile file on disk does not contain the password" claim there is only proven structurally
 * (ConnectionProfile has no password field at all). This test proves the REAL file this task writes
 * never contains the password, by actually reading it back as text - the honest version of that claim.
 * Uses a JUnit5 @TempDir so it never touches a developer's real ~/.caerbannog/connections.json.
 *
 * Expected to fail to compile right now: dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
 * (the real repository implementation this task adds, taking the backing file as a constructor
 * parameter so tests can redirect it) does not exist yet, and neither do
 * dev.caerbannog.core.connections.ConnectionProfile/SaveConnectionResult in `core`.
 */
class JsonConnectionProfileRepositoryTest {

    @Test
    fun `add, list, update and delete round trip through a real JSON file`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        val repository = JsonFileConnectionProfileRepository(file)

        val saveResult = repository.add(
            ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer"),
        )
        assertTrue(saveResult is SaveConnectionResult.Saved)
        assertEquals(listOf("uat-eu"), repository.list().map { it.name })

        val duplicate = repository.add(
            ConnectionProfile(name = "uat-eu", host = "another-host.example.com", virtualHost = "/", username = "someone-else"),
        )
        assertTrue(duplicate is SaveConnectionResult.NameAlreadyExists)

        repository.update(repository.list().first().copy(host = "rabbit-uat-renamed.example.com"))
        assertEquals("rabbit-uat-renamed.example.com", repository.list().first().host)

        repository.delete("uat-eu")
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun `a new repository instance reads back what a previous instance wrote`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        JsonFileConnectionProfileRepository(file).add(
            ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer"),
        )

        val reopened = JsonFileConnectionProfileRepository(file)
        assertEquals(listOf("uat-eu"), reopened.list().map { it.name })
    }

    @Test
    fun `the password never appears in the JSON file on disk`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        val repository = JsonFileConnectionProfileRepository(file)

        repository.add(
            ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer"),
        )

        val fileContents = file.readText()
        assertFalse(fileContents.contains("s3cret", ignoreCase = true))
        assertFalse(fileContents.contains("password", ignoreCase = true)) {
            "The connections.json file must not even have a 'password' key, since ConnectionProfile " +
                "carries no password field at all - found in: $fileContents"
        }
    }

    // --- M1-06: declared permissions in the profile file. The old (M1-02) file format has no
    // "declaredPermissions" key; a profile without a declaration must still be written exactly like that.
    // Red state: ConnectionProfile.declaredPermissions is not persisted yet, so the key is never written
    // or read.

    private fun fixtureText(): String =
        checkNotNull(javaClass.getResource("/fixtures/connections-m1-02.json")) { "fixture missing" }.readText()

    private fun prodEu() = ConnectionProfile(name = "prod-eu", host = "rabbit.example", virtualHost = "/", username = "svc-explorer")

    private fun uatEu() = ConnectionProfile(
        name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "uat", username = "svc-uat",
        streamPort = 5552, managementPort = 15672, caCertificatePath = "/etc/ssl/uat-ca.pem",
        topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
    )

    @Test
    fun `a file in the M1-02 format loads with no declaration and all other fields intact`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        file.writeText(fixtureText())

        val loaded = JsonFileConnectionProfileRepository(file).list()

        assertEquals(listOf(prodEu(), uatEu()), loaded)
        assertTrue(loaded.all { it.declaredPermissions == null })
    }

    @Test
    fun `profiles without a declaration are written exactly like the M1-02 format, with no declaredPermissions key`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "connections.json")
        val repository = JsonFileConnectionProfileRepository(file)
        repository.add(prodEu())
        repository.add(uatEu())

        val written = file.readText()

        assertFalse(written.contains("declaredPermissions")) { "no declaration must write no key, got: $written" }
        assertEquals(fixtureText().trim(), written.trim(), "the file for undeclared profiles must stay unchanged")
    }

    @Test
    fun `a file with the declaredPermissions key loads it`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        file.writeText(
            """
            [
                {
                    "name": "prod-eu",
                    "host": "rabbit.example",
                    "virtualHost": "/",
                    "username": "svc-explorer",
                    "streamPort": 5551,
                    "managementPort": 15671,
                    "declaredPermissions": {
                        "configure": false,
                        "write": true
                    }
                }
            ]
            """.trimIndent(),
        )

        assertEquals(
            DeclaredPermissions(configure = false, write = true),
            JsonFileConnectionProfileRepository(file).list().single().declaredPermissions,
        )
    }

    @Test
    fun `a declaration round trips for yes-no and no-yes through a new repository instance`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        val yesNo = DeclaredPermissions(configure = true, write = false)
        val noYes = DeclaredPermissions(configure = false, write = true)
        JsonFileConnectionProfileRepository(file).apply {
            add(prodEu().copy(declaredPermissions = yesNo))
            add(uatEu().copy(declaredPermissions = noYes))
        }

        val reopened = JsonFileConnectionProfileRepository(file).list().associateBy { it.name }

        assertEquals(yesNo, reopened.getValue("prod-eu").declaredPermissions)
        assertEquals(noYes, reopened.getValue("uat-eu").declaredPermissions)
        assertTrue(file.readText().contains("declaredPermissions"))
    }

    @Test
    fun `a profile without a declaration round trips as none, and clearing removes the key again`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        val repository = JsonFileConnectionProfileRepository(file)
        repository.add(prodEu())
        assertNull(JsonFileConnectionProfileRepository(file).list().single().declaredPermissions)

        repository.update(prodEu().copy(declaredPermissions = DeclaredPermissions(true, true)))
        assertEquals(DeclaredPermissions(true, true), JsonFileConnectionProfileRepository(file).list().single().declaredPermissions)

        repository.update(prodEu())
        assertNull(JsonFileConnectionProfileRepository(file).list().single().declaredPermissions)
        assertFalse(file.readText().contains("declaredPermissions"))
    }

    @Test
    fun `an incomplete declaration object is refused, not guessed`(@TempDir tempDir: File) {
        val file = File(tempDir, "connections.json")
        file.writeText(
            """[{"name":"p","host":"h","virtualHost":"/","username":"u","streamPort":5551,"managementPort":15671,""" +
                """"declaredPermissions":{"configure":true}}]""",
        )

        assertFailsWith<SerializationException> { JsonFileConnectionProfileRepository(file).list() }
    }
}
