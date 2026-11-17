package dev.caerbannog.ui.planner

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.Test

/**
 * M9-09 step 2 (like `PolicyFormScaleUiTest`): the dead-lettering form at font scale 1.5 and on short windows, in its tallest state (At least once with the tick, a queue that has
 * its own dead-letter exchange, so every note is drawn). The fields, the options, the tick and the wizard buttons must stay reachable (scrolled to, or fixed on screen). RED until built.
 */
@OptIn(ExperimentalTestApi::class)
class DeadLetterFormScaleUiTest {
    private val known = KnownTopology(queues = setOf("orders"), queueDetails = mapOf("orders" to KnownQueue("quorum", null, ownDeadLetterExchange = "legacy-dlx")))

    private fun DesktopComposeUiTest.exercise(fontScale: Float) {
        val r = UiRig(reader = FakeKnownTopologyReader(known))
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { PlannerOver(r) } }
        waitForIdle()
        node("planner:addStepBtn").performClick()
        node("planWizard:kind:Dead-lettering for a queue").performScrollTo().performClick()
        listOf("planWizard:cancel", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        node("planWizard:next").performClick()
        node("planWizard:deadLetter:queue").performScrollTo().performTextReplacement("orders")
        node("planWizard:deadLetter:strategyAtLeastOnce").performScrollTo().performClick()
        node("planWizard:deadLetter:refusedTick").performScrollTo().assertIsDisplayed()
        listOf("targetQueue", "targetStream", "exchange", "targetName", "strategyAtMostOnce", "strategyAtLeastOnce", "refusedTick").forEach {
            node("planWizard:deadLetter:$it").performScrollTo().assertIsDisplayed()
        }
        node("planWizard:deadLetter:refusedTick").performScrollTo().performClick()
        listOf("planWizard:cancel", "planWizard:back", "planWizard:next").forEach { node(it).assertIsDisplayed() }
        r.close()
    }

    @Test
    fun `at font scale 1_5 the dead-lettering form and the wizard buttons are reachable on 1280 x 800`() = runDesktopComposeUiTest(1280, 800) { exercise(1.5f) }

    @Test
    fun `on a 600 high window at font scale 1_5 the form scrolls and the buttons stay on screen`() = runDesktopComposeUiTest(1280, 600) { exercise(1.5f) }

    @Test
    fun `on a short window at normal scale the form scrolls and the buttons stay on screen`() = runDesktopComposeUiTest(1280, 420) { exercise(1f) }
}
