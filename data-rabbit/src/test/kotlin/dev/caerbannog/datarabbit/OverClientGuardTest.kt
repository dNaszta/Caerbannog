package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M5-08a: `ManagementTopologyMigrationExecutor.overClient` is a documented TEST seam (M9-05 adds `ManagementDeclareCheck.overClient` for the same reason: the acceptance glue cannot see the internal `ManagementHttp`) (the acceptance glue cannot see the internal
 * `ManagementHttp`). No production source of any module may call it; the gateway builds the executor with the internal constructor.
 */
class OverClientGuardTest {

    private val allowedDefinition = setOf("ManagementTopologyMigrationExecutor.kt", "M508aTestStubs.kt", "ManagementDeclareCheck.kt")

    @Test
    fun `no production file calls overClient`() {
        val modules = File("..").listFiles { f -> f.isDirectory && File(f, "build.gradle.kts").exists() }.orEmpty()
        assertTrue(modules.size >= 5, "found only ${modules.map { it.name }}; the guard must not pass by finding nothing")
        val production = modules.flatMap { m ->
            listOf("src/main", "src/commonMain", "src/jvmMain").map { File(m, it) }.filter { it.isDirectory }
        }.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(production.size > 100, "scanned only ${production.size} production files")
        val offenders = production.filter { it.name !in allowedDefinition }
            .filter { f -> f.readLines().any { it.substringBefore("//").contains("overClient") } }
        assertTrue(offenders.isEmpty(), "production code calls the test-only overClient seam: ${offenders.map { it.path }}")
    }
}
