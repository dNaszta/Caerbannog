package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M8-04 scope 8: the dry-run row shows `action.detail` as a second line (tag `actionDetail:<index>`) only for an action that has details. */
@OptIn(ExperimentalTestApi::class)
class MigrationsActionDetailUiTest {

    private val queue = MigrationAction.DeclareQueue(
        "orders", "quorum",
        arguments = mapOf(
            "x-delivery-limit" to ArgumentValue.Whole(5),
            "x-message-ttl" to ArgumentValue.Whole(60000),
            "x-dead-letter-exchange" to ArgumentValue.Text("dlx"),
        ),
    )
    private val plainBinding = MigrationAction.DeclareBinding("events", "orders", "order.created")
    private val policy = MigrationAction.DeclarePolicy(
        "limits", "^orders",
        definition = mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish")),
        applyTo = "quorum_queues", priority = 7,
    )

    private fun state(vararg actions: MigrationAction): MigrationsScreenState {
        val file = MigrationFile("0003", "Converted", actions.toList(), """{ "version": "0003" }""", "abc0003")
        return MigrationsScreenState(
            profileName = "uat-eu", selectedVersion = "0003", selectedFile = file,
            dryRun = MigrationDryRun("0003", actions.map { ActionDryRun(it, allowed = true) }, canApply = true),
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: MigrationsScreenState) = setContent {
        MigrationsScreen(
            state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
        )
    }

    @Test
    fun `an action with arguments shows its detail line under the description`(): Unit = runDesktopComposeUiTest {
        show(state(queue, plainBinding, policy))
        onNodeWithTag("actionDetail:0").assertIsDisplayed().assertTextEquals(queue.detail!!)
        onNodeWithTag("actionDetail:0").assertTextContains("x-delivery-limit=5", substring = true)
        onNodeWithTag("actionDetail:0").assertTextContains("x-message-ttl=60000", substring = true)
        onNodeWithTag("actionDetail:0").assertTextContains("x-dead-letter-exchange=dlx", substring = true)
    }

    @Test
    fun `a policy with two definition keys shows them`(): Unit = runDesktopComposeUiTest {
        show(state(queue, plainBinding, policy))
        onNodeWithTag("actionDetail:2").assertIsDisplayed()
        onNodeWithTag("actionDetail:2").assertTextContains("max-length=1000", substring = true)
        onNodeWithTag("actionDetail:2").assertTextContains("overflow=reject-publish", substring = true)
    }

    @Test
    fun `a plain action has no detail line and its row is still there`(): Unit = runDesktopComposeUiTest {
        show(state(queue, plainBinding, policy))
        onNodeWithTag("dryRunAction:1").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTagCount("actionDetail:1"), "a binding without arguments must not get a detail line")
    }

    @Test
    fun `a screen of plain actions has no detail line at all`(): Unit = runDesktopComposeUiTest {
        show(state(MigrationAction.DeclareQueue("orders", "quorum"), plainBinding, MigrationAction.DeleteQueue("old")))
        for (i in 0..2) assertEquals(0, onAllNodesWithTagCount("actionDetail:$i"), "row $i")
        onNodeWithTag("dryRunAction:0").assertIsDisplayed()
    }

    @Test
    fun `the description text of a row is unchanged by the detail`(): Unit = runDesktopComposeUiTest {
        show(state(queue))
        onNodeWithText("Declare quorum queue orders").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
