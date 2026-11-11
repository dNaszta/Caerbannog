package dev.caerbannog.core.forwarding

import dev.caerbannog.core.forwarding.fakes.ShovelCall
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-07: the safety shape. The gateway has exactly four operations and the forwarding package imports nothing from java. */
class ShovelGatewayShapeTest {
    @Test
    fun `the gateway declares exactly dryRun, start, status and remove`() {
        val names = ShovelGateway::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("dryRun", "start", "status", "remove"), names, "the gateway has an operation it must not have (or lacks one)")
    }

    @Test
    fun `no operation can get, consume, acknowledge, reject, purge or delete a queue message or a queue`() {
        val forbidden = Regex("(get|consume|ack|nack|reject|purge|delete|list|parameters)", RegexOption.IGNORE_CASE)
        ShovelGateway::class.java.methods.forEach { assertFalse(forbidden.containsMatchIn(it.name), "forbidden operation ${it.name}") }
    }

    @Test
    fun `the calls the fake records are of the four kinds only`() {
        val kinds = ShovelCall::class.java.permittedSubclasses.map { it.simpleName }.toSet()
        assertEquals(setOf("DryRun", "Start", "Status", "Remove"), kinds)
    }

    @Test
    fun `the forwarding sources have no java dot import and no message-level function`() {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/forwarding")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty().toList()
        assertTrue(files.size >= 7, "the forwarding sources were not found in ${dir.absolutePath}")
        for (f in files) {
            val text = f.readText()
            assertFalse(Regex("(?m)^\\s*import\\s+java\\.").containsMatchIn(text), "${f.name} imports java.*")
            assertFalse(Regex("\\bjava\\.").containsMatchIn(text.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")), "${f.name} uses java.*")
        }
        val gateway = files.first { it.name == "ShovelGateway.kt" }.readText()
        assertFalse(Regex("fun\\s+(get|consume|ack|nack|reject|purge|delete)\\w*\\(").containsMatchIn(gateway), "a message-level function in ShovelGateway.kt")
    }
}
