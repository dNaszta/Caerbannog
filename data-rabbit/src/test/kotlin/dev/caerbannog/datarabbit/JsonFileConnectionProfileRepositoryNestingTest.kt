package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith

/**
 * M5-07 call site #10 (decided by the probe: a 10,000-level array under the `JsonElement` field `tls` overflowed the stack). A too-deep
 * `connections.json` (limit 32) behaves like a corrupt one: `list()` throws `SerializationException` (never an empty list, which would
 * let the next `add` overwrite the user's file), `add`, `update` and `delete` throw the same and leave the file bytes unchanged, and no
 * StackOverflowError (not caught here on purpose). Renamed from `...NestingProbeTest`.
 */
class JsonFileConnectionProfileRepositoryNestingTest {

    @TempDir
    lateinit var dir: File

    private fun profileJson(extra: String) = """[{"name":"p","host":"h","virtualHost":"/","username":"u","streamPort":5551,"managementPort":15671$extra}]"""
    private val newProfile = ConnectionProfile(name = "q", host = "h2", virtualHost = "/", username = "u2")
    private val existing = ConnectionProfile(name = "p", host = "h", virtualHost = "/", username = "u")

    private fun fileWith(text: String) = File(dir, "connections.json").also { it.writeText(text) }

    private fun assertRefusedAndUntouched(text: String) {
        val file = fileWith(text)
        val before = file.readBytes()
        val repository = JsonFileConnectionProfileRepository(file)
        assertFailsWith<SerializationException> { repository.list() }
        assertFailsWith<SerializationException> { repository.add(newProfile) }
        assertFailsWith<SerializationException> { repository.update(existing) }
        assertFailsWith<SerializationException> { repository.delete("p") }
        assertEquals(before.toList(), file.readBytes().toList(), "the file must be unchanged")
    }

    @Test
    fun `a deep array under tls is refused like a corrupt file and the file is untouched`() =
        assertRefusedAndUntouched(profileJson(""","tls":${DeepJson.arrays()}"""))

    @Test
    fun `a deep object under tls is refused like a corrupt file and the file is untouched`() =
        assertRefusedAndUntouched(profileJson(""","tls":${DeepJson.objects()}"""))

    @Test
    fun `a deep value under an unknown key is refused and the file is untouched`() =
        assertRefusedAndUntouched(profileJson(""","unknown":${DeepJson.arrays()}"""))

    @Test
    fun `a file that is one deep array is refused and the file is untouched`() = assertRefusedAndUntouched(DeepJson.arrays())

    @Test
    fun `a tls value nested 32 levels in total still loads`() {
        // root array + profile object + 30 arrays under tls = 32 levels, the limit
        val repository = JsonFileConnectionProfileRepository(fileWith(profileJson(""","tls":${DeepJson.arrays(30)}""")))
        assertEquals(listOf("p"), repository.list().map { it.name })
    }

    @Test
    fun `a tls value nested 33 levels in total is refused`() =
        assertRefusedAndUntouched(profileJson(""","tls":${DeepJson.arrays(31)}"""))
}
