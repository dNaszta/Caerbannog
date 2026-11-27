package dev.caerbannog.ui.appframe

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import kotlin.test.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.ConnectionFeature
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M8-02a (owner decision 2026-11-10): while a 3.x broker is connected the drawer shows ALL items; the ones the connection does not offer are
 * DISABLED (greyed, not clickable) and say why, as text and as accessible description. Tags: `navItem:<Destination>` (existing),
 * `navReasonNote` (M8-06c, F4 option B: the reason is shown ONCE; the old tags `navItemReason:<Destination>` and `searchReason` are gone), `searchBox`. Features come from `availableFeatures(...)` in core; the frame only reads them.
 */
@OptIn(ExperimentalTestApi::class)
class AppFrameDisabledNavigationUiTest {

    private val reason = ConnectionNotes.MIGRATION_SOURCE_PILL
    private val onlyMigration = setOf(ConnectionFeature.MigrateFromThreeX)
    private val disabled = listOf(AppDestination.Streams, AppDestination.Topology, AppDestination.Migrations)
    private val enabled = listOf(AppDestination.Connections, AppDestination.MigrateFromThree)

    private val noop = object : ConnectionControlEvents {
        override fun connect(name: String) = Unit
        override fun disconnect(name: String) = Unit
        override fun retry(name: String) = Unit
        override fun setRefreshInterval(interval: RefreshInterval) = Unit
        override fun refreshNow() = Unit
        override fun openConnections() = Unit
    }

    private val control = ConnectionControlState(
        chip = ConnectionChipState(ChipState.NoConnection, ChipIcon.LinkOff, "No connection", null, null, null, "No connection. Open connection menu", false),
        rows = emptyList(),
        emptyText = null,
        refresh = RefreshSectionState(RefreshInterval.Every5, enabled = false, note = "Connect to refresh"),
    )

    private val navigations = mutableListOf<AppDestination>()

    private fun DesktopComposeUiTest.frame(features: Set<ConnectionFeature>? = null, why: String? = null) {
        navigations.clear()
        setContent {
            if (features == null) {
                AppFrame(title = "Connections", control = control, events = noop, onNavigate = { navigations += it }) { Text("the screen") }
            } else {
                AppFrame(
                    title = "Connections", control = control, events = noop, onNavigate = { navigations += it },
                    features = features, featuresDisabledReason = why,
                ) { Text("the screen") }
            }
        }
    }

    /** Hovers the node: a tooltip is a popup, so it shows up as a second root of the semantics tree whose texts hold [text]. */
    private fun DesktopComposeUiTest.tooltipShows(tag: String, text: String): Boolean {
        onNodeWithTag(tag).performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(2000)
        waitForIdle()
        fun texts(n: androidx.compose.ui.semantics.SemanticsNode): String =
            (listOf(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()) + n.children.map { texts(it) }).joinToString(" ")
        val roots = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        return roots.drop(1).any { texts(it).contains(text) }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.visibleTexts(): String {
        val own = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        return (listOf(own) + children.map { it.visibleTexts() }).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** The reason as an accessible description: the content description or the state description of the item says it. */
    private fun SemanticsNodeInteraction.says(text: String): Boolean {
        val config = fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text in it } ||
            config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
    }

    @Test
    fun `a 3x connection leaves all five items and the search in the drawer, and Access is absent not disabled (AQ6)`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        onAllNodesWithTag("navItem:Access", useUnmergedTree = true).assertCountEquals(0)
        AppDestination.entries.filter { it != AppDestination.Access }.forEach { onNodeWithTag("navItem:${it.name}").assertExists("navItem:${it.name} is still drawn") }
        onNodeWithTag("searchBox").assertExists("the search is still drawn")
    }

    @Test
    fun `the items the connection does not offer are disabled and carry the reason as description, the text is shown once in the note`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        disabled.forEach {
            onNodeWithTag("navItem:${it.name}").assertIsNotEnabled()
            assertEquals(true, onNodeWithTag("navItem:${it.name}").says(reason), "navItem:${it.name} says the reason to a screen reader")
        }
        onNodeWithTag("searchBox").assertIsNotEnabled()
        assertEquals(true, onNodeWithTag("searchBox").says(reason), "the search says the reason to a screen reader")
        onNodeWithTag("navReasonNote", useUnmergedTree = true).assertTextEquals(reason)
    }

    // ---- M8-06c, F4 option B: ONE visible note under the list instead of one line under every disabled item and under the search ----

    @Test
    fun `the reason is shown once as the navReasonNote, and the per item and search reason tags are gone`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        onAllNodesWithTag("navReasonNote", useUnmergedTree = true).assertCountEquals(1)
        onNodeWithTag("navReasonNote", useUnmergedTree = true).assertTextEquals(reason)
        AppDestination.entries.forEach { onAllNodesWithTag("navItemReason:${it.name}", useUnmergedTree = true).assertCountEquals(0) }
        onAllNodesWithTag("searchReason", useUnmergedTree = true).assertCountEquals(0)
        val visible = onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().sumOf { node -> Regex(Regex.escape(reason)).findAll(node.visibleTexts()).count() }
        assertEquals(1, visible, "the reason text is visible exactly once (descriptions and tooltips are not visible text)")
    }

    @Test
    fun `the tooltip of a disabled item still reads the reason`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        assertTrue(tooltipShows("navItem:Streams", reason), "hovering a disabled item shows the reason as a tooltip")
    }

    @Test
    fun `the disabled search shows its reason as tooltip and as description, the enabled search shows neither`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        assertEquals(true, onNodeWithTag("searchBox").says(reason), "description of the disabled search")
        assertTrue(tooltipShows("searchBox", reason), "hovering the disabled search shows the reason as a tooltip")
    }

    @Test
    fun `the enabled search has no reason description and no tooltip`() = runDesktopComposeUiTest(1280, 800) {
        frame(setOf(ConnectionFeature.Search, ConnectionFeature.MigrateFromThreeX), reason)
        assertEquals(false, onNodeWithTag("searchBox").says(reason))
        assertEquals(false, tooltipShows("searchBox", reason), "an enabled search has no tooltip")
    }

    @Test
    fun `no note for a connection that offers everything, also when a reason is passed, and none without a reason`() = runDesktopComposeUiTest(1280, 800) {
        frame(ConnectionFeature.entries.toSet(), reason)
        onAllNodesWithTag("navReasonNote", useUnmergedTree = true).assertCountEquals(0)
        frame()
        onAllNodesWithTag("navReasonNote", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the note is not a drawer item, it is not focusable and does not navigate`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        onNodeWithTag("navReasonNote", useUnmergedTree = true).performClick()
        assertEquals(emptyList(), navigations)
        onNodeWithTag("navReasonNote", useUnmergedTree = true).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
    }

    @Test
    fun `Connections and Migrate from 3x stay enabled, have no reason and navigate`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        enabled.forEach {
            onNodeWithTag("navItem:${it.name}").assertIsEnabled()
            assertEquals(false, onNodeWithTag("navItem:${it.name}").says(reason), "an enabled item has no reason")
        }
        onNodeWithTag("navItem:Connections").performClick()
        onNodeWithTag("navItem:MigrateFromThree").performClick()
        assertEquals(listOf(AppDestination.Connections, AppDestination.MigrateFromThree), navigations)
    }

    @Test
    fun `a click on a disabled item records no navigation`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        disabled.forEach { onNodeWithTag("navItem:${it.name}").performClick() }
        assertEquals(emptyList(), navigations, "a disabled item must not navigate")
    }

    @Test
    fun `a click on the disabled search does nothing`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        onNodeWithTag("searchBox").performClick()
        assertEquals(emptyList(), navigations)
    }

    @Test
    fun `with the default features every item is enabled and no reason is shown`() = runDesktopComposeUiTest(1280, 800) {
        frame()
        AppDestination.entries.forEach {
            onNodeWithTag("navItem:${it.name}").assertIsEnabled()
            onAllNodesWithTag("navItemReason:${it.name}", useUnmergedTree = true).assertCountEquals(0)
        }
        onNodeWithTag("searchBox").assertIsEnabled()
        onAllNodesWithTag("searchReason", useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithTag("navReasonNote", useUnmergedTree = true).assertCountEquals(0)
        AppDestination.entries.forEach { onNodeWithTag("navItem:${it.name}").performClick() }
        assertEquals(AppDestination.entries.toList(), navigations)
    }

    @Test
    fun `a feature that is on enables exactly its item`() = runDesktopComposeUiTest(1280, 800) {
        frame(setOf(ConnectionFeature.Streams, ConnectionFeature.Search, ConnectionFeature.MigrateFromThreeX), reason)
        onNodeWithTag("navItem:Streams").assertIsEnabled()
        onNodeWithTag("searchBox").assertIsEnabled()
        onNodeWithTag("navItem:Topology").assertIsNotEnabled()
        onNodeWithTag("navItem:Migrations").assertIsNotEnabled()
        onNodeWithTag("navItem:Connections").assertIsEnabled()
    }

    @Test
    fun `a disabled item cannot take focus and Tab with Enter or Space never reaches it`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        disabled.forEach { onNodeWithTag("navItem:${it.name}").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus)) }
        enabled.forEach { onNodeWithTag("navItem:${it.name}").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)) }
        onNodeWithTag("navItem:Connections").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(12) {
            onNodeWithTag("appDrawer").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("appDrawer").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("appDrawer").performKeyInput { pressKey(Key.Spacebar) }
        }
        assertTrue(navigations.none { it in disabled }, "keyboard reached a disabled item: $navigations")
    }
}
