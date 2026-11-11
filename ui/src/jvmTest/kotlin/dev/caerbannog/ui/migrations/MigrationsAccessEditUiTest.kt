package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M8-12a round 1 (decision B): a file that holds a password hash has no Edit action, the raw editor would show the hash; a fixed text stands in its place. */
@OptIn(ExperimentalTestApi::class)
class MigrationsAccessEditUiTest {
    private val text = "This file holds password hashes. Edit it outside the app."

    private fun state(vararg actions: MigrationAction): MigrationsScreenState {
        val file = MigrationFile("0001", "access", actions.toList(), """{ "version": "0001" }""", "abc0001")
        return MigrationsScreenState(
            profileName = "new-eu", selectedVersion = "0001", selectedFile = file,
            dryRun = MigrationDryRun("0001", actions.map { ActionDryRun(it, allowed = true) }, canApply = true),
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: MigrationsScreenState) = setContent {
        MigrationsScreen(
            state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
        )
    }

    @Test
    fun `a file with a declareUser has no Edit button and says to edit it outside the app`(): Unit = runDesktopComposeUiTest {
        show(state(MigrationAction.DeclareVhost("/orders"), MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH"), "rabbit_password_hashing_sha256")))
        assertEquals(0, onAllNodesWithTag("migrations:editFileBtn").fetchSemanticsNodes().size, "the Edit button is offered for a file that holds a hash")
        onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun `a topology file keeps its Edit button and shows no such text`(): Unit = runDesktopComposeUiTest {
        show(state(MigrationAction.DeclareQueue("orders", "quorum")))
        onNodeWithTag("migrations:editFileBtn").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText(text).fetchSemanticsNodes().size)
    }
}
