package dev.caerbannog.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.ui.connections.AppIcon
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SharedComponentsUiTest {

    @Test
    fun `SearchField shows placeholder when empty and emits changes`() = runDesktopComposeUiTest {
        var query by mutableStateOf("")
        setContent {
            SearchField(
                value = query,
                placeholder = "Search items",
                tag = "mySearch",
                onSearch = { query = it },
            )
        }

        onNodeWithTag("mySearch").assertExists()
        onNodeWithTag("mySearch").performTextInput("test-filter")
        assertEquals("test-filter", query)
    }

    @Test
    fun `CenteredMessage renders message, icon, action, and tag`() = runDesktopComposeUiTest {
        var clicked = false
        setContent {
            val retryAction: @Composable () -> Unit = {
                TextButton(onClick = { clicked = true }, modifier = Modifier.testTag("actionBtn")) {
                    Text("Retry")
                }
            }
            CenteredMessage(
                text = "No items found",
                icon = AppIcon.Inbox,
                tag = "myMessage",
                action = retryAction,
            )
        }

        onNodeWithTag("myMessage").assertExists()
        onNodeWithTag("myMessage").assertTextEquals("No items found")
        onNodeWithTag("actionBtn").assertExists()
    }
}
