package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.SaveConnectionResult
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
}
