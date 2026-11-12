package dev.caerbannog.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.caerbannog.core.help.HowTo

/** The "How to" text button of a screen's top line (tag `howto-<id>`); it opens [HowToDialog]. */
@Composable
fun HowToLink(howTo: HowTo, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    // Compact (24 dp, like the tips): the link must not make the top line taller than its text.
    TextButton(
        onClick = { open = true }, modifier = modifier.height(24.dp).testTag("howto-${howTo.id}"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) { Text("How to", maxLines = 1) }
    // The link may sit in a right-to-left row (TopLineWithHowTo); the dialog must not be mirrored.
    if (open) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { HowToDialog(howTo) { open = false } }
}

/**
 * The top line of a screen: [content] (weighted) and the "How to" [link] (a `HowToLink`) at the right. The link is composed FIRST and laid out at the right (a right-to-left
 * row), so it is the first Tab stop of the screen (M8-14b) while still sitting top-right (owner decision D4).
 */
@Composable
fun TopLineWithHowTo(link: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            link()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
            }
        }
    }
}

/** A modal list: the title, "You need", the numbered steps and "If it goes wrong". Tags: `howto-dialog-<id>`, `howto-step-<n>` (1-based), `howto-close`. */
@Composable
fun HowToDialog(howTo: HowTo, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(
                Modifier.width(560.dp).padding(24.dp).verticalScroll(rememberScrollState()).testTag("howto-dialog-${howTo.id}"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(howTo.title, style = MaterialTheme.typography.titleLarge)
                Section("You need")
                howTo.needs.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodyMedium) }
                Section("Steps")
                howTo.steps.forEachIndexed { index, step ->
                    Row(Modifier.testTag("howto-step-${index + 1}")) { Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                }
                Section("If it goes wrong")
                howTo.ifWrong.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = onClose, modifier = Modifier.testTag("howto-close")) { Text("Close") }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
