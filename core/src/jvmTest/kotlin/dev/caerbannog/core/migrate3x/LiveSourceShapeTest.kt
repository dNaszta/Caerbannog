package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-02a: the read port is GET-only by SHAPE (a write method added to it fails here), the source file stays free of platform and HTTP code,
 * and the fake the acceptance suite relies on is honest about what it scripts.
 */
class LiveSourceShapeTest {

    @Test
    fun `the read port has exactly the three read methods`() {
        val methods = ThreeXReads::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("definitions", "storageVersions", "deprecatedFeaturesInUse"), methods, "a method was added to ThreeXReads: is it a read?")
    }

    @Test
    fun `the live source file has no java or http imports and never waits by itself`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val file = File(requireNotNull(dir), "core/src/commonMain/kotlin/dev/caerbannog/core/migrate3x/LiveDefinitionsSource.kt")
        val text = file.readText()
        val imports = text.lines().filter { it.startsWith("import ") }
        assertTrue(imports.none { it.startsWith("import java.") || it.startsWith("import javax.") || "ktor" in it || "paho" in it || "rabbitmq" in it }, "imports: $imports")
        assertFalse(Regex("""Thread\.sleep|[^.\w]delay\(""").containsMatchIn(text), "the source must wait only through the injected pause")
    }

    @Test
    fun `the fake script counts reads per virtual host and returns null before the scripted read`() = runTest {
        val reads = FakeThreeXReads()
        reads.storageVersionAppearsAtRead("/", "q", 3, version = 2)
        val seen = (1..4).map { (reads.storageVersions("/") as ReadOutcome.Ok).value["q"] }
        assertEquals(listOf(null, null, 2, 2), seen)
        assertEquals(4, reads.storageReads("/"))
        assertEquals(0, reads.storageReads("other"))
        reads.storageVersionNeverAppears("/", "n")
        assertEquals(null, (reads.storageVersions("/") as ReadOutcome.Ok).value["n"])
    }
}
