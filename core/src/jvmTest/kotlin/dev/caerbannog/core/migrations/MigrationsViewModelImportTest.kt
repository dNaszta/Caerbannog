package dev.caerbannog.core.migrations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-04 round 1: importing an untrusted file with a wrong-typed value shows an error, never throws, and changes nothing. */
class MigrationsViewModelImportTest {

    private object NoopExecutor : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Success
    }

    private val files = InMemoryMigrationFileRepository()
    private val vm = MigrationsViewModel(
        fileRepository = files,
        runner = MigrationRunner(InMemoryMigrationHistoryRepository(), NoopExecutor),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val good = """{ "version": "0001", "description": "ok", "up": [ { "action": "declareQueue", "name": "q" } ] }"""
    private val wrongTyped = """{ "version": "0002", "up": [ { "action": "declareQueue", "name": { "s": "SENTINEL-Vm" } } ] }"""

    @Test
    fun `importing a file with a wrong-typed value returns Invalid, shows the error and leaves the list unchanged`() = runBlocking {
        vm.importFile(good)
        val before = vm.state.value.entries.map { it.file.version }
        assertEquals(listOf("0001"), before)

        val result = vm.importFile(wrongTyped)

        assertEquals(MigrationValidationResult.Invalid("the value of \"name\" has the wrong type"), result)
        assertEquals("the value of \"name\" has the wrong type", vm.state.value.errorMessage)
        assertTrue(!vm.state.value.errorMessage!!.contains("SENTINEL"))
        assertEquals(before, vm.state.value.entries.map { it.file.version })
        assertEquals(listOf("0001"), files.listFiles().map { it.version })
    }

    @Test
    fun `a dot name import is refused the same way`() = runBlocking {
        val result = vm.importFile("""{ "version": "0003", "up": [ { "action": "declareQueue", "name": ".." } ] }""")
        assertEquals(MigrationValidationResult.Invalid("a name must not be \".\" or \"..\""), result)
        assertTrue(files.listFiles().isEmpty())
        assertNull(vm.state.value.selectedVersion)
    }

    @Test
    fun `updating a file with a wrong-typed value is refused and keeps the stored file`() = runBlocking {
        vm.importFile(good)
        val result = files.updateFile("0001", """{ "version": "0001", "up": [ { "action": "declareQueue", "name": [] } ] }""")
        assertTrue(result is MigrationValidationResult.Invalid, "got $result")
        assertEquals(good, files.listFiles().single().rawJson)
    }
}
