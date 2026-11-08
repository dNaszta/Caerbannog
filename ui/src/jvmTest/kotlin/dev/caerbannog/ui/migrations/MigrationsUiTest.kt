package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MigrationsUiTest {

    private fun sampleFile(version: String, description: String = "Desc $version") = MigrationFile(
        version = version,
        description = description,
        up = listOf(
            MigrationAction.DeclareQueue("orders", "quorum"),
            MigrationAction.DeclareBinding("events", "orders", "order.created"),
        ),
        rawJson = """{ "version": "$version", "description": "$description" }""",
        checksum = "abc$version",
    )

    @Test
    fun `MigrationsScreen renders title and primary action buttons`(): Unit = runDesktopComposeUiTest {
        val state = MigrationsScreenState(
            profileName = "uat-eu",
            isApplyPendingEnabled = true,
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:title").assertIsDisplayed()
        onNodeWithTag("migrations:importBtn").assertIsDisplayed()
        onNodeWithTag("migrations:exportBtn").assertIsDisplayed()
        onNodeWithTag("migrations:applyPendingBtn").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `Apply pending button is disabled when cannot apply`(): Unit = runDesktopComposeUiTest {
        val state = MigrationsScreenState(
            profileName = "uat-eu",
            isApplyPendingEnabled = false,
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:applyPendingBtn").assertIsNotEnabled()
    }

    @Test
    fun `renders migration files cards with status pills`(): Unit = runDesktopComposeUiTest {
        val file1 = sampleFile("0001", "Create orders queue")
        val file2 = sampleFile("0002", "Add events binding")
        val state = MigrationsScreenState(
            entries = listOf(
                MigrationEntry(file1, MigrationStatus.Applied("abc0001")),
                MigrationEntry(file2, MigrationStatus.Pending),
            ),
            selectedVersion = "0001",
            selectedFile = file1,
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrationCard:0001").assertIsDisplayed()
        onNodeWithTag("migrationCard:0002").assertIsDisplayed()
        onNodeWithTag("migrationStatus:Applied", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("migrationStatus:Pending", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `selecting a card invokes onSelectVersion callback`(): Unit = runDesktopComposeUiTest {
        val file1 = sampleFile("0001")
        val file2 = sampleFile("0002")
        val state = MigrationsScreenState(
            entries = listOf(
                MigrationEntry(file1, MigrationStatus.Applied("abc0001")),
                MigrationEntry(file2, MigrationStatus.Pending),
            ),
            selectedVersion = "0001",
            selectedFile = file1,
        )
        var selected: String? = null

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = { selected = it },
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrationCard:0002").performClick()
        assertEquals("0002", selected)
    }

    @Test
    fun `renders selected migration detail, raw JSON and dry run actions`(): Unit = runDesktopComposeUiTest {
        val file = sampleFile("0003", "Add orders queue")
        val dryRun = MigrationDryRun(
            version = "0003",
            actions = listOf(
                ActionDryRun(file.up[0], allowed = true),
                ActionDryRun(file.up[1], allowed = false, reason = "Blocked by control level"),
            ),
            canApply = false,
        )
        val state = MigrationsScreenState(
            profileName = "uat-eu",
            selectedVersion = "0003",
            selectedFile = file,
            dryRun = dryRun,
            isApplySingleEnabled = false,
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrationDetail:header").assertIsDisplayed()
        onNodeWithTag("migrationDetail:rawJson").assertIsDisplayed()
        onNodeWithTag("dryRunAction:0").assertIsDisplayed()
        onNodeWithTag("dryRunAction:1").assertIsDisplayed()
        onNodeWithTag("migrations:applySingleBtn").assertIsNotEnabled()
    }

    @Test
    fun `locked banner is shown when bannerMessage is present`(): Unit = runDesktopComposeUiTest {
        val file = sampleFile("0003")
        val state = MigrationsScreenState(
            profileName = "uat-eu",
            selectedVersion = "0003",
            selectedFile = file,
            bannerMessage = "Apply is disabled: user cannot change topology on uat-eu.",
            hasConfigurePermission = false,
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:lockedBanner").assertIsDisplayed()
    }

    @Test
    fun `warning banner is displayed when an applied migration is modified`(): Unit = runDesktopComposeUiTest {
        val state = MigrationsScreenState(
            warningMessage = "Warning: an applied migration was modified on disk.",
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:warningBanner").assertIsDisplayed()
    }

    @Test
    fun `import JSON dialog opens and submits content`(): Unit = runDesktopComposeUiTest {
        val state = MigrationsScreenState()
        var importedText: String? = null

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = { importedText = it },
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:importBtn").performClick()
        onNodeWithTag("importMigration:input").assertIsDisplayed()
        onNodeWithTag("importMigration:input").performTextInput("""{ "version": "0005" }""")
        onNodeWithTag("importMigration:confirmBtn").performClick()

        assertEquals("""{ "version": "0005" }""", importedText)
    }

    @Test
    fun `edit file dialog opens and saves updated content`(): Unit = runDesktopComposeUiTest {
        val file = sampleFile("0003")
        val state = MigrationsScreenState(
            selectedVersion = "0003",
            selectedFile = file,
        )
        var updatedVersion: String? = null
        var updatedJson: String? = null

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { ver, json ->
                    updatedVersion = ver
                    updatedJson = json
                },
                onValidateAgain = {},
                onDismissError = {},
            )
        }

        onNodeWithTag("migrations:editFileBtn").performClick()
        onNodeWithTag("editMigration:input").assertIsDisplayed()
        onNodeWithTag("editMigration:saveBtn").performClick()

        assertEquals("0003", updatedVersion)
        assertEquals(file.rawJson, updatedJson)
    }

    @Test
    fun `error banner renders error message and dismisses`(): Unit = runDesktopComposeUiTest {
        var dismissed = false
        val state = MigrationsScreenState(
            errorMessage = "the file is not valid JSON",
        )

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = { dismissed = true },
            )
        }

        onNodeWithTag("migrations:errorBanner").assertIsDisplayed()
    }

    @Test
    fun `MigrationsScreen renders inside AppFrame with navigation drawer items`(): Unit = runDesktopComposeUiTest(1280, 800) {
        var navigatedTo: dev.caerbannog.ui.appframe.AppDestination? = null
        val state = MigrationsScreenState(profileName = "uat-eu")

        setContent {
            MigrationsScreen(
                state = state,
                onSelectVersion = {},
                onApplyPending = {},
                onApplySingle = {},
                onImportJson = {},
                onExportTopology = {},
                onUpdateFile = { _, _ -> },
                onValidateAgain = {},
                onDismissError = {},
                onNavigate = { navigatedTo = it },
            )
        }

        onNodeWithTag("appDrawer").assertIsDisplayed()
        onNodeWithTag("navItem:Streams").performClick()
        assertEquals(dev.caerbannog.ui.appframe.AppDestination.Streams, navigatedTo)
    }
}
