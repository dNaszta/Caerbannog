package dev.caerbannog.ui.migrations

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationsScreenState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-10 step 2: the action rows of the Migrations screen use the words of the planner (`MqttRoutes.words`, kind word from the other actions of the same
 * migration; the Migrations screen knows no broker topology, so a destination the file does not declare has no kind). Only the words: the button
 * "Send a test message" is M9-10b. Tags are the existing `dryRunAction:<i>` and `actionDetail:<i>`.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsMqttRouteUiTest {
    private fun state(vararg actions: MigrationAction): MigrationsScreenState {
        val file = MigrationFile("0005", "Routes", actions.toList(), """{ "version": "0005" }""", "abc0005")
        return MigrationsScreenState(
            profileName = "uat-eu", selectedVersion = "0005", selectedFile = file,
            dryRun = MigrationDryRun("0005", actions.map { ActionDryRun(it, allowed = true) }, canApply = true),
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: MigrationsScreenState) = setContent {
        MigrationsScreen(
            state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String): String {
        fun all(x: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            x.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + x.children.flatMap { all(it) }
        return all(onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()).joinToString(" ")
    }

    @Test
    fun `a route row says MQTT route with its filter and the kind of a destination the file declares`() = runDesktopComposeUiTest(1280, 1000) {
        show(
            state(
                MigrationAction.DeclareStream("telemetry"),
                MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.*.temp"),
                MigrationAction.DeclareQueue("alarms", "quorum"),
                MigrationAction.DeclareBinding("amq.topic", "alarms", "alarms.#"),
            ),
        )
        assertTrue(textOf("dryRunAction:0").contains("Declare stream telemetry"), textOf("dryRunAction:0"))
        assertTrue(textOf("dryRunAction:1").contains("MQTT route sensors/+/temp to stream telemetry"), textOf("dryRunAction:1"))
        assertTrue(textOf("dryRunAction:3").contains("MQTT route alarms/# to queue alarms"), textOf("dryRunAction:3"))
        assertEquals("Binding on amq.topic with the key sensors.*.temp", textOf("actionDetail:1"))
        assertTrue(!textOf("dryRunAction:1").contains("Bind amq.topic"), textOf("dryRunAction:1"))
    }

    @Test
    fun `a route to a destination the file does not declare is named without a kind`() = runDesktopComposeUiTest(1280, 800) {
        show(state(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#")))
        assertTrue(textOf("dryRunAction:0").contains("MQTT route sensors/# to telemetry"), textOf("dryRunAction:0"))
        assertTrue(!textOf("dryRunAction:0").contains("to stream") && !textOf("dryRunAction:0").contains("to queue"), textOf("dryRunAction:0"))
    }

    @Test
    fun `a binding on amq topic that is not a route keeps the binding words and gets no extra line`() = runDesktopComposeUiTest(1280, 800) {
        show(
            state(
                MigrationAction.DeclareBinding("amq.topic", "alarms", "sensors..temp"),
                MigrationAction.DeclareBinding("events", "alarms", "sensors.#"),
            ),
        )
        assertTrue(textOf("dryRunAction:0").contains("Bind amq.topic to alarms with sensors..temp"), textOf("dryRunAction:0"))
        assertTrue(textOf("dryRunAction:1").contains("Bind events to alarms with sensors.#"), textOf("dryRunAction:1"))
        for (i in 0..1) assertTrue(!textOf("dryRunAction:$i").contains("MQTT route"), textOf("dryRunAction:$i"))
    }

    @Test
    fun `a route row has no test message button yet`() = runDesktopComposeUiTest(1280, 800) {
        show(state(MigrationAction.DeclareBinding("amq.topic", "telemetry", "sensors.#")))
        assertTrue(!textOf("dryRunAction:0").contains("Send a test message"), "that button is M9-10b")
    }
}
