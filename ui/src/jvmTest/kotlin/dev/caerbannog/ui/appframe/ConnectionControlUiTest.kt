package dev.caerbannog.ui.appframe

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionRowState
import dev.caerbannog.core.connections.PermissionPill
import dev.caerbannog.core.connections.PillTone
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.RowAction
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M2-05b step 2: the connection chip and its popup (Compose UI test, state in, events out; the model is core's and is tested
 * there, so the states below are built by hand). Written first, red against the empty stubs in `AppFrame.kt`.
 *
 * Contract (test tags, names are the test developer's proposal; icons follow the existing `icon:<mockup name>` convention):
 *   "connectionChip"        the chip button: focusable, clickable, contentDescription = `ConnectionChipState.accessibleLabel`
 *   "icon:link" | "icon:link_off" | "icon:sync" | "icon:error"   the state icon, inside the chip or a popup row
 *   "chipPill"              the permission pill inside the chip, only when the state has a pill (text = pill text)
 *   "connectionPopup"       the popup container, shown while open
 *   "popupRow:<name>"       one saved profile row (merged text: name and state text); `selected` is true for the current row
 *   "popupAction:<name>"    the row's one action button (text Connect, Disconnect or Retry); absent for RowAction.None
 *   "refreshChoice:<label>" a single-choice chip with label Off, 5 s, 10 s, 30 s or 60 s; `selected` for the chosen one
 *   "refreshNow"            the "Refresh now" button;  "refreshNote"  the note text;
 *   "manageConnections"     the "Manage connections" button;  "newConnectionAction"  the "New connection" button of the empty popup
 * The chip tooltip is not tested here (Compose Desktop tooltips are not reachable from a test); the text is pinned in core.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionControlUiTest {

    private class Recorder : ConnectionControlEvents {
        val events = mutableListOf<String>()
        override fun connect(name: String) { events += "connect:$name" }
        override fun disconnect(name: String) { events += "disconnect:$name" }
        override fun retry(name: String) { events += "retry:$name" }
        override fun setRefreshInterval(interval: RefreshInterval) { events += "interval:${interval.seconds}" }
        override fun refreshNow() { events += "refreshNow" }
        override fun openConnections() { events += "openConnections" }
    }

    private val unlockedPill = PermissionPill("Can change topology", PillTone.OK)
    private val lockedPill = PermissionPill("Topology locked", PillTone.LOCKED)

    private val connectedChip = ConnectionChipState(
        ChipState.Connected, ChipIcon.Link, "prod-eu", null, lockedPill, null,
        "Connection prod-eu, Connected, topology locked. Open connection menu", errorBorder = false,
    )

    private val refreshOn = RefreshSectionState(RefreshInterval.Every30, enabled = true, note = "Updated 12 s ago")
    private val refreshOff = RefreshSectionState(RefreshInterval.Every5, enabled = false, note = "Connect to refresh")

    /** The popup of the mockup: connected (current), disconnected, failed, connecting. */
    private val mockupState = ConnectionControlState(
        chip = connectedChip,
        rows = listOf(
            ConnectionRowState("prod-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true),
            ConnectionRowState("uat-eu", ChipIcon.LinkOff, "Disconnected", RowAction.Connect, current = false),
            ConnectionRowState("staging-eu", ChipIcon.Error, "Failed: Credentials were refused", RowAction.Retry, current = false),
            ConnectionRowState("local-test", ChipIcon.Sync, "Connecting", RowAction.None, current = false),
        ),
        emptyText = null,
        refresh = refreshOn,
    )

    private fun chipOf(kind: ChipState, icon: ChipIcon, text: String, stateText: String?, pill: PermissionPill?, label: String, error: Boolean = false) =
        ConnectionChipState(kind, icon, text, stateText, pill, if (kind == ChipState.Failed) "Credentials were refused" else null, label, error)

    private fun DesktopComposeUiTest.show(state: ConnectionControlState, recorder: Recorder = Recorder(), width: Int = 1280): Recorder {
        setContent { ConnectionControl(state, recorder) }
        return recorder
    }

    private fun DesktopComposeUiTest.focusChip() {
        onNodeWithTag("connectionChip").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("connectionChip").assertIsFocused()
    }

    private fun DesktopComposeUiTest.open() {
        onNodeWithTag("connectionChip").performClick()
        waitForIdle()
        onNodeWithTag("connectionPopup").assertExists("the popup must be open")
    }

    private fun SemanticsMatcherInPopup(): SemanticsMatcher = hasAnyAncestor(hasTestTag("connectionPopup"))

    private fun DesktopComposeUiTest.focusedTag(): String? =
        onAllNodes(SemanticsMatcher("focused node in the popup") { it.config.getOrNull(SemanticsProperties.Focused) == true }.and(SemanticsMatcherInPopup()), useUnmergedTree = true)
            .fetchSemanticsNodes().lastOrNull()?.config?.getOrNull(SemanticsProperties.TestTag)

    // --- the chip, every state ---

    private val chipCases = listOf(
        Triple("No connection", chipOf(ChipState.NoConnection, ChipIcon.LinkOff, "No connection", null, null, "No connection. Open connection menu"), "link_off"),
        Triple("Connecting", chipOf(ChipState.Connecting, ChipIcon.Sync, "uat-eu", null, null, "Connection uat-eu, Connecting. Open connection menu"), "sync"),
        Triple("Connected", connectedChip, "link"),
        Triple("Failed", chipOf(ChipState.Failed, ChipIcon.Error, "uat-eu", null, null, "Connection uat-eu, Failed. Open connection menu", error = true), "error"),
        Triple("Disconnected", chipOf(ChipState.Disconnected, ChipIcon.LinkOff, "uat-eu", "Disconnected", null, "Connection uat-eu, Disconnected. Open connection menu"), "link_off"),
    )

    @Test
    fun `the chip shows its state icon, name and accessible label for every state`() {
        for ((label, chip, icon) in chipCases) {
            runDesktopComposeUiTest(1280, 400) {
                show(mockupState.copy(chip = chip))

                onNodeWithTag("connectionChip").assertExists("$label: the chip")
                onNode(hasTestTag("icon:$icon").and(hasAnyAncestor(hasTestTag("connectionChip"))), useUnmergedTree = true)
                    .assertExists("$label: icon:$icon inside the chip")
                onNode(hasText(chip.text).and(hasAnyAncestor(hasTestTag("connectionChip"))), useUnmergedTree = true)
                    .assertExists("$label: the text '${chip.text}' inside the chip")
                val described = onNodeWithTag("connectionChip").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
                assertEquals(listOf(chip.accessibleLabel), described, "$label: accessible label")
            }
        }
    }

    @Test
    fun `the pill is in the chip only when the state has one`() {
        for ((label, chip, _) in chipCases) {
            runDesktopComposeUiTest(1280, 400) {
                show(mockupState.copy(chip = chip))
                val pill = onAllNodes(hasTestTag("chipPill"), useUnmergedTree = true).fetchSemanticsNodes()
                assertEquals(if (chip.pill != null) 1 else 0, pill.size, "$label: pill count")
                if (chip.pill != null) {
                    onNode(hasTestTag("chipPill").and(hasText(chip.pill!!.text).or(hasAnyDescendant(hasText(chip.pill!!.text)))), useUnmergedTree = true).assertExists()
                }
            }
        }
    }

    @Test
    fun `a Disconnected chip says the word Disconnected next to the name, the others do not`() {
        for ((label, chip, _) in chipCases) {
            runDesktopComposeUiTest(1280, 400) {
                show(mockupState.copy(chip = chip))
                val words = onAllNodes(hasText("Disconnected").and(hasAnyAncestor(hasTestTag("connectionChip"))), useUnmergedTree = true)
                    .fetchSemanticsNodes().size
                assertEquals(if (chip.stateText != null) 1 else 0, words, "$label: the word Disconnected inside the chip")
            }
        }
    }

    @Test
    fun `the chip has no content-description other than its own label, the icons are decorative`() = runDesktopComposeUiTest(1280, 400) {
        show(mockupState)

        val described = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription).and(hasAnyAncestor(hasTestTag("connectionChip")).or(hasTestTag("connectionChip"))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(listOf(listOf(connectedChip.accessibleLabel)), described.map { it.config[SemanticsProperties.ContentDescription] })
    }

    @Test
    fun `a long name does not push the pill out of the chip`() = runDesktopComposeUiTest(400, 400) {
        val long = "a-rather-long-connection-name-".repeat(5)
        show(mockupState.copy(chip = connectedChip.copy(text = long)))

        val chip = onNodeWithTag("connectionChip").fetchSemanticsNode().boundsInRoot
        val pill = onNodeWithTag("chipPill", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(pill.left >= chip.left && pill.right <= chip.right + 0.5f, "the pill stays inside the chip: chip $chip, pill $pill")
        assertTrue(chip.right <= 400f + 0.5f, "the chip stays inside the window: $chip")
    }

    // --- opening and closing ---

    @Test
    fun `the popup is closed at first and a click on the chip opens it`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        onNodeWithTag("connectionPopup").assertDoesNotExist()

        open()
    }

    @Test
    fun `Enter on the focused chip opens the popup and focus moves into it`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        focusChip()

        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithTag("connectionPopup").assertExists()
        assertTrue(focusedTag() != null, "focus must move into the popup on open")
    }

    @Test
    fun `Space on the focused chip opens the popup`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        focusChip()

        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()

        onNodeWithTag("connectionPopup").assertExists()
    }

    @Test
    fun `Escape closes the popup and returns focus to the chip`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        focusChip()
        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithTag("connectionPopup").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithTag("connectionPopup").assertDoesNotExist()
        onNodeWithTag("connectionChip").assertIsFocused()
    }

    @Test
    fun `Up and Down move focus between the actions and the refresh choices, and Up undoes Down`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        focusChip()
        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        val first = focusedTag()

        onNodeWithTag("connectionPopup").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        val second = focusedTag()
        assertNotEquals(first, second, "Down must move focus")

        onNodeWithTag("connectionPopup").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertEquals(first, focusedTag(), "Up must go back")
    }

    @Test
    fun `Down reaches every action row, every refresh choice and Refresh now`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        focusChip()
        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        val seen = linkedSetOf<String>()
        repeat(20) {
            focusedTag()?.let(seen::add)
            onNodeWithTag("connectionPopup").performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
        }
        val expected = listOf(
            "popupAction:prod-eu", "popupAction:uat-eu", "popupAction:staging-eu",
            "refreshChoice:Off", "refreshChoice:5 s", "refreshChoice:10 s", "refreshChoice:30 s", "refreshChoice:60 s", "refreshNow",
            "manageConnections",
        )
        assertTrue(seen.containsAll(expected), "keyboard reach: missing ${expected - seen}, seen $seen")
    }

    @Test
    fun `Enter on a Connect action runs it and keeps the popup open`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(mockupState)
        focusChip()
        onNodeWithTag("connectionChip").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        var presses = 0
        while (focusedTag() != "popupAction:uat-eu" && presses++ < 20) {
            onNodeWithTag("connectionPopup").performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
        }
        assertEquals("popupAction:uat-eu", focusedTag(), "could not reach the Connect action of uat-eu with Down")

        onNodeWithTag("popupAction:uat-eu").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(listOf("connect:uat-eu"), recorder.events)
        onNodeWithTag("connectionPopup").assertExists("the popup stays open so the new state is visible")
    }

    // --- the popup ---

    @Test
    fun `the popup lists the sections in order - Connections, Stream list refresh, then Manage connections`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        val tops = listOf("Connections", "Stream list refresh", "Manage connections").map { text ->
            onNode(hasText(text).and(SemanticsMatcherInPopup()), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops, "section order by position: $tops")
    }

    @Test
    fun `every saved profile has a row with its name and state text, in list order`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        val order = mockupState.rows.map { row ->
            onNode(hasTestTag("popupRow:${row.name}"), useUnmergedTree = false).assertExists("row of ${row.name}")
            onNode(hasTestTag("popupRow:${row.name}").and(hasAnyDescendant(hasText(row.name))), useUnmergedTree = true).assertExists()
            onNode(hasTestTag("popupRow:${row.name}").and(hasAnyDescendant(hasText(row.stateText))), useUnmergedTree = true)
                .assertExists("state text '${row.stateText}' in the row of ${row.name}")
            onNode(hasTestTag("popupRow:${row.name}")).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `each row has the icon of its state`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        val expected = mapOf("prod-eu" to "link", "uat-eu" to "link_off", "staging-eu" to "error", "local-test" to "sync")
        for ((name, icon) in expected) {
            onNode(hasTestTag("icon:$icon").and(hasAnyAncestor(hasTestTag("popupRow:$name"))), useUnmergedTree = true)
                .assertExists("icon:$icon in the row of $name")
        }
    }

    @Test
    fun `the current row is highlighted and no other is`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        onNodeWithTag("popupRow:prod-eu").assertIsSelected()
        listOf("uat-eu", "staging-eu", "local-test").forEach { onNodeWithTag("popupRow:$it").assertIsNotSelected() }
    }

    @Test
    fun `each row has its one action - Disconnect, Connect, Retry - and none while Connecting`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        mapOf("prod-eu" to "Disconnect", "uat-eu" to "Connect", "staging-eu" to "Retry").forEach { (name, text) ->
            onNode(hasTestTag("popupAction:$name").and(hasText(text))).assertExists("$name offers $text")
        }
        onNodeWithTag("popupAction:local-test").assertDoesNotExist()
    }

    @Test
    fun `clicking an action runs the matching event and the popup stays open`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(mockupState)
        open()

        onNodeWithTag("popupAction:uat-eu").performClick()
        onNodeWithTag("popupAction:prod-eu").performClick()
        onNodeWithTag("popupAction:staging-eu").performClick()
        waitForIdle()

        assertEquals(listOf("connect:uat-eu", "disconnect:prod-eu", "retry:staging-eu"), recorder.events)
        onNodeWithTag("connectionPopup").assertExists()
    }

    @Test
    fun `a failed row shows the reason as one plain line`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        onNodeWithText("Failed: Credentials were refused", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `with no saved connection the popup says so and offers New connection`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(
            ConnectionControlState(
                chip = chipCases[0].second, rows = emptyList(), emptyText = "No saved connections", refresh = refreshOff,
            ),
        )
        open()

        onNodeWithText("No saved connections", useUnmergedTree = true).assertExists()
        onNode(hasTestTag("popupRow:prod-eu")).assertDoesNotExist()
        onNodeWithTag("newConnectionAction").performClick()
        assertEquals(listOf("openConnections"), recorder.events)
    }

    @Test
    fun `Manage connections opens the Connections screen`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(mockupState)
        open()

        onNodeWithTag("manageConnections").performClick()

        assertEquals(listOf("openConnections"), recorder.events)
    }

    // --- the refresh section ---

    @Test
    fun `the refresh choices are Off, 5 s, 10 s, 30 s and 60 s, the selected one is marked`() = runDesktopComposeUiTest(1280, 800) {
        show(mockupState)
        open()

        listOf("Off", "5 s", "10 s", "30 s", "60 s").forEach { label ->
            val node = onNodeWithTag("refreshChoice:$label")
            node.assertExists("choice $label")
            if (label == "30 s") node.assertIsSelected() else node.assertIsNotSelected()
        }
        onNodeWithText("Refresh every", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an enabled refresh section runs the interval and the Refresh now events and shows the note`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(mockupState)
        open()

        onNodeWithTag("refreshChoice:10 s").assertIsEnabled().performClick()
        onNodeWithTag("refreshChoice:Off").performClick()
        onNodeWithTag("refreshNow").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(listOf("interval:10", "interval:0", "refreshNow"), recorder.events)
        onNode(hasTestTag("refreshNote").and(hasText("Updated 12 s ago")), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a disabled refresh section disables every choice and Refresh now, says Connect to refresh, and sends nothing`() = runDesktopComposeUiTest(1280, 800) {
        val recorder = show(mockupState.copy(refresh = refreshOff, chip = chipCases[0].second))
        open()

        listOf("Off", "5 s", "10 s", "30 s", "60 s").forEach { onNodeWithTag("refreshChoice:$it").assertIsNotEnabled() }
        onNodeWithTag("refreshNow").assertIsNotEnabled()
        onNode(hasTestTag("refreshNote").and(hasText("Connect to refresh")), useUnmergedTree = true).assertExists()
        onNodeWithTag("refreshChoice:5 s").assertIsSelected() // the chosen interval is still shown
        onNodeWithTag("refreshChoice:10 s").performClick()
        onNodeWithTag("refreshNow").performClick()
        assertEquals(emptyList(), recorder.events)
    }

    @Test
    fun `the popup follows a new state while it is open`() = runDesktopComposeUiTest(1280, 800) {
        val state = mutableStateOf(mockupState)
        val recorder = Recorder()
        setContent { ConnectionControl(state.value, recorder) }
        open()
        onNodeWithTag("popupAction:uat-eu").assertExists()

        state.value = mockupState.copy(
            rows = mockupState.rows.map {
                if (it.name == "uat-eu") ConnectionRowState("uat-eu", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true)
                else it.copy(current = false, action = if (it.action == RowAction.Disconnect) RowAction.Connect else it.action)
            },
        )
        waitForIdle()

        onNode(hasTestTag("popupAction:uat-eu").and(hasText("Disconnect"))).assertExists()
        onNodeWithTag("connectionPopup").assertExists()
    }
}
