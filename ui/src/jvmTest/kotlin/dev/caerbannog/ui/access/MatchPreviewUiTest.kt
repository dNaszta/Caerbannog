package dev.caerbannog.ui.access

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.access.AccessScreen as Screen
import dev.caerbannog.core.access.AccessState
import dev.caerbannog.core.access.AccessTexts
import dev.caerbannog.core.access.MatchAction
import dev.caerbannog.core.access.MatchPreviewState
import dev.caerbannog.core.access.MatchPreviewTexts
import dev.caerbannog.core.access.MatchVerdictView
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.PermissionsScreen
import dev.caerbannog.core.access.PermissionsState
import dev.caerbannog.core.access.PermissionsTexts
import dev.caerbannog.core.access.VerdictIcon
import dev.caerbannog.core.access.VhostRowView
import dev.caerbannog.core.access.VhostsStatus
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.streams.RefreshInterval
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10-04 part B, step 2 (test developer): the match preview form and verdict on the Permissions tab through stable test tags (mockup `docs/ux/Access.dc.html`,
 * frames `perm-admin`, `perm-verdicts`, `layout-narrow-perm`). The screen draws the strings of [MatchPreviewState] and decides none. Red until the form is drawn
 * (the `matchPreview` and `matchPreviewEvents` parameters of `AccessScreen` exist as a stub that ignores them).
 *
 * Tags the form must have (keep stable): `accessMatch` (the form, below the topic table), `accessMatchHeading`, `accessMatchAction:<publish|read|configure>`
 * (one clickable choice each, with its label as text), `accessMatchVhost` (shows the chosen host, opens the list), `accessMatchVhostOption:<vhost>`,
 * `accessMatchNameLabel` (Exchange / Queue / Name), `accessMatchName` (text field), `accessMatchRoutingKey` (text field, only when the state says so),
 * `accessMatchCheck` (button, text "Check"), and for a verdict: `accessMatchVerdict` (root), `accessMatchVerdictIcon:<Match|NoMatch|Unreadable|NotReadable>`,
 * `accessMatchVerdictTitle`, `accessMatchVerdictSentence`, `accessMatchSource` (absent when the state has none), `accessMatchNote`.
 * R1 (architect, 2026-11-27): when `checkEnabled` is false (Locked, TokenUser) the form is drawn dimmed, `accessMatchCheck` is disabled, and a marker `accessMatchDimmed`
 * exists inside the form root `accessMatch` (absent otherwise; it is how a test sees the dimming, which is not readable from the semantics tree).
 * The form is drawn on the Loaded, Locked and TokenUser frames of the tab and not while loading or not connected. The existing tags of the tab stay.
 */
@OptIn(ExperimentalTestApi::class)
class MatchPreviewUiTest {

    private class Events : MatchPreviewEvents {
        val log = mutableListOf<String>()
        override fun chooseAction(action: MatchAction) { log += "action:$action" }
        override fun chooseVhost(vhost: String) { log += "vhost:$vhost" }
        override fun setName(text: String) { log += "name:$text" }
        override fun setRoutingKey(text: String) { log += "key:$text" }
        override fun check() { log += "check" }
    }

    private class WhoEvents : AccessEvents {
        override fun setShown(shown: Boolean) = Unit
        override fun refreshNow() = Unit
        override fun retry() = Unit
    }

    private class PermEvents : PermissionsEvents {
        override fun setShown(shown: Boolean) = Unit
        override fun refreshNow() = Unit
        override fun retry() = Unit
        override fun chooseUser(key: String) = Unit
        override fun setUserFilter(text: String) = Unit
        override fun setVhostFilter(text: String) = Unit
    }

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

    private val who = AccessState(
        screen = Screen.Loaded, message = null, readOnlyNote = AccessTexts.READ_ONLY_NOTE, user = "ops-admin", tags = listOf("administrator"),
        vhostsStatus = VhostsStatus.Listed, vhosts = listOf(VhostRowView("/orders", null)),
    )

    private fun cell(text: String) = PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))

    private val loaded = PermissionsState(
        screen = PermissionsScreen.Loaded,
        message = null,
        heading = "Permissions of svc-orders",
        readLabel = PermissionsTexts.READ_FROM_BROKER,
        chosenUser = "svc-orders",
        rows = listOf(PermissionRow("/orders", cell("^queue\\..*"), cell("^(ex|queue)\\..*"), cell(".*"))),
    )

    private val locked = PermissionsState(
        screen = PermissionsScreen.Locked, message = PermissionsTexts.LOCKED_TITLE, detail = PermissionsTexts.lockedDetail("svc-explorer"),
    )

    private val token = PermissionsState(
        screen = PermissionsScreen.TokenUser, message = PermissionsTexts.TOKEN_TITLE, detail = PermissionsTexts.tokenDetail("jdoe"), chosenUser = "jdoe",
    )

    private val form = MatchPreviewState(vhosts = listOf("/billing", "/orders"), vhost = "/orders", name = "ex.orders", routingKey = "orders.created")

    private fun verdict(icon: VerdictIcon, title: String, sentence: String, source: String? = MatchPreviewTexts.SOURCE_BROKER) =
        MatchVerdictView(icon, title, sentence, source, MatchPreviewTexts.NOTE)

    private val match = verdict(VerdictIcon.Match, "Would match.", "The write pattern ^(ex|queue)\\..* matches ex.orders on /orders.")

    private fun DesktopComposeUiTest.show(
        match: MatchPreviewState = form,
        permissions: PermissionsState = loaded,
        events: Events = Events(),
        width: Int = 1280,
        fontScale: Float = 1f,
    ): Events {
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale),
            ) {
                AccessScreen(
                    who, null, 10_000L, WhoEvents(), control, noop,
                    permissions = permissions, permissionsEvents = PermEvents(), matchPreview = match, matchPreviewEvents = events,
                )
            }
        }
        onNodeWithTag("accessTabPermissions").performClick()
        return events
    }

    private fun DesktopComposeUiTest.text(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text
                ?: n.children.joinToString(" ") { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() }
        }

    private fun DesktopComposeUiTest.absent(vararg tags: String) = tags.forEach { onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(0) }

    // ---------------------------------------------------------------- the form

    @Test
    fun `the form is drawn under the tables with the heading, three actions, host, name, routing key and Check`() = runDesktopComposeUiTest(1280, 800) {
        show()
        onNodeWithTag("accessMatch").assertExists()
        assertEquals(MatchPreviewTexts.HEADING, text("accessMatchHeading"))
        assertEquals("Publish to an exchange", text("accessMatchAction:publish"))
        assertEquals("Read from a queue", text("accessMatchAction:read"))
        assertEquals("Configure", text("accessMatchAction:configure"))
        absent("accessMatchAction:bind", "accessMatchAction:stream")
        assertEquals("/orders", text("accessMatchVhost"))
        assertEquals("Exchange", text("accessMatchNameLabel"))
        assertEquals("ex.orders", text("accessMatchName"))
        onNodeWithTag("accessMatchRoutingKey").assertExists()
        assertEquals("orders.created", text("accessMatchRoutingKey"))
        assertEquals("Check", text("accessMatchCheck"))
    }

    @Test
    fun `the tables of the tab and their tags stay when the form is added`() = runDesktopComposeUiTest(1280, 800) {
        show()
        onNodeWithTag("accessPermissionsTable").assertExists()
        assertEquals("^(ex|queue)\\..*", text("accessCell:/orders:write"))
        assertEquals("Permissions of svc-orders", text("accessPermissionsHeading"))
        assertEquals(PermissionsTexts.READ_FROM_BROKER, text("accessPermissionsReadLabel"))
    }

    @Test
    fun `before Check no verdict and no note are drawn (the mockup draws none)`() = runDesktopComposeUiTest(1280, 800) {
        show()
        absent("accessMatchVerdict", "accessMatchVerdictTitle", "accessMatchNote", "accessMatchSource")
    }

    @Test
    fun `the name label comes from the state and the routing key box is absent for read and configure`() = runDesktopComposeUiTest(1280, 800) {
        show(form.copy(action = MatchAction.Read, nameLabel = "Queue", showRoutingKey = false))
        assertEquals("Queue", text("accessMatchNameLabel"))
        absent("accessMatchRoutingKey")
    }

    @Test
    fun `the name box shows amq default when the state says so, never an empty box`() = runDesktopComposeUiTest(1280, 800) {
        show(form.copy(name = "amq.default"))
        assertEquals("amq.default", text("accessMatchName"))
    }

    // ---------------------------------------------------------------- events

    @Test
    fun `choosing an action sends it`() = runDesktopComposeUiTest(1280, 800) {
        val e = show()
        onNodeWithTag("accessMatchAction:read").performClick()
        onNodeWithTag("accessMatchAction:configure").performClick()
        onNodeWithTag("accessMatchAction:publish").performClick()
        assertEquals(listOf("action:Read", "action:Configure", "action:Publish"), e.log)
    }

    @Test
    fun `choosing a virtual host from the list sends it`() = runDesktopComposeUiTest(1280, 800) {
        val e = show()
        onNodeWithTag("accessMatchVhost").performClick()
        onNodeWithTag("accessMatchVhostOption:/billing").performClick()
        assertEquals(listOf("vhost:/billing"), e.log)
    }

    @Test
    fun `typing in the name box and the routing key box sends what is typed`() = runDesktopComposeUiTest(1280, 800) {
        val e = show(form.copy(name = "", routingKey = ""))
        onNodeWithTag("accessMatchName").performTextInput("ex.x")
        onNodeWithTag("accessMatchRoutingKey").performTextInput("a.b")
        assertTrue(e.log.contains("name:ex.x"), "events were ${e.log}")
        assertTrue(e.log.contains("key:a.b"), "events were ${e.log}")
    }

    @Test
    fun `Check sends check and nothing else`() = runDesktopComposeUiTest(1280, 800) {
        val e = show()
        onNodeWithTag("accessMatchCheck").performClick()
        assertEquals(listOf("check"), e.log)
    }

    @Test
    fun `the form has no event that sends anything to the broker`() {
        val methods = MatchPreviewEvents::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("chooseAction", "chooseVhost", "setName", "setRoutingKey", "check"), methods, "a new event on the match preview needs a decision")
    }

    // ---------------------------------------------------------------- verdicts

    @Test
    fun `would match shows icon, title, sentence, source and the prediction note`() = runDesktopComposeUiTest(1280, 800) {
        show(form.copy(verdict = match))
        onNodeWithTag("accessMatchVerdict").assertExists()
        onNodeWithTag("accessMatchVerdictIcon:Match", useUnmergedTree = true).assertExists()
        assertEquals("Would match.", text("accessMatchVerdictTitle"))
        assertEquals("The write pattern ^(ex|queue)\\..* matches ex.orders on /orders.", text("accessMatchVerdictSentence"))
        assertEquals("Patterns read from the broker.", text("accessMatchSource"))
        assertEquals("Prediction from the patterns above; the broker decides. Check sends nothing to the broker.", text("accessMatchNote"))
    }

    private fun DesktopComposeUiTest.assertDrawn(case: MatchVerdictView) {
        show(form.copy(verdict = case))
        onNodeWithTag("accessMatchVerdictIcon:${case.icon.name}", useUnmergedTree = true).assertExists()
        assertEquals(case.title, text("accessMatchVerdictTitle"))
        assertEquals(case.sentence, text("accessMatchVerdictSentence"))
        assertEquals(MatchPreviewTexts.NOTE, text("accessMatchNote"))
        if (case.source == null) absent("accessMatchSource") else assertEquals(case.source, text("accessMatchSource"))
    }

    @Test
    fun `the would not match verdict is drawn with its icon, sentence and note`() = runDesktopComposeUiTest(1280, 800) {
        assertDrawn(verdict(VerdictIcon.NoMatch, "Would not match.", "The write pattern ^invoices$ does not match ex.orders on /billing."))
    }

    @Test
    fun `the could not be read verdict is drawn with its icon, sentence and note`() = runDesktopComposeUiTest(1280, 800) {
        assertDrawn(verdict(VerdictIcon.Unreadable, "Pattern could not be read.", "The write pattern ([unclosed is not a pattern this app can apply. No answer is guessed."))
    }

    @Test
    fun `the not readable verdict is drawn with its icon, sentence and note and no source`() = runDesktopComposeUiTest(1280, 800) {
        assertDrawn(verdict(VerdictIcon.NotReadable, "Permissions not readable.", MatchPreviewTexts.LOCKED_SENTENCE, source = null))
    }

    @Test
    fun `the topic verdict is drawn with the match icon, sentence and note`() = runDesktopComposeUiTest(1280, 800) {
        assertDrawn(verdict(VerdictIcon.Match, "Would match (topic permission).", "The topic write pattern ^orders\\..* of amq.topic matches the routing key orders.created."))
    }

    @Test
    fun `would not match is drawn with the block icon and not the match icon`() = runDesktopComposeUiTest(1280, 800) {
        show(form.copy(verdict = verdict(VerdictIcon.NoMatch, "Would not match.", "svc-orders has no permission on /billing.")))
        onNodeWithTag("accessMatchVerdictIcon:NoMatch", useUnmergedTree = true).assertExists()
        absent("accessMatchVerdictIcon:Match")
        assertEquals("svc-orders has no permission on /billing.", text("accessMatchVerdictSentence"))
    }

    @Test
    fun `a verdict with no source draws no source line`() = runDesktopComposeUiTest(1280, 800) {
        show(form.copy(verdict = verdict(VerdictIcon.NotReadable, "Permissions not readable.", MatchPreviewTexts.LOCKED_SENTENCE, source = null)))
        absent("accessMatchSource")
        assertEquals("Permissions not readable.", text("accessMatchVerdictTitle"))
    }

    // ---------------------------------------------------------------- frames

    @Test
    fun `the form is drawn on the locked frame dimmed, Check disabled, with the verdict already shown`() = runDesktopComposeUiTest(1280, 800) {
        show(
            form.copy(
                verdict = verdict(VerdictIcon.NotReadable, "Permissions not readable.", MatchPreviewTexts.LOCKED_SENTENCE, source = null),
                vhosts = listOf("/orders"), checkEnabled = false,
            ),
            permissions = locked,
        )
        onNodeWithTag("accessMatch").assertExists()
        onNodeWithTag("accessMatchDimmed", useUnmergedTree = true).assertExists()
        onNodeWithTag("accessMatchCheck").assertIsNotEnabled()
        assertEquals("Permissions not readable.", text("accessMatchVerdictTitle"))
        assertEquals(PermissionsTexts.LOCKED_TITLE, text("accessPermissionsMessage"))
        assertEquals("/orders", text("accessMatchVhost"))
    }

    @Test
    fun `the form is drawn on the token user frame dimmed, Check disabled, with the verdict already shown`() = runDesktopComposeUiTest(1280, 800) {
        show(
            form.copy(
                verdict = verdict(VerdictIcon.NotReadable, "Permissions not readable.", PermissionsTexts.tokenDetail("jdoe"), source = null),
                vhosts = listOf("/orders"), checkEnabled = false,
            ),
            permissions = token,
        )
        onNodeWithTag("accessMatch").assertExists()
        onNodeWithTag("accessMatchDimmed", useUnmergedTree = true).assertExists()
        onNodeWithTag("accessMatchCheck").assertIsNotEnabled()
        assertEquals(PermissionsTexts.tokenDetail("jdoe"), text("accessMatchVerdictSentence"))
    }

    @Test
    fun `on a loaded frame Check is enabled and the form is not dimmed`() = runDesktopComposeUiTest(1280, 800) {
        show()
        onNodeWithTag("accessMatchCheck").assertIsEnabled()
        absent("accessMatchDimmed")
    }

    @Test
    fun `no form while the tab is loading or not connected`() = runDesktopComposeUiTest(1280, 800) {
        show(form, permissions = PermissionsState(screen = PermissionsScreen.Loading, message = PermissionsTexts.LOADING))
        absent("accessMatch", "accessMatchCheck")
    }

    @Test
    fun `no form when no connection is held`() = runDesktopComposeUiTest(1280, 800) {
        show(form, permissions = PermissionsState())
        absent("accessMatch", "accessMatchCheck")
    }

    @Test
    fun `in a narrow window the form and the verdict are still all there`() = runDesktopComposeUiTest(480, 900) {
        show(form.copy(verdict = match))
        for (tag in listOf("accessMatchCheck", "accessMatchName", "accessMatchVhost", "accessMatchVerdictTitle", "accessMatchNote")) {
            onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `at font scale 1_5 in a narrow window the form and the verdict are still all there (layout-fs15)`() =
        runDesktopComposeUiTest(480, 900) {
            show(form.copy(verdict = match), fontScale = 1.5f)
            for (tag in listOf("accessMatchCheck", "accessMatchName", "accessMatchRoutingKey", "accessMatchVhost", "accessMatchVerdictSentence", "accessMatchNote")) {
                onNodeWithTag(tag, useUnmergedTree = true).assertExists()
            }
        }
}
