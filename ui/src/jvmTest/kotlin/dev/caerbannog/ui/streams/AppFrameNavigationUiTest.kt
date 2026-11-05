package dev.caerbannog.ui.streams

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.ui.appframe.AppDestination
import dev.caerbannog.ui.appframe.AppFrame
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M2-05c step 2, brief item "navigation": the drawer items of the app frame report the destination the user chose
 * (`onNavigate`), so the app can show the Connections and Streams screens. Tags: `navItem:<Connections|Streams|Topology|Migrations>`
 * (the whole item is the click target, focusable, Enter activates it), the item of the screen on show has `selected` semantics.
 * The frame only REPORTS: Topology and Migrations have no screen yet, the app ignores them (pinned in the desktopApp wiring test).
 * "Manage connections" / "New connection" in the popup call `ConnectionControlEvents.openConnections` (05b tests); that the app
 * turns it into the same navigation is pinned in `CompositionRootNavigationTest`.
 */
@OptIn(ExperimentalTestApi::class)
class AppFrameNavigationUiTest {

    @Test
    fun `each drawer item reports its destination when pressed`() = runDesktopComposeUiTest(1280, 800) {
        val seen = mutableListOf<AppDestination>()
        setContent {
            AppFrame(title = "Streams", control = connected, events = noControlEvents, onNavigate = { seen += it }) { Column { Text("screen") } }
        }

        for (item in AppDestination.entries) onNodeWithTag("navItem:${item.name}").performClick()

        assertEquals(AppDestination.entries.toList(), seen)
    }

    @Test
    fun `the item of the screen on show is selected, the others are not`() = runDesktopComposeUiTest(1280, 800) {
        setContent { AppFrame(title = "Streams", control = connected, events = noControlEvents) { Text("screen") } }

        onNodeWithTag("navItem:Streams").assertIsSelected()
        for (other in listOf("Connections", "Topology", "Migrations")) onNodeWithTag("navItem:$other").assertIsNotSelected()
    }

    @Test
    fun `a drawer item can be used from the keyboard, Enter activates it`() = runDesktopComposeUiTest(1280, 800) {
        val seen = mutableListOf<AppDestination>()
        setContent {
            AppFrame(title = "Streams", control = connected, events = noControlEvents, onNavigate = { seen += it }) { Text("screen") }
        }

        onNodeWithTag("navItem:Connections").requestFocus()
        onNodeWithTag("navItem:Connections").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }

        assertEquals(listOf(AppDestination.Connections), seen)
    }

    @Test
    fun `the Streams screen carries the drawer and reports navigation from it`() = runDesktopComposeUiTest(1280, 800) {
        val seen = mutableListOf<AppDestination>()
        screen(rowsState(), onNavigate = { seen += it })

        onNodeWithTag("navItem:Streams").assertIsSelected()
        onNodeWithTag("navItem:Connections").performClick()

        assertEquals(listOf(AppDestination.Connections), seen)
    }
}
