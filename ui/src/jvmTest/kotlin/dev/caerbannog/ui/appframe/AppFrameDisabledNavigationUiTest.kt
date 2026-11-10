package dev.caerbannog.ui.appframe

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
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
 * `navItemReason:<Destination>`, `searchBox`, `searchReason`. Features come from `availableFeatures(...)` in core; the frame only reads them.
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

    /** The reason as an accessible description: the content description or the state description of the item says it. */
    private fun SemanticsNodeInteraction.says(text: String): Boolean {
        val config = fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text in it } ||
            config.getOrNull(SemanticsProperties.StateDescription)?.contains(text) == true
    }

    @Test
    fun `a 3x connection leaves all five items and the search in the drawer`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        AppDestination.entries.forEach { onNodeWithTag("navItem:${it.name}").assertExists("navItem:${it.name} is still drawn") }
        onNodeWithTag("searchBox").assertExists("the search is still drawn")
    }

    @Test
    fun `the items the connection does not offer are disabled and carry the reason as text and as description`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        disabled.forEach {
            onNodeWithTag("navItem:${it.name}").assertIsNotEnabled()
            onNodeWithTag("navItemReason:${it.name}", useUnmergedTree = true).assertTextEquals(reason)
            assertEquals(true, onNodeWithTag("navItem:${it.name}").says(reason), "navItem:${it.name} says the reason to a screen reader")
        }
        onNodeWithTag("searchBox").assertIsNotEnabled()
        onNodeWithTag("searchReason", useUnmergedTree = true).assertTextEquals(reason)
        assertEquals(true, onNodeWithTag("searchBox").says(reason), "the search says the reason to a screen reader")
    }

    @Test
    fun `Connections and Migrate from 3x stay enabled, have no reason and navigate`() = runDesktopComposeUiTest(1280, 800) {
        frame(onlyMigration, reason)
        enabled.forEach {
            onNodeWithTag("navItem:${it.name}").assertIsEnabled()
            onAllNodesWithTag("navItemReason:${it.name}", useUnmergedTree = true).assertCountEquals(0)
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
}
