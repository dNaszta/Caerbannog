package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.ApplyOutcome
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.migrations.MigrationsWording
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.ui.migrate3x.allText
import dev.caerbannog.ui.migrate3x.assertDrawn
import dev.caerbannog.ui.migrate3x.assertNotDrawn
import dev.caerbannog.ui.migrate3x.assertTextHas
import dev.caerbannog.ui.migrate3x.nodes
import dev.caerbannog.ui.migrate3x.textOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-14b (owner decisions D2, D4): the Migrations screen draws the top line advice and the How to link, the tips of the folder, the dry run, a modified file and a raw view with a hash,
 * and the advice under the apply outcome banner. Tags `migrations-advice-top|outcome`, `howto-apply-migrations`, `help-tip-mig-<row>`. Red against the stubs.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsHelpUiTest {
    private fun file(version: String = "0003", vararg actions: MigrationAction) = MigrationFile(
        version, "Desc $version", actions.toList().ifEmpty { listOf(MigrationAction.DeclareQueue("orders", "quorum")) }, """{ "version": "$version" }""", "abc$version",
    )

    private fun state(f: MigrationFile = file(), status: MigrationStatus = MigrationStatus.Pending, override: MigrationsScreenState.() -> MigrationsScreenState = { this }) = MigrationsScreenState(
        entries = listOf(MigrationEntry(f, status)), selectedVersion = f.version, selectedFile = f,
        dryRun = MigrationDryRun(f.version, f.up.map { ActionDryRun(it, allowed = true) }, canApply = true),
        isApplyPendingEnabled = true, isApplySingleEnabled = true, profileName = "uat-eu", folder = MigrationFolderState.Ready("/Users/dana/work/migrations"),
    ).override()

    private fun DesktopComposeUiTest.show(state: MigrationsScreenState) = setContent {
        MigrationsScreen(
            state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
        )
    }

    private fun DesktopComposeUiTest.assertTip(id: String) {
        val tag = "help-tip-$id"
        val expected = MigrationsWording.HELP_TIPS.firstOrNull { it.id == id }?.text
        assertTrue(!expected.isNullOrBlank(), "no text for $id in MigrationsWording.HELP_TIPS")
        assertDrawn(tag)
        assertEquals(1, nodes(tag).size, "$tag is drawn once")
        assertTrue(textOf(tag).contains(expected!!), "the tip $tag should describe itself as \"$expected\" but says \"${textOf(tag)}\"")
    }

    private fun DesktopComposeUiTest.assertAdvice(tag: String, text: String) {
        assertTrue(text.isNotBlank(), "the advice text for $tag is empty")
        assertTextHas(tag, text)
        assertTrue(onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "$text must be visible text without hovering")
    }

    @Test
    fun `the title row has the advice line and the How to link`() = runDesktopComposeUiTest(1280, 900) {
        show(state())
        assertAdvice("migrations-advice-top", MigrationsWording.ADVICE_TOP)
        assertDrawn("howto-apply-migrations")
        assertDrawn("migrations:title")
    }

    @Test
    fun `the folder row and the dry run have their tips`() = runDesktopComposeUiTest(1280, 900) {
        show(state())
        assertDrawn("migrations:folderRow")
        assertTip("mig-23"); assertTip("mig-24")
    }

    @Test
    fun `a pending file has no modified tip and a topology file has no hash tip`() = runDesktopComposeUiTest(1280, 900) {
        show(state())
        assertNotDrawn("help-tip-mig-26"); assertNotDrawn("help-tip-mig-27")
    }

    @Test
    fun `a modified file has the tip that it changed after it was applied`() = runDesktopComposeUiTest(1280, 900) {
        show(state(status = MigrationStatus.Modified("abc", "def")))
        assertTip("mig-26")
    }

    @Test
    fun `a file that holds a hash has the raw view tip and keeps its edit text`() = runDesktopComposeUiTest(1280, 900) {
        val access = file("0001", MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH"), "rabbit_password_hashing_sha256"))
        show(state(access))
        assertTip("mig-27")
        assertTrue(onAllNodes(hasText(MigrationsWording.HASH_FILE_NO_EDIT), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the existing text stays")
        assertTrue(!wholeText().contains("SENTINEL-HASH"), "no hash on the screen")
    }

    private fun DesktopComposeUiTest.wholeText(): String =
        onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { it.allText() }

    @Test
    fun `a conflict banner is followed by the conflict advice`() = runDesktopComposeUiTest(1280, 900) {
        val outcome = ApplyOutcome.Conflict("0003", 1, 1, "queue exists with other arguments", "orders")
        show(state { copy(applyOutcome = outcome) })
        assertDrawn("migrations:errorBanner")
        assertAdvice("migrations-advice-outcome", MigrationsWording.applyAdvice(outcome).orEmpty())
    }

    @Test
    fun `a failed action banner is followed by the stopped-at-this-action advice`() = runDesktopComposeUiTest(1280, 900) {
        val outcome = ApplyOutcome.Failed("0003", 1, 1, "the broker refused")
        show(state { copy(applyOutcome = outcome) })
        assertAdvice("migrations-advice-outcome", MigrationsWording.applyAdvice(outcome).orEmpty())
    }

    @Test
    fun `no outcome means no outcome advice`() = runDesktopComposeUiTest(1280, 900) {
        show(state())
        assertNotDrawn("migrations-advice-outcome")
    }
}
