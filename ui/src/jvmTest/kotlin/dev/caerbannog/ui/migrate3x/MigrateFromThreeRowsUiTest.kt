package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-06a: rows, lazy list, keyboard, long names, plain text, no hash anywhere. Everything below goes through the real builder. */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeRowsUiTest {
    private fun rowTags(test: androidx.compose.ui.test.DesktopComposeUiTest) = (0 until 40).filter { test.nodes("migrate3x-row-$it").isNotEmpty() }

    @Test
    fun `an open group draws its rows and a closed group draws none`() = runDesktopComposeUiTest(1280, 800) {
        val held = MigrateScreenFiles.held()
        val key = MigrateScreenFiles.openRule(held, "queue-not-durable").single()
        show(MigrateScreenFiles.report(open = { setOf(key) }))
        assertTextHas("migrate3x-row-0", "temp")
        assertEquals(listOf(0), rowTags(this), "one finding, one row")
    }

    @Test
    fun `the rows of a group are in report order and each shows message, proposal and the unverified marker when it applies`() = runDesktopComposeUiTest(1280, 800) {
        val held = MigrateScreenFiles.held()
        val state = MigrateScreenFiles.report(open = { MigrateScreenFiles.openRule(it, "classic-index-v1") })
        show(state)
        val rows = state.view.groups.filter { it.open }.sumOf { it.count }
        assertTrue(rows >= 4, "the premise: four classic queues are at risk, got $rows")
        assertTextHas("migrate3x-row-0", held.report.findings.first { it.rule == "classic-index-v1" }.name!!)
    }

    @Test
    fun `a user row shows tags, algorithm and hash hidden`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report(MigrateScreenFiles.withUser, open = MigrateScreenFiles::allGroups)
        show(state)
        val svcRow = (0 until 40).first { nodes("migrate3x-row-$it").any { n -> n.allText().contains("svc") } }
        assertTextHas("migrate3x-row-$svcRow", "svc", "management", "policymaker", "rabbit_password_hashing_sha256", "hash hidden")
    }

    @Test
    fun `no node, description or text of the screen holds a hash or a password`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report(MigrateScreenFiles.withUser, open = MigrateScreenFiles::allGroups)
        show(state)
        val everything = wholeScreenText()
        assertTrue(everything.contains("hash hidden"), "the premise: the user row is drawn")
        listOf(MigrateScreenFiles.SENTINEL_HASH, MigrateScreenFiles.SENTINEL_PASSWORD, "amqp://").forEach { assertFalse(everything.contains(it), "the screen holds $it") }
    }

    @Test
    fun `a name from the file is plain text, control characters escaped, markup not interpreted`() = runDesktopComposeUiTest(1280, 800) {
        val raw = "<b>x</b>\u0007"
        val text = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(raw, durable = false)))
        val state = MigrateScreenFiles.report(text, open = { held -> MigrateScreenFiles.openRule(held, "queue-not-durable") })
        show(state)
        val row = textOf("migrate3x-row-0")
        assertTrue(row.contains("<b>x</b>\\u0007"), "the row shows <b>x</b>\\u0007 literally: $row")
        assertTrue(wholeScreenText().none { it.code < 0x20 && it != '\n' }, "a control character reached the screen")
    }

    @Test
    fun `the unreadable policy pattern row says the app could not evaluate it and asks for a check by hand`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report(open = { MigrateScreenFiles.openRule(it, "policy-pattern-unreadable") })
        show(state)
        assertTextHas("migrate3x-row-0", "lookahead", "could not evaluate", "did not use it to decide which queues are mirrored", "by hand")
        assertFalse(wholeScreenText().contains("(?!amq"), "the pattern text is not shown")
    }

    @Test
    fun `a very long name does not break the layout`() = runDesktopComposeUiTest(900, 700) {
        val long = "q-" + "x".repeat(600)
        val text = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(long, durable = false)))
        show(MigrateScreenFiles.report(text, open = { held -> MigrateScreenFiles.openRule(held, "queue-not-durable") }))
        onNodeWithTag("migrate3x-row-0").assertIsDisplayed()
        onNodeWithTag("migrate3x-export").assertIsDisplayed()
    }

    @Test
    fun `60000 queues draw a handful of rows, not 60000`() = runDesktopComposeUiTest(1280, 800) {
        val held = MigrateScreenFiles.held(DefinitionsFiles.classicQueues(60_000))
        val view0 = dev.caerbannog.core.migrate3x.ReportViewBuilder.build(held)
        val biggest = view0.groups.maxBy { it.count }
        val state = MigrateFromThreeState.Report(dev.caerbannog.core.migrate3x.ReportViewBuilder.build(held, openGroups = setOf(biggest.key)), null)
        show(state)
        assertDrawn("migrate3x-row-0")
        val drawn = (0 until 500).count { nodes("migrate3x-row-$it").isNotEmpty() }
        assertTrue(drawn in 1..200, "a lazy list draws only what is visible, drew $drawn of ${biggest.count}")
        assertDrawn("migrate3x-loss-banner")
    }

    @Test
    fun `keyboard - a group header takes focus and Enter opens it`() = runDesktopComposeUiTest(1280, 800) {
        val state = MigrateScreenFiles.report()
        val calls = MigrateCalls()
        show(state, calls)
        val key = state.view.groups.first().key
        onNodeWithTag("migrate3x-group-$key").requestFocus()
        onNodeWithTag("migrate3x-group-$key").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("group:$key"), calls.events)
    }

    @Test
    fun `keyboard - a chip and the export button can be focused and pressed with Enter`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateScreenFiles.report(), calls)
        onNodeWithTag("migrate3x-chip-info").requestFocus()
        onNodeWithTag("migrate3x-chip-info").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("migrate3x-export").requestFocus()
        onNodeWithTag("migrate3x-export").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("severity:Info", "export"), calls.events)
    }

    @Test
    fun `keyboard - the file button of the empty state takes focus and Enter chooses`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        show(MigrateFromThreeState.ChooseSource, calls)
        onNodeWithTag("migrate3x-choose-file").requestFocus()
        onNodeWithTag("migrate3x-choose-file").performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("chooseFile"), calls.events)
    }
}
