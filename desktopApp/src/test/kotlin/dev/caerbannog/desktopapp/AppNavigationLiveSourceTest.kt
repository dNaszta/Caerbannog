package dev.caerbannog.desktopapp

import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.ui.appframe.AppDestination
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M8-06c: the request the connection popup sends to the Migrate from 3.x screen ("Migrate from 3.x" opens it with the live source selected).
 * `requestedSource` is set by [AppNavigation.openMigrateFromThree], cleared by [AppNavigation.sourceRequestHandled] and by opening any other destination.
 */
class AppNavigationLiveSourceTest {

    @Test
    fun `no source is requested at the start`() {
        assertNull(AppNavigation().requestedSource.value)
    }

    @Test
    fun `openMigrateFromThree opens the screen and requests the source`() {
        val nav = AppNavigation(AppDestination.Connections)
        nav.openMigrateFromThree(SourceKind.Live)
        assertEquals(AppDestination.MigrateFromThree, nav.screen.value)
        assertEquals(SourceKind.Live, nav.requestedSource.value)
        nav.openMigrateFromThree(SourceKind.File)
        assertEquals(SourceKind.File, nav.requestedSource.value, "the latest request wins")
    }

    @Test
    fun `from the stream screen it leaves the stream and the screen shows Migrate from 3x`() {
        val nav = AppNavigation(AppDestination.Streams)
        nav.openStream("orders", offset = 5)
        nav.openMigrateFromThree(SourceKind.Live)
        assertEquals(AppDestination.MigrateFromThree, nav.screen.value)
        assertNull(nav.selectedStream.value)
        assertNull(nav.initialOffset.value)
    }

    @Test
    fun `the screen taking the request clears it, and the screen stays`() {
        val nav = AppNavigation()
        nav.openMigrateFromThree(SourceKind.Live)
        nav.sourceRequestHandled()
        assertNull(nav.requestedSource.value)
        assertEquals(AppDestination.MigrateFromThree, nav.screen.value)
        nav.sourceRequestHandled()
        assertNull(nav.requestedSource.value, "handling twice is harmless")
    }

    @Test
    fun `opening any other destination clears a request that was not taken`() {
        listOf(AppDestination.Connections, AppDestination.Streams, AppDestination.Topology, AppDestination.Migrations).forEach { other ->
            val nav = AppNavigation()
            nav.openMigrateFromThree(SourceKind.Live)
            nav.open(other)
            assertNull(nav.requestedSource.value, "$other")
            assertEquals(other, nav.screen.value)
        }
    }
}
