package dev.caerbannog.core.migrations

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * M8-04 architecture guards (source greps, the module runs with its own directory as the working directory):
 * `migrations` must not import `migrate3x` (M8-05 makes migrate3x depend on migrations: a cycle otherwise), and `core.json` is plain
 * Kotlin common code (no `java.*`, the module is multiplatform).
 */
class MigrationArchitectureGuardTest {

    private fun sources(subPackage: String): List<File> {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/$subPackage")
        assertTrue(dir.isDirectory, "cannot find ${dir.absolutePath}; the guard must not pass by finding nothing")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources under ${dir.absolutePath}")
        return files
    }

    @Test
    fun `migrations does not import migrate3x`() {
        val offenders = sources("migrations").filter { f -> f.readLines().any { it.contains("core.migrate3x") } }
        assertTrue(offenders.isEmpty(), "these migrations files refer to the migrate3x package: ${offenders.map { it.name }}")
    }

    @Test
    fun `core json has no java imports or fully qualified java use`() {
        val offenders = sources("json").filter { f -> f.readLines().any { Regex("""(^|[^A-Za-z0-9_.])java(x)?\.""").containsMatchIn(it.substringBefore("//")) } }
        assertTrue(offenders.isEmpty(), "these core.json files use java.*: ${offenders.map { it.name }}")
    }

    @Test
    fun `the typed value type lives in core json and not in migrate3x any more`() {
        val migrate3x = sources("migrate3x")
        val stillThere = migrate3x.filter { f -> f.readText().let { Regex("""(sealed )?interface ArgumentValue\b""").containsMatchIn(it) } }
        assertTrue(stillThere.isEmpty(), "ArgumentValue is still declared in migrate3x: ${stillThere.map { it.name }}")
        val json = sources("json")
        assertTrue(json.any { f -> Regex("""sealed interface ArgumentValue\b""").containsMatchIn(f.readText()) && f.name != "M804TestStubs.kt" }, "ArgumentValue must be declared in core.json (not in the M804TestStubs.kt stub)")
    }

    @Test
    fun `the M804 compile stubs are gone`() {
        val stubs = sources("json").filter { it.name == "M804TestStubs.kt" }
        assertTrue(stubs.isEmpty(), "delete the test-first stub file: ${stubs.map { it.path }}")
    }
}
