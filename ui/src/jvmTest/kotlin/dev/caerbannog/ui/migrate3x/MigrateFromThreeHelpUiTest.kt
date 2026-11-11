package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.LeftOutRow
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SavedFile
import dev.caerbannog.ui.migrate3x.ReviewScreenFiles.ORDERS_GROUP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-14 (owner decisions D2, D4): the Migrate from 3.x screen draws a tip next to every term of the table and the always visible advice lines, as plain text
 * (never only a tooltip), and a "How to" link on its top line. Tags: `help-tip-m3x-<n>`, `migrate3x-advice-<top|vhost|next-step|delete-file>`, `howto-analyse-generate`.
 * Red against the stubs (the screen draws none of them). Row 19 (the 3.x pill) lives on the Connections screen and is not part of this round.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeHelpUiTest {
    private fun DesktopComposeUiTest.assertTip(n: Int) {
        val tag = "help-tip-m3x-$n"
        assertDrawn(tag)
        val expected = MigrateFromThreeTexts.HELP_TIPS.firstOrNull { it.id == "m3x-$n" }?.text
        assertTrue(expected != null && expected.isNotBlank(), "row $n has no text in MigrateFromThreeTexts.HELP_TIPS")
        assertEquals(1, nodes(tag).size, "$tag is drawn once")
        assertTrue(textOf(tag).contains(expected!!), "the tip $tag should describe itself as \"$expected\" but says \"${textOf(tag)}\"")
    }

    /** An advice line is displayed text of its own, not a tooltip and not a content description. */
    private fun DesktopComposeUiTest.assertAdvice(tag: String, text: String) {
        assertTrue(text.isNotBlank(), "the advice text for $tag is empty")
        assertDrawn(tag)
        assertTextHas(tag, text)
        assertTrue(onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "$text must be visible text without hovering")
    }

    @Test
    fun `source choice - the top line advice and the How to link, the info line stays, tips on the two sources`() = runDesktopComposeUiTest(1280, 900) {
        show(MigrateFromThreeState.ChooseSource)
        assertAdvice("migrate3x-advice-top", MigrateFromThreeTexts.ADVICE_TOP)
        assertTextHas("migrate3x-info", MigrateFromThreeTexts.INFO_LINE)
        assertDrawn("howto-analyse-generate")
        assertTip(3); assertTip(4)
    }

    @Test
    fun `the virtual host prompt says the host must already exist and that no access migration is possible`() = runDesktopComposeUiTest(1280, 900) {
        show(MigrateFromThreeState.NeedsVirtualHost("one-vhost.json", null))
        assertAdvice("migrate3x-advice-vhost", MigrateFromThreeTexts.ADVICE_VHOST)
        assertDrawn("migrate3x-vhost-field")
    }

    @Test
    fun `the report has the How to link and tips on the chips and the message-loss block`() = runDesktopComposeUiTest(1280, 900) {
        show(MigrateScreenFiles.report())
        assertDrawn("howto-analyse-generate")
        assertTip(6); assertTip(7)
        assertDrawn("migrate3x-loss-banner")
    }

    @Test
    fun `the decision bar, the review sheet and the generate button carry their tips`() = runDesktopComposeUiTest(1280, 1000) {
        val rig = LiveRig(ReviewScreenFiles.orders)
        showLive(rig)
        assertTip(8)
        clickReview(rig, ORDERS_GROUP, "orders")
        assertDrawn("migrate3x-review-sheet")
        assertTip(9); assertTip(10); assertTip(11); assertTip(12)
        click("migrate3x-tick-${Acknowledgement.OrderingByPriorityMayChange.name}")
        click("migrate3x-accept")
        assertDrawn("migrate3x-generate")
        assertTip(13)
    }

    private fun result(withAccess: Boolean) = GenerationReport(
        how = How.Saved, headline = "2 migrations saved",
        files = listOf(SavedFile("0001", "access", "Access migrated from RabbitMQ 3.x legacy.json", 9, "/m/0001.json"), SavedFile("0002", "/", "Migrated from RabbitMQ 3.x legacy.json vhost /", 214, "/m/0002.json")),
        leftOut = emptyList(), notes = emptyList(), vhostHint = null,
        accessLine = if (withAccess) "Access: 1 virtual host, 4 users, 6 permissions, 1 topic permission" else null,
        usersNotMigrated = if (withAccess) listOf(LeftOutRow("guest", "not created; it has a publicly known password")) else emptyList(),
        permissionsLeftOut = if (withAccess) listOf(LeftOutRow("guest on /", "the user is not migrated")) else emptyList(),
        secretWarning = if (withAccess) "This file contains password hashes. Anyone who has it can try to guess the passwords offline. Keep it secret: do not commit it to git or put it in a shared or synced folder." else null,
    )

    private fun DesktopComposeUiTest.showResult(report: GenerationReport) {
        val review = ReviewState(1, 0, 1, 0, 0, "Every item is decided", null, false, null, GenerationState.Done(report))
        show(MigrateScreenFiles.report().copy(review = review))
    }

    @Test
    fun `the result says what to do next, explains the access card and the two lists, and tells to delete the secret file`() = runDesktopComposeUiTest(1280, 1100) {
        showResult(result(withAccess = true))
        assertDrawn("migrate3x-result")
        assertAdvice("migrate3x-advice-next-step", MigrateFromThreeTexts.ADVICE_NEXT_STEP)
        assertTip(15); assertTip(16); assertTip(17)
        assertDrawn("migrate3x-access-warning")
        assertAdvice("migrate3x-advice-delete-file", MigrateFromThreeTexts.DELETE_THE_FILE)
    }

    @Test
    fun `a result without access data has the next step line but no access tips and no delete-the-file line`() = runDesktopComposeUiTest(1280, 1100) {
        showResult(result(withAccess = false))
        assertAdvice("migrate3x-advice-next-step", MigrateFromThreeTexts.ADVICE_NEXT_STEP)
        listOf("help-tip-m3x-15", "help-tip-m3x-16", "help-tip-m3x-17", "migrate3x-advice-delete-file").forEach { assertNotDrawn(it) }
    }

    @Test
    fun `the existing words stay`() = runDesktopComposeUiTest(1280, 900) {
        show(MigrateFromThreeState.ChooseSource)
        assertDrawn("migrate3x-choose-file")
        assertTextHas("migrate3x-choose-file", MigrateFromThreeTexts.CHOOSE_FILE)
    }
}
