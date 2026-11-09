package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * M5-07 call site #10 PROBE (brief: "the test developer adds a 10,000-level probe test; if it passes today, OUT; if it overflows, guard
 * like #9"). `profiles.json` is decoded into `@Serializable` DTOs, but the DTO field `tls` is a `JsonElement`, so a deep value there goes
 * through the tree reader. Each probe asserts only "no StackOverflowError" (not caught: JUnit reports it); an ordinary
 * SerializationException is today's behaviour for a corrupt file and is accepted here.
 */
class JsonFileConnectionProfileRepositoryNestingProbeTest {

    @TempDir
    lateinit var dir: File

    private fun profileJson(extra: String) =
        """[{"name":"p","host":"h","virtualHost":"/","username":"u"$extra}]"""

    private fun listSurvives(text: String) {
        val file = File(dir, "connections.json").also { it.writeText(text) }
        try {
            JsonFileConnectionProfileRepository(file).list()
        } catch (e: kotlinx.serialization.SerializationException) {
            // a corrupt file: existing behaviour, not the subject of this probe
        } catch (e: IllegalArgumentException) {
            // same
        }
        assertTrue(true)
    }

    @Test
    fun `probe - a deep value under the JsonElement field tls`() = listSurvives(profileJson(""","tls":${DeepJson.arrays()}"""))

    @Test
    fun `probe - a deep object under the JsonElement field tls`() = listSurvives(profileJson(""","tls":${DeepJson.objects()}"""))

    @Test
    fun `probe - a deep value under an unknown key`() = listSurvives(profileJson(""","unknown":${DeepJson.arrays()}"""))

    @Test
    fun `probe - the whole file is a deep array`() = listSurvives(DeepJson.arrays())
}
