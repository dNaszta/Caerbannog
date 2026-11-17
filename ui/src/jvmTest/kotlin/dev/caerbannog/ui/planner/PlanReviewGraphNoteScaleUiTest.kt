package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.MqttDestinationKind
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-07b step 4 (test developer): the note box has a fixed height, so a larger font scale or a denser screen must not cut its sentence. For each
 * density (1, 1.5, 2) and font scale (1, 1.3, 1.5) the sentence's text layout reports no height overflow, and the sentence is still drawn in full.
 */
@OptIn(ExperimentalTestApi::class)
class PlanReviewGraphNoteScaleUiTest {
    private fun SemanticsNode.layouts(): List<TextLayoutResult> {
        val out = mutableListOf<TextLayoutResult>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    @Test
    fun `the sentence of the note is not cut at a larger density or font scale`() {
        val problems = mutableListOf<String>()
        for (density in listOf(1f, 1.5f, 2f)) for (fontScale in listOf(1f, 1.3f, 1.5f)) runDesktopComposeUiTest(1600, 1000) {
            val r = UiRig(reader = FakeKnownTopologyReader(KnownTopology.None)) {
                stream("device-commands", withResponse = true)
                mqttRoute("devices/+/answer", "device-commands-dln", MqttDestinationKind.Stream)
            }
            setContent { CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) { PlannerOver(r) } }
            waitForIdle()
            val layouts = node("planGraphNote:0").fetchSemanticsNode().layouts()
            if (layouts.isEmpty()) problems += "density $density, font $fontScale: no text layout"
            layouts.forEach { l ->
                if (l.didOverflowHeight) problems += "density $density, font $fontScale: the sentence overflows the box (${l.lineCount} lines)"
            }
            r.close()
        }
        assertEquals(emptyList(), problems)
    }
}
