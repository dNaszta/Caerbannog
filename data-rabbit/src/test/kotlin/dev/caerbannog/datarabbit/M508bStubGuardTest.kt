package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/** M5-08b: the test-first compile stubs of `data-rabbit` are gone when the task is done (red until the developer deletes them). */
class M508bStubGuardTest {
    private val root = File("src/main/kotlin/dev/caerbannog/datarabbit")

    @Test
    fun `the M508b stub file is deleted and no STUB marker is left`() {
        assertTrue(root.isDirectory, "cannot find ${root.absolutePath}")
        val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(all.isNotEmpty())
        assertTrue(all.none { it.name == "M508bTestStubs.kt" }, "delete M508bTestStubs.kt")
        val markers = all.filter { f -> f.readLines().any { it.contains("M5-08b STUB") } }
        assertTrue(markers.isEmpty(), "replace the stubs marked 'M5-08b STUB' in ${markers.map { it.name }}")
    }
}
