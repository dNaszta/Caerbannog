package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** M2-06-F-A: file-level check, no Docker. The Taskfile descriptions of the seed tasks mention --hold. */
class TaskfileHoldTest {
    private val taskfile: Path = Path.of("").toAbsolutePath().parent.resolve("Taskfile.yml")

    private fun desc(task: String): String {
        val lines = Files.readAllLines(taskfile)
        val start = lines.indexOfFirst { it == "  $task:" }
        assertTrue(start >= 0, "task $task not found")
        return lines.drop(start + 1).takeWhile { it.startsWith("   ") }.firstOrNull { it.trim().startsWith("desc:") }.orEmpty()
    }

    @Test
    fun `dev seed description mentions hold`() = assertTrue("--hold" in desc("dev:seed"), "desc was: ${desc("dev:seed")}")

    @Test
    fun `dev seed plain description mentions hold`() = assertTrue("--hold" in desc("dev:seed:plain"), "desc was: ${desc("dev:seed:plain")}")
}
