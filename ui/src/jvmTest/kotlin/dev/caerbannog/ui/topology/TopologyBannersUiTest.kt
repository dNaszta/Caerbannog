package dev.caerbannog.ui.topology

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.topology.TopologyBannerKind
import dev.caerbannog.core.topology.TopologyTexts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2: the read-only reasons (mockup `banner-reasons`, owner decision O1 = b, architect ruling: DRAW ONLY `state.banners.first()`, never a
 * loop). Window 1280 x 800 dp. RED on the compile stub. Tag contract, in addition to [TopologyScreenUiTest]:
 *  - exactly ONE node whose tag starts with `topologyBanner` exists when `state.banners` is not empty, none when it is empty. Its tag is
 *    `topologyBanner:<kind name>` (ReadOnlyLevel, NoConfigure, DeclaredNotConfirmed, NotReadable, OtherVhost) of the FIRST banner; it holds the headline
 *    and the detail (when there is one) as its text, in that order; it sits above the tabs (above `tab:Queues`).
 *  - the banner is an information banner, not an error: it has no button (the lost-connection banner `topologyConnectionBanner` is a different node
 *    and may show together with it).
 *  - the banner is drawn on every screen that has the tabs row (Ready, Empty, Loading, VhostUnavailable); never in CannotRead.
 *  The state decides which reason and its wording; the screen draws them (the texts below are the core strings).
 */
@OptIn(ExperimentalTestApi::class)
class TopologyBannersUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.banners() = onAllNodes(
        SemanticsMatcher("test tag starts with topologyBanner:") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith("topologyBanner:") },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    private fun textOf(interaction: SemanticsNodeInteraction): String =
        interaction.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    private val level = banner(TopologyBannerKind.ReadOnlyLevel, TopologyTexts.READ_ONLY_HEADLINE, TopologyTexts.READ_ONLY_DETAIL)
    private val noConfigure = banner(TopologyBannerKind.NoConfigure, TopologyTexts.noConfigure("svc-explorer", "/"))
    private val otherVhost = banner(TopologyBannerKind.OtherVhost, TopologyTexts.otherVhost("/"))
    private val declared = banner(
        TopologyBannerKind.DeclaredNotConfirmed, "Topology is locked on this connection.",
        "You declared that svc-explorer can change topology; the broker could not confirm the permissions and still enforces its own.",
    )

    @Test
    fun `no reason means no banner`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = emptyList()))
        assertEquals(0, banners().size)
    }

    @Test
    fun `one banner is drawn with its headline and detail, tagged by its kind`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = listOf(level)))
        node("topologyBanner:ReadOnlyLevel").assertExists()
        val text = textOf(node("topologyBanner:ReadOnlyLevel"))
        assertTrue(text.contains("This connection is set to read-only."), text)
        assertTrue(text.contains("Its topology level is Read-only"), text)
        assertTrue(text.indexOf("This connection") < text.indexOf("Its topology level"), "headline first, then the detail: $text")
    }

    @Test
    fun `only the first banner of the list is drawn, never a loop`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = listOf(level, noConfigure, declared, otherVhost)))
        assertEquals(1, banners().size)
        node("topologyBanner:ReadOnlyLevel").assertExists()
        node("topologyBanner:NoConfigure").assertDoesNotExist()
        node("topologyBanner:OtherVhost").assertDoesNotExist()
    }

    @Test
    fun `when the broker reason comes first it is the one drawn`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = listOf(noConfigure, otherVhost)))
        assertEquals(1, banners().size)
        assertTrue(textOf(node("topologyBanner:NoConfigure")).contains("no configure permission for svc-explorer on virtual host /"))
    }

    @Test
    fun `the other vhost banner says where permissions were checked`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(vhost = "billing", banners = listOf(otherVhost)))
        assertEquals("Permissions were checked on virtual host / only. This virtual host is shown read-only.", textOf(node("topologyBanner:OtherVhost")).trim())
    }

    @Test
    fun `a declaration banner shows its detail`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = listOf(declared)))
        assertTrue(textOf(node("topologyBanner:DeclaredNotConfirmed")).contains("could not confirm the permissions and still enforces its own"))
    }

    @Test
    fun `the banner changes with the state and goes away`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState(banners = listOf(level)))
        node("topologyBanner:ReadOnlyLevel").assertExists()
        shown.state = queuesState(banners = listOf(otherVhost))
        waitForIdle()
        node("topologyBanner:ReadOnlyLevel").assertDoesNotExist()
        node("topologyBanner:OtherVhost").assertExists()
        shown.state = queuesState(banners = emptyList())
        waitForIdle()
        assertEquals(0, banners().size)
    }

    @Test
    fun `the banner is above the tabs and has no button`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(banners = listOf(level)))
        val banner = node("topologyBanner:ReadOnlyLevel").fetchSemanticsNode().boundsInRoot
        val tabs = node("tab:Queues").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.bottom <= tabs.top + 1f, "banner $banner above tabs $tabs")
        onAllNodes(SemanticsMatcher("a button inside the banner") {
            it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button && it.boundsInRoot.top >= banner.top && it.boundsInRoot.bottom <= banner.bottom
        }, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the banner shows together with the lost connection banner, and on every state with tabs`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState(banners = listOf(level), stale = true, connectionBanner = "Connection lost", action = "Retry"))
        node("topologyBanner:ReadOnlyLevel").assertExists()
        node("topologyConnectionBanner").assertExists()
        shown.state = emptyQueues.copy(banners = listOf(level))
        waitForIdle()
        node("topologyBanner:ReadOnlyLevel").assertExists()
        shown.state = unavailable.copy(banners = listOf(otherVhost))
        waitForIdle()
        node("topologyBanner:OtherVhost").assertExists()
        shown.state = noTag.copy(banners = listOf(level))
        waitForIdle()
        assertEquals(0, banners().size, "a user who cannot read topology gets the message only")
    }
}
