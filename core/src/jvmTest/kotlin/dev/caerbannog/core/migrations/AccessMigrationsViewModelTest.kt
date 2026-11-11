package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.SecretValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-12a: the Migrations screen view model passes the user's tags to the runner and enables Apply for an administrator without the configure permission. */
class AccessMigrationsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val user = MigrationAction.DeclareUser("svc", SecretValue.of("NEW-HASH"), "rabbit_password_hashing_sha256", listOf("management"))
    private val needsTag = "Creating virtual hosts, users and permissions needs the administrator tag"
    private val needsFull = "Creating virtual hosts, users and permissions needs the level Full control"

    private fun accessFile() = MigrationFile("0001", "access", listOf(MigrationAction.DeclareVhost("/orders"), user), "{}", "c1")

    private fun vm(tags: Set<String>, level: TopologyControlLevel = TopologyControlLevel.FULL_CONTROL, executor: FakeTopologyMigrationExecutor = FakeTopologyMigrationExecutor()): MigrationsViewModel {
        val files = InMemoryMigrationFileRepository().also { runBlocking { it.addFile(accessFile()) } }
        return MigrationsViewModel(
            fileRepository = files, runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor),
            profileName = "new-eu", vhost = "/", hasConfigurePermission = false, controlLevel = level, userTags = tags, scope = scope,
        )
    }

    @Test
    fun `an administrator at Full control can apply an access file although the configure permission is missing on the connection's vhost`() {
        val state = vm(setOf("administrator")).state.value
        assertEquals(true, state.dryRun?.canApply, "dry run: ${state.dryRun}")
        assertTrue(state.isApplyPendingEnabled, "Apply pending must be enabled for an administrator at Full control")
    }

    @Test
    fun `without the administrator tag Apply pending is disabled and the dry run says why`() {
        val state = vm(emptySet()).state.value
        assertFalse(state.isApplyPendingEnabled)
        assertEquals(needsTag, state.dryRun?.blockedReason)
    }

    @Test
    fun `at Declare and edit Apply pending is disabled and the dry run names the level`() {
        val state = vm(setOf("administrator"), TopologyControlLevel.DECLARE_AND_EDIT).state.value
        assertFalse(state.isApplyPendingEnabled)
        assertEquals(needsFull, state.dryRun?.blockedReason)
    }

    @Test
    fun `the tags arrive with the connection context`() {
        val viewModel = vm(emptySet())
        viewModel.setConnectionContext("new-eu", "/", false, TopologyControlLevel.FULL_CONTROL, setOf("administrator"))
        assertTrue(viewModel.state.value.isApplyPendingEnabled)
    }

    @Test
    fun `a conflict on a user stops the apply and the outcome names the user, never a hash`() = runBlocking<Unit> {
        val executor = FakeTopologyMigrationExecutor().also { it.existingUser("svc", "OLD-HASH") }
        val viewModel = vm(setOf("administrator"), executor = executor)
        viewModel.applyPending()
        val outcome = assertIs<ApplyOutcome.Conflict>(viewModel.state.value.applyOutcome, "outcome: ${viewModel.state.value.applyOutcome}")
        assertEquals("svc", outcome.resource)
        assertFalse(outcome.toString().contains("OLD-HASH") || outcome.toString().contains("NEW-HASH"))
    }
}
