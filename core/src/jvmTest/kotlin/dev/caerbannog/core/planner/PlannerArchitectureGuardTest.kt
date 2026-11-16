package dev.caerbannog.core.planner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M9-02 architecture guard (a source scan in the style of `MigrationArchitectureGuardTest`; the module runs with its own directory as the working
 * directory). The planner is a front end on the migration files (spec A7): no file under `core/planner` may name the executor that changes a
 * broker, the runner's apply, a publisher or the shovel gateway, and `core` keeps its module rule (no `java.*`, no RabbitMQ or Paho).
 */
class PlannerArchitectureGuardTest {

    private fun sources(): List<File> {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/planner")
        assertTrue(dir.isDirectory, "cannot find ${dir.absolutePath}; the guard must not pass by finding nothing")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { assertTrue(it.isNotEmpty()) }
    }

    /** [patterns] are regular expressions, so `\bFile\(` does not match `MigrationFile(`. */
    private fun offenders(vararg patterns: String): List<String> = sources().flatMap { f ->
        val regexes = patterns.map { Regex(it) }
        f.readLines().withIndex().filter { (_, line) -> regexes.any { it.containsMatchIn(line.substringBefore("//")) } }
            .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
    }

    @Test
    fun `the planner names no executor, no apply and no publisher`() {
        val bad = offenders("TopologyMigrationExecutor", "applyPending", "applySingle", "MigrationRunner", "MessagePublisher", "ShovelGateway", "QueueMessageInspector")
        assertTrue(bad.isEmpty(), "the planner must not have a path to the broker: $bad")
    }

    @Test
    fun `the planner writes a file only through the migration repository and the draft store`() {
        val bad = offenders("""java\.io""", """java\.nio""", """\bFile\(""", """\bwriteText\b""", "ManagementHttp", "HttpClient")
        assertTrue(bad.isEmpty(), "the planner has no other file or network path (A7): $bad")
    }

    @Test
    fun `core planner has no java, rabbit or paho imports`() {
        val bad = sources().flatMap { f ->
            f.readLines().filter { Regex("""^\s*import\s+(java|javax|com\.rabbitmq|org\.eclipse\.paho)\.""").containsMatchIn(it) }.map { "${f.name}: $it" }
        }
        assertTrue(bad.isEmpty(), "core is platform free: $bad")
    }

    @Test
    fun `the view model and the draft store interface are in the planner package`() {
        assertTrue(sources().any { f -> Regex("""class PlannerViewModel\b""").containsMatchIn(f.readText()) }, "PlannerViewModel is declared in core/planner")
        assertTrue(sources().any { f -> Regex("""interface PlanDraftStore\b""").containsMatchIn(f.readText()) }, "PlanDraftStore is declared in core/planner")
    }
}
