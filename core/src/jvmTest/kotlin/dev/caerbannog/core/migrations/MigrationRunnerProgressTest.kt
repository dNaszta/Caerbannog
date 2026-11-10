package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08c1 scope 2: `MigrationRunner.applyPending(..., onAction)` tells the caller which action is about to be sent, one call per action,
 * in order, BEFORE the send, never after a failure; the default empty callback changes nothing.
 */
class MigrationRunnerProgressTest {

    /** Writes into one shared [log] so the order of "step" and "send" lines can be asserted. */
    private class LoggingExecutor(private val log: MutableList<String>, private val failOn: String? = null) : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult {
            val name = (action as MigrationAction.DeclareQueue).name
            log += "send $name"
            return if (name == failOn) ActionResult.Failed("the broker refused the request") else ActionResult.Success
        }
    }

    private fun migration(version: String, vararg queues: String): MigrationFile {
        val actions = queues.joinToString(", ") { """{ "action": "declareQueue", "name": "$it" }""" }
        return (MigrationParser.parse("""{ "version": "$version", "description": "d", "up": [ $actions ] }""") as MigrationValidationResult.Valid).migration
    }

    private val log = mutableListOf<String>()

    private fun apply(
        files: List<MigrationFile>,
        executor: TopologyMigrationExecutor = LoggingExecutor(log),
        history: MigrationHistoryRepository = InMemoryMigrationHistoryRepository(),
        permission: Boolean = true,
        level: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL,
        onAction: ((ApplyStep) -> Unit)? = {  },
    ): MigrationApplyResult = runBlocking {
        val runner = MigrationRunner(history, executor)
        if (onAction == null) runner.applyPending("uat-eu", files, permission, level, "/")
        else runner.applyPending("uat-eu", files, permission, level, "/", onAction = onAction)
    }

    @Test
    fun `onAction is called once per action, in order, before each send`() {
        val result = apply(listOf(migration("0003", "a", "b", "c"))) { log += "step ${it.version} ${it.index}/${it.total}" }

        assertIs<MigrationApplyResult.Success>(result)
        assertEquals(
            listOf("step 0003 1/3", "send a", "step 0003 2/3", "send b", "step 0003 3/3", "send c"),
            log,
            "every step must be announced right before its action is sent",
        )
    }

    @Test
    fun `the index starts again at 1 for each migration and the total is that migration's own`() {
        apply(listOf(migration("0002", "x"), migration("0001", "a", "b"))) { log += "step ${it.version} ${it.index}/${it.total}" }

        assertEquals(
            listOf("step 0001 1/2", "send a", "step 0001 2/2", "send b", "step 0002 1/1", "send x"),
            log,
            "pending migrations run in ascending version order",
        )
    }

    @Test
    fun `no step is announced after a failure and the failed action was announced`() {
        val result = apply(
            listOf(migration("0003", "a", "b", "c")),
            executor = LoggingExecutor(log, failOn = "b"),
        ) { log += "step ${it.index}/${it.total}" }

        val failed = assertIs<MigrationApplyResult.ActionFailed>(result)
        assertEquals(2, failed.index)
        assertEquals(listOf("step 1/3", "send a", "step 2/3", "send b"), log, "nothing may follow the failed action, not even a step")
    }

    @Test
    fun `a migration that is already applied announces nothing`() {
        val applied = migration("0001", "a")
        val history = InMemoryMigrationHistoryRepository().also { runBlocking { it.recordApplied("uat-eu", "0001", applied.checksum) } }

        val result = apply(listOf(applied), history = history) { log += "step" }

        assertEquals(MigrationApplyResult.Success(emptyList()), result)
        assertTrue(log.isEmpty(), "nothing is sent and nothing announced: $log")
    }

    @Test
    fun `a blocked apply announces nothing`() {
        assertIs<MigrationApplyResult.Blocked>(apply(listOf(migration("0001", "a")), permission = false) { log += "step" })
        assertIs<MigrationApplyResult.Blocked>(apply(listOf(migration("0001", "a")), level = TopologyControlLevel.READ_ONLY) { log += "step" })
        assertTrue(log.isEmpty(), "a blocked apply must neither send nor announce: $log")
    }

    @Test
    fun `the default empty callback changes nothing`() {
        val files = listOf(migration("0001", "a", "b"), migration("0002", "c"))
        val withHook = mutableListOf<String>()
        val resultWith = apply(files, executor = LoggingExecutor(withHook)) { }
        val resultDefault = apply(files, executor = LoggingExecutor(log), onAction = null)

        assertEquals(resultWith, resultDefault)
        assertEquals(listOf("send a", "send b", "send c"), log)
        assertEquals(log, withHook, "the callback must not change what is sent")
        assertEquals(MigrationApplyResult.Success(listOf("0001", "0002")), resultDefault)
    }
}
