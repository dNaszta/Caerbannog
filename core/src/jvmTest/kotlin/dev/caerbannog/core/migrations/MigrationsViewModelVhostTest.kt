package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08a scenario "A migration for another virtual host is blocked" at view-model level: the screen hands the connection's virtual host to
 * the runner, shows the dry-run reason and disables "Apply pending" (today it is enabled whenever something is pending and the level allows).
 */
class MigrationsViewModelVhostTest {

    private val files = InMemoryMigrationFileRepository()
    private val executor = FakeTopologyMigrationExecutor()

    private fun vm(vhost: String) = MigrationsViewModel(
        fileRepository = files,
        runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor),
        vhost = vhost,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private fun import(vhost: String) = runBlocking {
        files.importFile("""{ "version": "0001", "description": "d", "up": [ { "action": "declareQueue", "name": "orders", "vhost": "$vhost" } ] }""")
    }

    @Test
    fun `a pending migration for another virtual host shows the reason and disables Apply pending`() {
        import("/orders")
        val vm = vm("/")
        vm.loadMigrations()
        val state = vm.state.value
        assertEquals("0001", state.selectedVersion)
        assertFalse(state.isApplyPendingEnabled, "Apply pending must be disabled")
        assertFalse(state.isApplySingleEnabled, "Apply must be disabled")
        val row = state.dryRun!!.actions.single()
        assertFalse(row.allowed)
        assertTrue(row.reason!!.contains("virtual host"), row.reason)
    }

    @Test
    fun `a pending migration for the connection's own virtual host stays enabled`() {
        import("/orders")
        val vm = vm("/orders")
        vm.loadMigrations()
        assertTrue(vm.state.value.isApplyPendingEnabled)
        assertTrue(vm.state.value.dryRun!!.canApply)
    }

    @Test
    fun `applying from the screen for another virtual host is blocked and sends nothing`() = runBlocking {
        import("/orders")
        val vm = vm("/")
        vm.loadMigrations()
        assertIs<MigrationApplyResult.Blocked>(vm.applyPending())
        assertIs<MigrationApplyResult.Blocked>(vm.applySingle("0001"))
        assertTrue(executor.executedActions.isEmpty(), "sent: ${executor.executedActions}")
    }

    @Test
    fun `switching the connection context to the right virtual host unblocks it`() {
        import("/orders")
        val vm = vm("/")
        vm.loadMigrations()
        assertFalse(vm.state.value.isApplyPendingEnabled)
        vm.setConnectionContext("uat-eu", "/orders", true, TopologyControlLevel.FULL_CONTROL)
        assertTrue(vm.state.value.isApplyPendingEnabled)
    }
}
