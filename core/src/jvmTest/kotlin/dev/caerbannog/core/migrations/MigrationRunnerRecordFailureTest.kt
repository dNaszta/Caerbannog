package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08b round 1 (F7): `recordApplied` can fail AFTER the broker changes were made. The runner turns IllegalStateException (history
 * unreadable) and IOException (write failed) into [MigrationApplyResult.NotRecorded] and never throws them; anything else still propagates.
 */
class MigrationRunnerRecordFailureTest {

    private class FailingHistory(private val failure: Throwable?, private val failOnVersion: String? = null) : MigrationHistoryRepository {
        val recorded = mutableMapOf<String, String>()
        override suspend fun getAppliedMigrations(profileName: String): Map<String, String> = recorded.toMap()
        override suspend fun recordApplied(profileName: String, version: String, checksum: String) {
            if (failure != null && (failOnVersion == null || failOnVersion == version)) throw failure
            recorded[version] = checksum
        }
    }

    private fun migration(version: String): MigrationFile {
        val raw = """{ "version": "$version", "description": "d", "up": [ { "action": "declareQueue", "name": "q$version" } ] }"""
        return (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
    }

    private val executor = FakeTopologyMigrationExecutor()

    private fun apply(history: MigrationHistoryRepository, vararg versions: String): MigrationApplyResult = runBlocking {
        MigrationRunner(history, executor).applyPending(
            "uat-eu", versions.map(::migration), true, TopologyControlLevel.FULL_CONTROL, "/",
        )
    }

    @Test
    fun `an unreadable history after the broker changes gives NotRecorded, not an exception`() {
        val result = apply(FailingHistory(IllegalStateException("SENTINEL-state the history file is unreadable")), "0001")

        val notRecorded = assertIs<MigrationApplyResult.NotRecorded>(result)
        assertEquals("0001", notRecorded.version)
        assertTrue(notRecorded.reason.isNotBlank())
        assertFalse(notRecorded.reason.contains("SENTINEL"), "the reason is fixed text, not the exception message: ${notRecorded.reason}")
        assertEquals(1, executor.executedActions.size, "the broker change was made: the result must say applied but not recorded")
    }

    @Test
    fun `a failed history write after the broker changes gives NotRecorded, not an exception`() {
        val result = apply(FailingHistory(IOException("SENTINEL-disk /home/u/x")), "0001")

        val notRecorded = assertIs<MigrationApplyResult.NotRecorded>(result)
        assertEquals("0001", notRecorded.version)
        assertFalse(notRecorded.reason.contains("SENTINEL") || notRecorded.reason.contains("/home"))
    }

    @Test
    fun `when the second migration cannot be recorded the first stays recorded and the third is not run`() {
        val history = FailingHistory(IOException("x"), failOnVersion = "0002")

        val result = apply(history, "0001", "0002", "0003")

        val notRecorded = assertIs<MigrationApplyResult.NotRecorded>(result)
        assertEquals("0002", notRecorded.version)
        assertEquals(setOf("0001"), history.recorded.keys)
        assertEquals(listOf("q0001", "q0002"), executor.executedActions.map { (it as MigrationAction.DeclareQueue).name })
    }

    @Test
    fun `any other exception from the history still propagates`() {
        assertFailsWith<UnsupportedOperationException> { apply(FailingHistory(UnsupportedOperationException("boom")), "0001") }
        assertFailsWith<IllegalArgumentException> { apply(FailingHistory(IllegalArgumentException("boom")), "0001") }
        assertFailsWith<RuntimeException> { apply(FailingHistory(RuntimeException("boom")), "0001") }
    }

    @Test
    fun `a cancellation while recording keeps cancelling and is never turned into NotRecorded`() {
        // On the JVM a CancellationException is an IllegalStateException: the narrow catch must not swallow it.
        assertFailsWith<kotlinx.coroutines.CancellationException> { apply(FailingHistory(kotlinx.coroutines.CancellationException("cancelled")), "0001") }
    }

    @Test
    fun `subclasses of IOException are caught too and an Error still propagates`() {
        for (failure in listOf(java.nio.file.AccessDeniedException("x"), java.nio.file.NoSuchFileException("x"), java.nio.file.FileSystemException("x"), java.io.FileNotFoundException("x"))) {
            assertIs<MigrationApplyResult.NotRecorded>(apply(FailingHistory(failure), "0001"), "$failure should give NotRecorded")
        }
        assertFailsWith<OutOfMemoryError> { apply(FailingHistory(OutOfMemoryError("x")), "0001") }
    }

    @Test
    fun `the view model shows the fixed text for NotRecorded, from apply pending and from apply single`() = runBlocking {
        val files = InMemoryMigrationFileRepository().also { it.addFile(migration("0001")) }
        val vm = MigrationsViewModel(
            fileRepository = files,
            runner = MigrationRunner(FailingHistory(IOException("SENTINEL")), executor),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val text = "The migration was applied but could not be recorded; running it again is safe"

        val pending = vm.applyPending()
        assertIs<MigrationApplyResult.NotRecorded>(pending)
        assertEquals(text, vm.state.value.errorMessage)
        assertFalse(vm.state.value.isApplying)

        vm.dismissError()
        val single = vm.applySingle("0001")
        assertIs<MigrationApplyResult.NotRecorded>(single)
        assertEquals(text, vm.state.value.errorMessage)
    }
}
