package dev.caerbannog.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.jupiter.api.Test

/**
 * M1-01 skeleton spike, Acceptance item 6: "One Compose Multiplatform UI test renders a composable
 * and finds it by text."
 *
 * Renders the placeholder `App()` composable (desktopApp's empty-window content, per the task
 * brief's Design notes: "a single `@Composable fun App()` with no view model") and asserts a text
 * node exists.
 *
 * Expected to fail to compile right now, for two independent reasons:
 *   1. `ui/build.gradle.kts` applies no Compose Multiplatform plugin and declares no
 *      `org.jetbrains.compose.ui:ui-test` dependency yet, so the Compose UI test API imports above
 *      do not resolve.
 *   2. `App()` itself does not exist yet in `ui`'s commonMain - it is the one piece of real
 *      production code this spike needs, and the senior developer writes it to make this test
 *      compile and pass.
 */
class AppUiTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `App renders placeholder text`() = runDesktopComposeUiTest {
        setContent { App() }

        onNodeWithText("Caerbannog", substring = true).assertExists()
    }
}
