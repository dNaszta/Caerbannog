package dev.caerbannog.ui.appframe

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionControlTexts
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-06c: the popup row of a connected 3.x connection offers "Migrate from 3.x" (tag `connection-popup-migrate3x`), before Disconnect, in the focus group;
 * no other row offers it. State in, events out (`ConnectionControlEvents.openMigrateFromThree`); the rule is core's (`ConnectionRowState.offersMigration`).
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionControlMigrationUiTest {

    private class Recorder : ConnectionControlEvents {
        val events = mutableListOf<String>()
        override fun connect(name: String) { events += "connect:$name" }
        override fun disconnect(name: String) { events += "disconnect:$name" }
        override fun retry(name: String) { events += "retry:$name" }
        override fun setRefreshInterval(interval: RefreshInterval) { events += "interval:${interval.seconds}" }
        override fun refreshNow() { events += "refreshNow" }
        override fun openConnections() { events += "openConnections" }
        override fun openMigrateFromThree() { events += "openMigrateFromThree" }
    }

    private val chip = ConnectionChipState(
        ChipState.Connected, ChipIcon.Link, "legacy-eu", null, PermissionPill(ConnectionNotes.MIGRATION_SOURCE_PILL, PillTone.WARNING), null,
        "Connection legacy-eu, Connected, rabbitmq 3.x: migration source only. Open connection menu", errorBorder = false,
    )

    private val state = ConnectionControlState(
        chip = chip,
        rows = listOf(
            ConnectionRowState("legacy-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true, offersMigration = true),
            ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false),
        ),
        emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every30, enabled = true, note = "Updated 12 s ago"),
    )

    private fun DesktopComposeUiTest.open(state: ConnectionControlState): Recorder {
        val recorder = Recorder()
        setContent { ConnectionControl(state, recorder) }
        onNodeWithTag("connectionChip").performClick()
        waitForIdle()
        onNodeWithTag("connectionPopup").assertExists("the popup must be open")
        return recorder
    }

    @Test
    fun `the connected 3x row offers Migrate from 3x, and choosing it calls the event once`() = runDesktopComposeUiTest(1280, 600) {
        val recorder = open(state)
        onNodeWithTag("connection-popup-migrate3x").assertTextContains("Migrate from 3.x")
        assertEquals("Migrate from 3.x", ConnectionControlTexts.MIGRATE_FROM_3X)
        onNodeWithTag("connection-popup-migrate3x").performClick()
        assertEquals(listOf("openMigrateFromThree"), recorder.events)
    }

    @Test
    fun `only that row has the entry, and it sits before the Disconnect button of its row`() = runDesktopComposeUiTest(1280, 600) {
        open(state)
        onAllNodesWithTag("connection-popup-migrate3x", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("connection-popup-migrate3x").assert(hasAnyAncestor(hasTestTag("popupRow:legacy-eu")))
        val migrate = onNodeWithTag("connection-popup-migrate3x").fetchSemanticsNode().boundsInRoot
        val disconnect = onNodeWithTag("popupAction:legacy-eu").fetchSemanticsNode().boundsInRoot
        assertTrue(migrate.right <= disconnect.left + 0.5f, "Migrate from 3.x is before Disconnect: $migrate, $disconnect")
    }

    @Test
    fun `a row that does not offer the migration has no entry, whatever its state`() = runDesktopComposeUiTest(1280, 600) {
        val rows = listOf(
            ConnectionRowState("prod-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true),
            ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false),
            ConnectionRowState("staging-eu", ChipIcon.Error, "Failed: Credentials were refused", RowAction.Retry, current = false),
            ConnectionRowState("local-test", ChipIcon.Sync, "Connecting", RowAction.None, current = false),
        )
        open(state.copy(rows = rows, chip = chip.copy(text = "prod-eu", pill = null)))
        onAllNodesWithTag("connection-popup-migrate3x", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `keyboard - the entry takes the entry focus of the popup, and Space activates it`() = runDesktopComposeUiTest(1280, 600) {
        val recorder = open(state)
        onNode(SemanticsMatcher("focused node in the popup") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true }, useUnmergedTree = true)
            .assert(hasTestTag("connection-popup-migrate3x"))
        onNodeWithTag("connection-popup-migrate3x").performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("openMigrateFromThree"), recorder.events)
    }

    @Test
    fun `keyboard - the entry takes focus inside the popup and Enter chooses it`() = runDesktopComposeUiTest(1280, 600) {
        val recorder = open(state)
        onNodeWithTag("connection-popup-migrate3x").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
        onNodeWithTag("connection-popup-migrate3x").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("connection-popup-migrate3x").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("openMigrateFromThree"), recorder.events)
    }
}
