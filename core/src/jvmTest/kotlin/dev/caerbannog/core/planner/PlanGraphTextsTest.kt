package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-04: the fixed words of the review graph are `core` constants (the mockup's wording, Planner.dc.html "8. Review graph: other cases"). */
class PlanGraphTextsTest {
    @Test
    fun `the too large notice`() {
        assertEquals("This plan is too large to draw", PlanGraphNotice.TooLarge.title)
        assertEquals("The graph shows at most 60 items. The step list is the review.", PlanGraphNotice.TooLarge.detail)
    }

    @Test
    fun `only the plan, with the two causes`() {
        val notConnected = PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected)
        val notRead = PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead)
        assertEquals("Only the plan is drawn", notConnected.title)
        assertEquals("Only the plan is drawn", notRead.title)
        assertEquals("Not connected: what already exists on a broker is not shown.", notConnected.detail)
        assertEquals("The broker could not be read: what already exists is not shown.", notRead.detail)
    }

    @Test
    fun `the legend names the marks`() {
        val legend = PlannerTexts.GRAPH_LEGEND
        assertEquals("Thick outline, \"new\": added by this plan", legend[0])
        assertEquals("Thin outline: already on the broker", legend[1])
        assertTrue(legend[2].contains("Dotted outline") && legend[2].contains("not in this plan"), legend[2])
        assertEquals(3, legend.size, "the dashed and dotted links come with M9-07 and M9-09")
    }

    @Test
    fun `no graph text holds markup or an unfilled placeholder`() {
        val texts = listOf(PlanGraphNotice.TooLarge.title, PlanGraphNotice.TooLarge.detail) +
            OnlyThePlanCause.entries.flatMap { listOf(PlanGraphNotice.OnlyThePlan(it).title, PlanGraphNotice.OnlyThePlan(it).detail) } + PlannerTexts.GRAPH_LEGEND
        for (t in texts) {
            assertTrue(t.isNotBlank())
            assertTrue(t.none { it in "<>`*" }, "markup in \"$t\"")
            assertTrue("{" !in t && "}" !in t && "%s" !in t && "null" !in t, "placeholder in \"$t\"")
        }
    }
}
