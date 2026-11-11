package dev.caerbannog.ui.migrate3x

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M8-06e item 2: the virtual host prompt keeps what the user typed when the view model refuses the name and shows the prompt again with an error. The text
 * is owned by the composable and keyed on the file label only. Green on arrival: it characterizes the keying `remember(state.fileLabel)`. It can fail:
 * with `remember(state)` or `remember(state.error)` in `VirtualHostPrompt` the refusal (a new state) empties the field and the first test fails.
 */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeVirtualHostTextUiTest {
    private fun DesktopComposeUiTest.typed(): String =
        onNodeWithTag("migrate3x-vhost-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `a refused name leaves the typed text in the field and shows the error`() = runDesktopComposeUiTest(1280, 800) {
        val state = mutableStateOf<MigrateFromThreeState>(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null))
        showWith({ state.value })
        onNodeWithTag("migrate3x-vhost-field").performTextInput("a\tb")
        assertEquals("a\tb", typed(), "the premise: the text was typed")
        assertNotDrawn("migrate3x-vhost-error")

        state.value = MigrateFromThreeState.NeedsVirtualHost("vhost.json", "The virtual host name cannot contain a tab")
        waitForIdle()
        assertEquals("a\tb", typed(), "the refusal emptied the field: the prompt must be keyed on the file label only, never on the state or its error")
        assertEquals("The virtual host name cannot contain a tab", textOf("migrate3x-vhost-error").trim())
    }

    @Test
    fun `a second refusal and the error going away also keep the text`() = runDesktopComposeUiTest(1280, 800) {
        val state = mutableStateOf<MigrateFromThreeState>(MigrateFromThreeState.NeedsVirtualHost("vhost.json", "first refusal"))
        showWith({ state.value })
        onNodeWithTag("migrate3x-vhost-field").performTextInput("  ")
        state.value = MigrateFromThreeState.NeedsVirtualHost("vhost.json", "second refusal"); waitForIdle()
        assertEquals("  ", typed())
        state.value = MigrateFromThreeState.NeedsVirtualHost("vhost.json", null); waitForIdle()
        assertEquals("  ", typed())
        assertNotDrawn("migrate3x-vhost-error")
    }

    @Test
    fun `another file starts with an empty field`() = runDesktopComposeUiTest(1280, 800) {
        val state = mutableStateOf<MigrateFromThreeState>(MigrateFromThreeState.NeedsVirtualHost("vhost.json", null))
        showWith({ state.value })
        onNodeWithTag("migrate3x-vhost-field").performTextInput("orders")
        state.value = MigrateFromThreeState.NeedsVirtualHost("other.json", null); waitForIdle()
        assertEquals("", typed(), "a different file label must give an empty field")
    }
}
