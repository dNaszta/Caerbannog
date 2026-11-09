package dev.caerbannog.core.migrations

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/** M5-08b: the test-first compile stubs of `core` are gone when the task is done (red until the developer deletes them). */
class M508bStubGuardTest {
    private val root = File("src/commonMain/kotlin/dev/caerbannog/core")

    @Test
    fun `the M508b stub files are deleted and no STUB marker is left`() {
        assertTrue(root.isDirectory, "cannot find ${root.absolutePath}")
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(all.isNotEmpty())
        val stubFiles = all.filter { it.name == "M508bTestStubs.kt" }
        assertTrue(stubFiles.isEmpty(), "delete the test-first stub files: ${stubFiles.map { it.path }}")
        val markers = all.filter { f -> f.readLines().any { it.contains("M5-08b STUB") } }
        assertTrue(markers.isEmpty(), "replace the stubs marked 'M5-08b STUB' in ${markers.map { it.name }}")
    }
}
