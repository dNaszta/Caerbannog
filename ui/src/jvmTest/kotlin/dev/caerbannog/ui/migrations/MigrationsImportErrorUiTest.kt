package dev.caerbannog.ui.migrations

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-04 round 1 (F1): the import dialog with a real view model. A file with `"name": {}` must end in the error banner with the fixed
 * message, not in an exception of the launched coroutine (the desktop app launches `importFile` the same way, without a handler).
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsImportErrorUiTest {

    private object NoopExecutor : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Success
    }

    @Test
    fun `importing a file whose name is an object shows the fixed error and does not throw`(): Unit = runDesktopComposeUiTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, t -> uncaught += t })
        val vm = MigrationsViewModel(
            fileRepository = InMemoryMigrationFileRepository(),
            runner = MigrationRunner(InMemoryMigrationHistoryRepository(), NoopExecutor),
            scope = scope,
        )

        setContent {
            val state by vm.state.collectAsState()
            MigrationsScreen(
                state = state,
                onSelectVersion = {}, onApplyPending = {}, onApplySingle = {},
                onImportJson = { scope.launch { vm.importFile(it) } },
                onExportTopology = {}, onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = { vm.dismissError() },
            )
        }

        onNodeWithTag("migrations:importBtn").performClick()
        onNodeWithTag("importMigration:input").performTextInput("""{"version":"0009","up":[{"action":"declareQueue","name":{}}]}""")
        onNodeWithTag("importMigration:confirmBtn").performClick()
        waitForIdle()

        assertTrue(uncaught.isEmpty(), "the import threw: ${uncaught.firstOrNull()}")
        onNodeWithTag("migrations:errorBanner").assertIsDisplayed()
        onNodeWithTag("migrations:errorBanner").assertTextContains("the value of \"name\" has the wrong type", substring = true)
        assertEquals(emptyList(), vm.state.value.entries.map { it.file.version })
    }
}
