package dev.caerbannog.desktopapp

import dev.caerbannog.ui.appframe.AppDestination
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M2-06a step 2 (brief item 5): the rules of the one-level stream screen in [AppNavigation]. `selectedStream` is the stream whose screen
 * is open, null for the list. Pinned where the brief is silent:
 *  - a drawer item that has no screen yet (Migrations; Topology got one in M2-08c) changes nothing, so it does not close the stream either (the user would
 *    lose the tab for a click that leads nowhere);
 *  - `openStream` of the open stream again, or of another one while on Streams, just selects it;
 *  - `closeStream` with nothing open is a no-op.
 * Disconnect clearing the selection is a composition rule (it needs the link): CompositionRootClientsWiringTest.
 */
class AppNavigationStreamTest {

    private fun onStreams() = AppNavigation(AppDestination.Streams)

    @Test
    fun `no stream is open at the start`() {
        assertNull(AppNavigation().selectedStream.value)
        assertNull(onStreams().selectedStream.value)
    }

    @Test
    fun `openStream on the Streams screen opens that stream`() {
        val nav = onStreams()
        nav.openStream("orders-events")
        assertEquals("orders-events", nav.selectedStream.value)
        assertEquals(AppDestination.Streams, nav.screen.value, "the drawer keeps Streams selected")
    }

    @Test
    fun `openStream from another screen does nothing`() {
        val nav = AppNavigation(AppDestination.Connections)
        nav.openStream("orders-events")
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Connections, nav.screen.value)
    }

    @Test
    fun `a blank name is not opened`() {
        val nav = onStreams()
        nav.openStream("")
        assertNull(nav.selectedStream.value)
        nav.openStream("   ")
        assertNull(nav.selectedStream.value)
    }

    @Test
    fun `a second openStream selects the other stream`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.openStream("b")
        assertEquals("b", nav.selectedStream.value)
    }

    @Test
    fun `closeStream goes back to the list and keeps the Streams screen`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.closeStream()
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Streams, nav.screen.value)
        nav.closeStream() // nothing open: harmless
        assertNull(nav.selectedStream.value)
    }

    @Test
    fun `opening the Streams drawer item while a stream is open goes back to the list`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.open(AppDestination.Streams)
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Streams, nav.screen.value)
    }

    @Test
    fun `any other drawer item with a screen clears the selection`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.open(AppDestination.Connections)
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Connections, nav.screen.value)

        nav.open(AppDestination.Streams)
        assertNull(nav.selectedStream.value, "coming back to Streams shows the list, not the old stream")
    }

    @Test
    fun `opening Migrations clears the stream selection and opens Migrations`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.open(AppDestination.Migrations)
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Migrations, nav.screen.value)
    }

    // M2-08c: Topology has a screen now. Like the Streams item it never leads back into an old stream.
    @Test
    fun `the Topology item opens the Topology screen and closes an open stream`() {
        val nav = onStreams()
        nav.openStream("a")
        nav.open(AppDestination.Topology)
        assertEquals(AppDestination.Topology, nav.screen.value)
        assertNull(nav.selectedStream.value)
        nav.open(AppDestination.Streams)
        assertEquals(AppDestination.Streams, nav.screen.value)
        assertNull(nav.selectedStream.value, "coming back to Streams shows the list")
    }

    @Test
    fun `a stream cannot be opened from the Topology screen`() {
        val nav = AppNavigation(AppDestination.Topology)
        nav.openStream("a")
        assertNull(nav.selectedStream.value)
        assertEquals(AppDestination.Topology, nav.screen.value)
    }

    @Test
    fun `openStream with offset records the initial offset and clear on close`() {
        val nav = onStreams()
        nav.openStream("orders-events", 47950L)
        assertEquals("orders-events", nav.selectedStream.value)
        assertEquals(47950L, nav.initialOffset.value)

        nav.closeStream()
        assertNull(nav.selectedStream.value)
        assertNull(nav.initialOffset.value)
    }
}
