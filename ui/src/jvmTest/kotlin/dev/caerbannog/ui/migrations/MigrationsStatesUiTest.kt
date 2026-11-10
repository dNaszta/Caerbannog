package dev.caerbannog.ui.migrations

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.ApplyOutcome
import dev.caerbannog.core.migrations.ApplyProgress
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.HistoryUnreadable
import dev.caerbannog.core.migrations.InvalidMigrationFile
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationFolderState
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M5-08c2 step 2: the Migrations screen states of `docs/ux/Migrations.dc.html`, one test per row of the table of
 * `docs/tasks/M5-08c2-migrations-screen-states.md`. State in, events out: the screen is given a [MigrationsScreenState] and records which
 * callback was called. Texts are read from the semantics tree (all `Text` below the tagged node), so a test does not care how the banner is built.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsStatesUiTest {

    private class Calls {
        val events = mutableListOf<String>()
        fun record(name: String): () -> Unit = { events += name }
    }

    private val folder = "/Users/dana/work/migrations"
    private val historyFile = "/Users/dana/.caerbannog/migration-history.json"

    private fun file(version: String) = MigrationFile(
        version = version, description = "Desc $version",
        up = listOf(MigrationAction.DeclareQueue("orders", "quorum")),
        rawJson = """{ "version": "$version" }""", checksum = "abc$version",
    )

    /** A state with one pending migration selected, as the view model builds it, overridden by [override]. */
    private fun state(override: MigrationsScreenState.() -> MigrationsScreenState = { this }): MigrationsScreenState {
        val f = file("0003")
        return MigrationsScreenState(
            entries = listOf(MigrationEntry(f, MigrationStatus.Pending)),
            selectedVersion = "0003", selectedFile = f,
            dryRun = MigrationDryRun("0003", listOf(ActionDryRun(f.up.first(), allowed = true)), canApply = true, blockedReason = null),
            isApplyPendingEnabled = true, isApplySingleEnabled = true, profileName = "uat-eu",
            folder = MigrationFolderState.Ready(folder),
        ).override()
    }

    private fun DesktopComposeUiTest.show(state: MigrationsScreenState, calls: Calls = Calls()) {
        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = { calls.events += "select:$it" }, onApplyPending = calls.record("applyPending"),
                onApplySingle = { calls.events += "applySingle:$it" }, onImportJson = { calls.events += "import" },
                onExportTopology = calls.record("export"), onUpdateFile = { _, _ -> calls.events += "update" },
                onValidateAgain = calls.record("validate"), onDismissError = calls.record("dismissError"),
                onChooseFolder = calls.record("chooseFolder"), onChangeFolder = calls.record("changeFolder"),
                onRetry = calls.record("retry"), onShowFolder = { calls.events += "showFolder:$it" },
                onStartWithEmptyHistory = calls.record("startEmpty"), onConfirmEmptyHistory = calls.record("confirmEmpty"),
                onCancelEmptyHistory = calls.record("cancelEmpty"), onDismissOutcome = calls.record("dismissOutcome"),
            )
        }
    }

    private fun SemanticsNode.allText(): String {
        val own = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        return (listOf(own) + children.map { it.allText() }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Everything written inside the node with [tag]; fails with a clear message when the node is not on the screen. */
    private fun DesktopComposeUiTest.textOf(tag: String): String {
        val node = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(node.isNotEmpty(), "nothing is drawn with the test tag $tag")
        return node.first().allText()
    }

    private fun DesktopComposeUiTest.assertDrawn(tag: String) {
        assertTrue(onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "nothing is drawn with the test tag $tag")
    }

    private fun DesktopComposeUiTest.assertNotDrawn(tag: String) =
        onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(0)

    private fun DesktopComposeUiTest.assertTextHas(tag: String, vararg parts: String) {
        val text = textOf(tag)
        for (part in parts) assertTrue(text.contains(part), "the node $tag should say \"$part\" but says \"$text\"")
    }

    // ---- 1. folder row -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a chosen folder is shown in a row with Show folder and Change folder`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state(), calls)

        assertDrawn("migrations:folderRow")
        assertTextHas("migrations:folderRow", "Migration files in")
        assertEquals(folder, textOf("migrations:folderPath"))
        onNodeWithTag("migrations:showFolderBtn").assertIsEnabled().performClick()
        onNodeWithTag("migrations:changeFolderBtn").assertIsEnabled().performClick()
        assertEquals(listOf("showFolder:$folder", "changeFolder"), calls.events)
    }

    @Test
    fun `Show folder is not drawn for a missing folder, and the row keeps Change folder`(): Unit = runDesktopComposeUiTest {
        show(state { copy(folder = MigrationFolderState.FolderUnavailable(this@MigrationsStatesUiTest.folder, FolderProblem.MISSING), entries = emptyList(), selectedFile = null) })

        assertNotDrawn("migrations:showFolderBtn")
        onNodeWithTag("migrations:changeFolderBtn").assertIsDisplayed()
    }

    @Test
    fun `Show folder is drawn when the path exists but is unusable`(): Unit = runDesktopComposeUiTest {
        show(state { copy(folder = MigrationFolderState.FolderUnavailable(this@MigrationsStatesUiTest.folder, FolderProblem.UNREADABLE), entries = emptyList(), selectedFile = null) })

        onNodeWithTag("migrations:showFolderBtn").assertIsDisplayed()
    }

    @Test
    fun `the folder row is not drawn while no folder is chosen`(): Unit = runDesktopComposeUiTest {
        show(state { copy(folder = MigrationFolderState.ChooseFolder, entries = emptyList(), selectedFile = null) })

        assertNotDrawn("migrations:folderRow")
    }

    @Test
    fun `a folder path with markup characters is plain text`(): Unit = runDesktopComposeUiTest {
        val odd = "/work/<b>x</b>&amp;/migrations"
        show(state { copy(folder = MigrationFolderState.Ready(odd)) })

        assertEquals(odd, textOf("migrations:folderPath"))
    }

    // ---- 2. no folder chosen ----------------------------------------------------------------------------------------------------------------

    private fun chooseState() = state {
        copy(folder = MigrationFolderState.ChooseFolder, entries = emptyList(), selectedFile = null, selectedVersion = null, dryRun = null,
            isApplyPendingEnabled = true, isApplySingleEnabled = true, isImportEnabled = false, isExportEnabled = false)
    }

    @Test
    fun `no folder chosen draws the panel with a Choose folder button that is the only way to open the dialog`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(chooseState(), calls)

        assertTextHas("migrations:chooseFolderPanel", "Choose the folder for your migration files", "Nothing is read or written until you choose a folder")
        onNodeWithTag("migrations:chooseFolderBtn").assertIsEnabled().performClick()
        assertEquals(listOf("chooseFolder"), calls.events, "the dialog is asked for by the click, never by drawing the screen")
    }

    @Test
    fun `no folder chosen disables Import, Export and Apply pending`(): Unit = runDesktopComposeUiTest {
        show(chooseState())

        onNodeWithTag("migrations:importBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:exportBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
    }

    @Test
    fun `drawing the screen calls no callback by itself`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(chooseState(), calls)
        waitForIdle()

        assertEquals(emptyList(), calls.events)
    }

    @Test
    fun `the choose-folder panel is not drawn when a folder is chosen`(): Unit = runDesktopComposeUiTest {
        show(state())

        assertNotDrawn("migrations:chooseFolderPanel")
    }

    // ---- 3. folder unavailable -------------------------------------------------------------------------------------------------------------

    @Test
    fun `a folder that cannot be used says why and where, and offers Try again and Choose another folder`(): Unit = runDesktopComposeUiTest {
        val problem = FolderProblem.MISSING
        val text = "The folder does not exist"
        run {
            val calls = Calls()
            show(state { copy(folder = MigrationFolderState.FolderUnavailable(this@MigrationsStatesUiTest.folder, problem), entries = emptyList(), selectedFile = null, isImportEnabled = false, isExportEnabled = false, isApplyPendingEnabled = false) }, calls)

            assertTextHas("migrations:folderUnavailableBanner", text, folder)
            onNodeWithTag("migrations:retryBtn").performClick()
            onNodeWithTag("migrations:chooseAnotherFolderBtn").performClick()
            assertEquals(listOf("retry", "changeFolder"), calls.events, "for $problem")
        }
    }

    @Test
    fun `the other two folder problems have their own fixed texts`() {
        for ((problem, text) in listOf(FolderProblem.NOT_A_FOLDER to "The path is not a folder", FolderProblem.UNREADABLE to "The folder cannot be read")) {
            runDesktopComposeUiTest {
                show(state { copy(folder = MigrationFolderState.FolderUnavailable(this@MigrationsStatesUiTest.folder, problem), entries = emptyList(), selectedFile = null) })
                assertTextHas("migrations:folderUnavailableBanner", text, folder)
            }
        }
    }

    @Test
    fun `an unusable folder disables Import, Export and Apply pending and lists nothing`(): Unit = runDesktopComposeUiTest {
        show(state { copy(folder = MigrationFolderState.FolderUnavailable(this@MigrationsStatesUiTest.folder, FolderProblem.NOT_A_FOLDER), entries = emptyList(), selectedFile = null, isImportEnabled = false, isExportEnabled = false, isApplyPendingEnabled = false) })

        onNodeWithTag("migrations:importBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:exportBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
        assertNotDrawn("migrationCard:0003")
    }

    @Test
    fun `the unavailable-folder banner is not drawn for a folder that works`(): Unit = runDesktopComposeUiTest {
        show(state())

        assertNotDrawn("migrations:folderUnavailableBanner")
    }

    // ---- 4. read-only folder ---------------------------------------------------------------------------------------------------------------

    private fun readOnlyState() = state { copy(folder = MigrationFolderState.FolderReadOnly(this@MigrationsStatesUiTest.folder), isImportEnabled = false, isExportEnabled = false) }

    @Test
    fun `a read-only folder says so with its path and disables Import, Export and Edit file but not Apply`(): Unit = runDesktopComposeUiTest {
        show(readOnlyState())

        assertTextHas("migrations:readOnlyBanner", "The folder is read-only", folder)
        onNodeWithTag("migrations:importBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:exportBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:editFileBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applyPendingBtn").assertIsEnabled()
        onNodeWithTag("migrations:applySingleBtn").assertIsEnabled()
    }

    @Test
    fun `Edit file is enabled in a writable folder`(): Unit = runDesktopComposeUiTest {
        show(state())

        onNodeWithTag("migrations:editFileBtn").assertIsEnabled()
        assertNotDrawn("migrations:readOnlyBanner")
    }

    // ---- 5. invalid files ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `an invalid file is listed with the Invalid pill and its fixed reason, and cannot be selected`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state { copy(invalidFiles = listOf(InvalidMigrationFile("0007.json", "The file is not valid JSON"))) }, calls)

        assertTextHas("migrationInvalid:0007.json", "0007.json", "Invalid", "The file is not valid JSON")
        onNodeWithTag("migrationInvalid:0007.json").performClick()
        assertEquals(emptyList(), calls.events, "an invalid row cannot be selected or applied")
    }

    @Test
    fun `a file name with markup characters is plain text`(): Unit = runDesktopComposeUiTest {
        val name = "<b>0008</b>&copy;.json"
        show(state { copy(invalidFiles = listOf(InvalidMigrationFile(name, "the value of \"version\" has the wrong type"))) })

        assertTextHas("migrationInvalid:$name", name, "has the wrong type")
    }

    @Test
    fun `invalid files are listed after the valid ones`(): Unit = runDesktopComposeUiTest {
        show(state { copy(invalidFiles = listOf(InvalidMigrationFile("0007.json", "The file is not valid JSON"))) })

        assertDrawn("migrationCard:0003"); assertDrawn("migrationInvalid:0007.json")
        val valid = onAllNodesWithTag("migrationCard:0003", useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot.y
        val invalid = onAllNodesWithTag("migrationInvalid:0007.json", useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot.y
        assertTrue(invalid > valid, "the invalid row (y=$invalid) must come below the valid card (y=$valid)")
    }

    // ---- 6. applying -----------------------------------------------------------------------------------------------------------------------

    private fun applyingState() = state { copy(isApplying = true, applyProgress = ApplyProgress("0003", 2, 3)) }

    @Test
    fun `applying says which action of how many and draws a progress bar`(): Unit = runDesktopComposeUiTest {
        show(applyingState())

        assertTextHas("migrations:applyingBanner", "Applying 0003: action 2 of 3")
        val bar = onAllNodesWithTag("migrations:applyProgress", useUnmergedTree = true).fetchSemanticsNodes().single()
        assertNotNull(bar.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo), "the progress bar must expose its range to screen readers")
    }

    @Test
    fun `while applying Apply pending, Apply single, Import and Export are disabled`(): Unit = runDesktopComposeUiTest {
        show(applyingState())

        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applySingleBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:importBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:exportBtn").assertIsNotEnabled()
        onNodeWithTag("migrationCard:0003").assertIsDisplayed()
    }

    @Test
    fun `no applying banner and no progress bar when nothing runs`(): Unit = runDesktopComposeUiTest {
        show(state())

        assertNotDrawn("migrations:applyingBanner")
        assertNotDrawn("migrations:applyProgress")
    }

    // ---- 7. failed, 8. conflict, 9. not recorded ---------------------------------------------------------------------------------------------

    @Test
    fun `a failed action shows its position, the reason, the reassurance, and Dismiss`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state { copy(applyOutcome = ApplyOutcome.Failed("0003", 2, 3, "the broker refused the request")) }, calls)

        assertTextHas(
            "migrations:failedBanner",
            "Action 2 of 3 failed: the broker refused the request",
            "Migration 0003 was not recorded. Running it again is safe: actions that already succeeded change nothing the second time.",
        )
        assertNotDrawn("migrations:conflictBanner"); assertNotDrawn("migrations:notRecordedBanner")
        onNodeWithTag("migrations:dismissOutcomeBtn").performClick()
        assertEquals(listOf("dismissOutcome"), calls.events)
    }

    @Test
    fun `the failed action's row in the dry run carries the Failed pill, the others do not`(): Unit = runDesktopComposeUiTest {
        val three = file("0003").copy(up = listOf(
            MigrationAction.DeclareQueue("a", "quorum"), MigrationAction.DeclareQueue("b", "quorum"), MigrationAction.DeclareQueue("c", "quorum"),
        ))
        show(state {
            copy(
                entries = listOf(MigrationEntry(three, MigrationStatus.Pending)), selectedFile = three,
                dryRun = MigrationDryRun("0003", three.up.map { ActionDryRun(it, allowed = true) }, canApply = true, blockedReason = null),
                applyOutcome = ApplyOutcome.Failed("0003", 2, 3, "the broker refused the request"),
            )
        })

        assertTextHas("dryRunFailed:1", "Failed")
        assertNotDrawn("dryRunFailed:0"); assertNotDrawn("dryRunFailed:2")
    }

    @Test
    fun `a conflict is its own banner, names the resource and says what to do`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state { copy(applyOutcome = ApplyOutcome.Conflict("0003", 1, 2, "orders exists with different settings", resource = "orders")) }, calls)

        assertTextHas(
            "migrations:conflictBanner",
            "Action 1 of 2 stopped: orders exists with different settings",
            "The existing \"orders\" was not changed. Migration 0003 was not recorded. To go on, change the file or the broker so that they agree, then apply again.",
        )
        assertNotDrawn("migrations:failedBanner")
        onNodeWithTag("migrations:dismissOutcomeBtn").performClick()
        assertEquals(listOf("dismissOutcome"), calls.events)
    }

    @Test
    fun `a conflict whose resource is not known still says the existing resource was not changed`(): Unit = runDesktopComposeUiTest {
        show(state { copy(applyOutcome = ApplyOutcome.Conflict("0003", 1, 1, "The binding exists with different settings")) })

        assertTextHas("migrations:conflictBanner", "The existing resource was not changed")
    }

    @Test
    fun `a resource name with markup characters is plain text in the conflict banner`(): Unit = runDesktopComposeUiTest {
        show(state { copy(applyOutcome = ApplyOutcome.Conflict("0003", 1, 1, "<i>q</i> exists with different settings", resource = "<i>q</i>")) })

        assertTextHas("migrations:conflictBanner", "<i>q</i> exists with different settings", "The existing \"<i>q</i>\" was not changed")
    }

    @Test
    fun `an apply that could not be recorded says so and can be dismissed`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state { copy(applyOutcome = ApplyOutcome.NotRecorded("0003", "The migration was applied but could not be recorded; running it again is safe")) }, calls)

        assertTextHas("migrations:notRecordedBanner", "The migration was applied but could not be recorded; running it again is safe")
        assertNotDrawn("migrations:failedBanner"); assertNotDrawn("migrations:conflictBanner")
        onNodeWithTag("migrations:dismissOutcomeBtn").performClick()
        assertEquals(listOf("dismissOutcome"), calls.events)
    }

    @Test
    fun `no outcome banner without an outcome, and the outcome is not written into the generic error banner`(): Unit = runDesktopComposeUiTest {
        show(state { copy(applyOutcome = ApplyOutcome.Failed("0003", 1, 1, "the broker did not answer")) })

        assertNotDrawn("migrations:errorBanner")
        assertNotDrawn("migrations:notRecordedBanner")
    }

    @Test
    fun `the generic error banner still shows a message such as the history file not moved`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(state { copy(errorMessage = "The history file could not be moved aside; it was not changed") }, calls)

        assertTextHas("migrations:errorBanner", "The history file could not be moved aside; it was not changed")
        onNodeWithTag("migrations:errorBanner").assertIsDisplayed()
    }

    // ---- 10. history unreadable and its dialog --------------------------------------------------------------------------------------------------

    private fun historyState() = state {
        copy(historyProblem = HistoryUnreadable(this@MigrationsStatesUiTest.historyFile, "The file is not valid JSON"), isApplyPendingEnabled = true, isApplySingleEnabled = true)
    }

    @Test
    fun `an unreadable history says so with its path and reason, and offers three ways out`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(historyState(), calls)

        assertTextHas("migrations:historyBanner", "The applied history cannot be read, so nothing can be applied", historyFile, "The file is not valid JSON")
        onNodeWithTag("migrations:historyRetryBtn").performClick()
        onNodeWithTag("migrations:historyShowFolderBtn").performClick()
        onNodeWithTag("migrations:emptyHistoryBtn").performClick()
        assertEquals("retry", calls.events[0])
        val shown = calls.events[1]
        assertTrue(shown == "showFolder:$historyFile" || shown == "showFolder:/Users/dana/.caerbannog", "Show folder must open the history file's folder: $shown")
        assertEquals("startEmpty", calls.events[2])
    }

    @Test
    fun `an unreadable history disables both apply buttons but the files stay listed`(): Unit = runDesktopComposeUiTest {
        show(historyState())

        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applySingleBtn").assertIsNotEnabled()
        onNodeWithTag("migrationCard:0003").assertIsDisplayed()
    }

    private val question = "Move migration-history.json to migration-history.json.unreadable-20261110-143005 and start with an empty history? The old file is kept."

    @Test
    fun `the empty-history dialog asks the question, Cancel changes nothing and Confirm calls once`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(historyState().copy(confirmation = question), calls)

        assertTextHas("migrations:emptyHistoryDialog", question)
        onNodeWithTag("migrations:emptyHistoryCancelBtn").performClick()
        assertEquals(listOf("cancelEmpty"), calls.events, "Cancel must never confirm")
    }

    @Test
    fun `Move file and start confirms`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(historyState().copy(confirmation = question), calls)

        onNodeWithTag("migrations:emptyHistoryConfirmBtn").performClick()

        assertEquals(listOf("confirmEmpty"), calls.events)
    }

    @Test
    fun `Escape closes the dialog like Cancel and never confirms`(): Unit = runDesktopComposeUiTest {
        val calls = Calls()
        show(historyState().copy(confirmation = question), calls)

        onNodeWithTag("migrations:emptyHistoryDialog").performKeyInput { pressKey(Key.Escape) }

        assertTrue("confirmEmpty" !in calls.events, "Esc must not move the file: ${calls.events}")
        assertEquals(listOf("cancelEmpty"), calls.events)
    }

    @Test
    fun `no dialog without a question`(): Unit = runDesktopComposeUiTest {
        show(historyState())

        assertNotDrawn("migrations:emptyHistoryDialog")
    }

    // ---- 11. a connection that cannot apply ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a connection without an executor says migrations are not offered and why`(): Unit = runDesktopComposeUiTest {
        val reason = "This broker runs RabbitMQ 3.x: it is a migration source only"
        show(state { copy(unavailableReason = reason) })
        assertTextHas("migrations:unavailableBanner", "Migrations are not offered on this connection", reason)
    }

    @Test
    fun `the other two fixed reasons are shown the same way`() {
        for (reason in listOf("The broker version could not be read, so migrations are not offered", "Connect to a broker first")) {
            runDesktopComposeUiTest {
                show(state { copy(unavailableReason = reason) })
                assertTextHas("migrations:unavailableBanner", "Migrations are not offered on this connection", reason)
            }
        }
    }

    @Test
    fun `an unavailable connection disables Apply but files, dry run, Import and Export keep working`(): Unit = runDesktopComposeUiTest {
        show(state { copy(unavailableReason = "Connect to a broker first", isApplyPendingEnabled = true, isApplySingleEnabled = true) })

        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:applySingleBtn").assertIsNotEnabled()
        onNodeWithTag("migrations:importBtn").assertIsEnabled()
        onNodeWithTag("migrations:exportBtn").assertIsEnabled()
        onNodeWithTag("migrationCard:0003").assertIsDisplayed()
        assertDrawn("dryRunAction:0")
    }

    @Test
    fun `the unavailable banner wins over the permission banner`(): Unit = runDesktopComposeUiTest {
        show(state { copy(unavailableReason = "Connect to a broker first", bannerMessage = "Apply is disabled: the permission check found that user cannot change topology on uat-eu.") })

        assertDrawn("migrations:unavailableBanner")
        assertNotDrawn("migrations:lockedBanner")
    }

    @Test
    fun `the permission banner is drawn when the connection can apply`(): Unit = runDesktopComposeUiTest {
        show(state { copy(bannerMessage = "Apply is disabled: the permission check found that user cannot change topology on uat-eu.") })

        assertTextHas("migrations:lockedBanner", "cannot change topology on uat-eu")
        assertNotDrawn("migrations:unavailableBanner")
    }

    @Test
    fun `no state banner for a screen without a problem`(): Unit = runDesktopComposeUiTest {
        show(state())

        for (tag in listOf(
            "migrations:unavailableBanner", "migrations:historyBanner", "migrations:folderUnavailableBanner", "migrations:readOnlyBanner",
            "migrations:failedBanner", "migrations:conflictBanner", "migrations:notRecordedBanner", "migrations:applyingBanner", "migrations:errorBanner",
        )) assertNotDrawn(tag)
    }

    @Test
    fun `the old interim banner strings are never drawn by the screen for the new states`(): Unit = runDesktopComposeUiTest {
        // The screen draws the structured state; `bannerMessage` is only the permission and level text.
        show(state { copy(historyProblem = HistoryUnreadable(historyFile, "The file is not valid JSON")) })

        assertNotDrawn("migrations:lockedBanner")
    }
}
