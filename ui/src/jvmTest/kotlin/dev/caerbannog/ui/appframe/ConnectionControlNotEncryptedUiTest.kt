package dev.caerbannog.ui.appframe

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-14a step 2 (tests first, red until built): the "Not encrypted" warning on the app bar chip and in the popup rows (decision O1: shown in
 * EVERY state of a plain profile). State in, no events: the model is core's (`ConnectionControlNotEncryptedTest`). Contract, extending
 * `ConnectionControlUiTest`: `ConnectionChipState.notEncrypted` draws a node tagged "chipNotEncrypted" INSIDE "connectionChip" with the visible
 * text "Not encrypted" and `icon:lock_open` (text and icon, never colour only); a row with `notEncrypted` draws "popupNotEncrypted:<name>" inside
 * "popupRow:<name>" the same way. The chip's contentDescription is `accessibleLabel` as before (core puts "Not encrypted" in it).
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionControlNotEncryptedUiTest {

    private class Quiet : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    private fun chip(kind: ChipState, icon: ChipIcon, notEncrypted: Boolean, name: String = "dev-local", stateText: String? = null) = ConnectionChipState(
        kind, icon, name, stateText, pill = null, tooltip = null,
        accessibleLabel = "Connection $name, ${kind.javaClass.simpleName}${if (notEncrypted) ", Not encrypted" else ""}. Open connection menu",
        errorBorder = kind == ChipState.Failed, notEncrypted = notEncrypted,
    )

    private fun state(chip: ConnectionChipState, vararg rows: ConnectionRowState) = ConnectionControlState(
        chip = chip, rows = rows.toList(), emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every5, enabled = false, note = "Connect to refresh"),
    )

    private fun DesktopComposeUiTest.show(state: ConnectionControlState) = setContent { ConnectionControl(state, Quiet()) }

    private fun DesktopComposeUiTest.has(tag: String, under: String? = null) = onAllNodes(
        hasTestTag(tag).let { m -> if (under == null) m else m.and(hasAnyAncestor(hasTestTag(under))) }, useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    private val everyState = listOf(
        Triple("Connecting", ChipState.Connecting, ChipIcon.Sync),
        Triple("Connected", ChipState.Connected, ChipIcon.Link),
        Triple("Failed", ChipState.Failed, ChipIcon.Error),
        Triple("Disconnected", ChipState.Disconnected, ChipIcon.LinkOff),
    )

    @Test
    fun `the chip of a plain profile shows the text Not encrypted with a lock_open icon in every state`() {
        for ((label, kind, icon) in everyState) {
            runDesktopComposeUiTest(1280, 400) {
                show(state(chip(kind, icon, notEncrypted = true, stateText = "Disconnected".takeIf { kind == ChipState.Disconnected })))

                onNode(hasTestTag("chipNotEncrypted").and(hasAnyAncestor(hasTestTag("connectionChip"))).and(hasText("Not encrypted")), useUnmergedTree = true)
                    .assertExists("$label: the chip must say Not encrypted")
                assertTrue(has("icon:lock_open", under = "chipNotEncrypted"), "$label: the warning has its icon")
            }
        }
    }

    @Test
    fun `the chip of a TLS profile has no warning`() {
        for ((label, kind, icon) in everyState) {
            runDesktopComposeUiTest(1280, 400) {
                show(state(chip(kind, icon, notEncrypted = false)))

                assertTrue(!has("chipNotEncrypted"), "$label: no warning on a TLS profile")
                assertTrue(!has("icon:lock_open"), "$label: no lock_open icon on a TLS profile")
            }
        }
    }

    @Test
    fun `the chip with no connection has no warning`() = runDesktopComposeUiTest(1280, 400) {
        show(state(chip(ChipState.NoConnection, ChipIcon.LinkOff, notEncrypted = false, name = "No connection")))
        assertTrue(!has("chipNotEncrypted"))
    }

    @Test
    fun `the accessible label of a plain chip carries Not encrypted`() = runDesktopComposeUiTest(1280, 400) {
        show(state(chip(ChipState.Connected, ChipIcon.Link, notEncrypted = true)))

        val description = onNodeWithTag("connectionChip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Connection dev-local, Connected, Not encrypted. Open connection menu"), description)
    }

    @Test
    fun `popup rows of plain profiles show the warning with an icon, rows of TLS profiles do not`() = runDesktopComposeUiTest(1280, 600) {
        show(
            state(
                chip(ChipState.NoConnection, ChipIcon.LinkOff, notEncrypted = false, name = "No connection"),
                ConnectionRowState("dev-local", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false, notEncrypted = true),
                ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false, notEncrypted = false),
                ConnectionRowState("dev-failed", ChipIcon.Error, "Failed: The broker could not be reached", RowAction.Retry, current = false, notEncrypted = true),
            ),
        )
        onNodeWithTag("connectionChip").performClick()
        waitForIdle()

        onNode(hasTestTag("popupNotEncrypted:dev-local").and(hasAnyAncestor(hasTestTag("popupRow:dev-local"))).and(hasText("Not encrypted")), useUnmergedTree = true)
            .assertExists("the plain row says Not encrypted")
        assertTrue(has("icon:lock_open", under = "popupNotEncrypted:dev-local"), "the row warning has its icon")
        assertTrue(has("popupNotEncrypted:dev-failed", under = "popupRow:dev-failed"), "also in the Failed row")
        assertTrue(!has("popupNotEncrypted:uat-eu"), "no warning on a TLS row")
    }

    @Test
    fun `a chip with a long name and the warning keeps the warning visible`() = runDesktopComposeUiTest(480, 400) {
        show(state(chip(ChipState.Connected, ChipIcon.Link, notEncrypted = true, name = "a-very-long-connection-name-for-a-local-development-broker-on-this-machine")))

        val warning = onNode(hasTestTag("chipNotEncrypted"), useUnmergedTree = true).fetchSemanticsNode()
        val chipBounds = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        val w = warning.boundsInRoot
        assertTrue(w.width > 0f && w.height > 0f, "the warning must be laid out, not squeezed to nothing: $w")
        assertTrue(w.right <= chipBounds.right + 1f, "the warning ($w) must stay inside the chip ($chipBounds); the name ellipsizes, the warning does not")
    }
}
