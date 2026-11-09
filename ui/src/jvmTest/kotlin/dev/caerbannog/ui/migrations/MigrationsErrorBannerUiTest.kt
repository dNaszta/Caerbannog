package dev.caerbannog.ui.migrations

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** M8-04 round 1 re-verify: the banner merges its text for screen readers (`mergeDescendants`), and Dismiss is still findable and clickable. */
@OptIn(ExperimentalTestApi::class)
class MigrationsErrorBannerUiTest {

    @Test
    fun `the banner exposes the message and the Dismiss button still works`(): Unit = runDesktopComposeUiTest {
        var dismissed = false
        setContent {
            MigrationsScreen(
                state = MigrationsScreenState(errorMessage = "the value of \"name\" has the wrong type"),
                onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
                onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = { dismissed = true },
            )
        }
        onNodeWithTag("migrations:errorBanner").assertIsDisplayed().assertTextContains("has the wrong type", substring = true)
        onNodeWithText("Dismiss").assertIsDisplayed().assertHasClickAction()
        onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed, "Dismiss must still call onDismissError")
    }
}
