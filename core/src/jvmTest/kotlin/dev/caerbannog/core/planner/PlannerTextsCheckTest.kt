package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-06 step 2: the fixed texts of the check, as literals (Q14 decided the wording; a later change is a change in `PlannerTexts` only, and then in
 * this file). API fixed here:
 *  - `PlannerTexts.resultLabel(finding: StepFinding): String`, `BLOCKED_PILL`, `CHECK_STALE`, `CHECK_AGAIN`, `CHECK_CAVEAT`;
 *  - `PlannerTexts.checkLabel(connection: String?)`, `checkProgress(step, total, connection)`;
 *  - `PlannerTexts.checkSummary(connection: String, time: String, results: List<StepCheck>): String` where [time] is already formatted by the
 *    screen ("14:02": `core` has no time zone).
 * `PlannerTexts.CHECK_LATER` is deleted.
 */
class PlannerTextsCheckTest {
    private fun step(id: Int, finding: StepFinding, blocked: String? = null) = StepCheck(id, finding, blocked)

    @Test
    fun `every result word is the one of the spec table`() {
        assertEquals("Will be created", PlannerTexts.resultLabel(StepFinding.WillBeCreated))
        assertEquals("Already there", PlannerTexts.resultLabel(StepFinding.AlreadyThere))
        assertEquals("Exists with different settings", PlannerTexts.resultLabel(StepFinding.Different))
        assertEquals("Needs something that is missing", PlannerTexts.resultLabel(StepFinding.Missing("The exchange e is not on the broker and is not declared earlier in this plan.")))
        assertEquals("Could not be checked", PlannerTexts.resultLabel(StepFinding.NotChecked("The broker did not answer.")))
        assertEquals("Will be deleted", PlannerTexts.resultLabel(StepFinding.WillBeDeleted))
        assertEquals("Already gone", PlannerTexts.resultLabel(StepFinding.AlreadyGone))
        assertEquals("Blocked", PlannerTexts.BLOCKED_PILL)
    }

    @Test
    fun `the result word never depends on the reason text it carries`() {
        assertEquals(
            PlannerTexts.resultLabel(StepFinding.Missing("a")),
            PlannerTexts.resultLabel(StepFinding.Missing("something else entirely")),
        )
        assertEquals(PlannerTexts.resultLabel(StepFinding.NotChecked("a")), PlannerTexts.resultLabel(StepFinding.NotChecked("b")))
    }

    @Test
    fun `the stale line and its button are as in the mockup`() {
        assertEquals("The plan changed since the check. Check again.", PlannerTexts.CHECK_STALE)
        assertEquals("Check again", PlannerTexts.CHECK_AGAIN)
    }

    @Test
    fun `the button says Check, or names the connection`() {
        assertEquals("Check", PlannerTexts.CHECK_LABEL)
        assertEquals("Check", PlannerTexts.checkLabel(null))
        assertEquals("Check against uat-eu", PlannerTexts.checkLabel("uat-eu"))
        assertEquals("Check against prod-eu", PlannerTexts.checkLabel("prod-eu"))
    }

    @Test
    fun `progress says which step of how many against which connection`() {
        assertTrue(PlannerTexts.checkProgress(4, 10, "uat-eu").startsWith("Checking step 4 of 10 against uat-eu"), PlannerTexts.checkProgress(4, 10, "uat-eu"))
        assertTrue(PlannerTexts.checkProgress(1, 1, "uat-eu").startsWith("Checking step 1 of 1 against uat-eu"))
    }

    // ---- the summary ----

    private val head = "Checked against uat-eu at 14:02: "

    private fun counts(results: List<StepCheck>): String {
        val text = PlannerTexts.checkSummary("uat-eu", "14:02", results)
        assertTrue(text.startsWith(head), "the summary starts with the connection and the time: \"$text\"")
        return text.removePrefix(head).trimEnd('.')
    }

    @Test
    fun `the summary of the scenario counts the results in the order of the spec`() {
        val results = (1..5).map { step(it, StepFinding.WillBeCreated) } + step(6, StepFinding.AlreadyThere) + step(7, StepFinding.Different)
        assertEquals("5 will be created, 1 already there, 1 exists with different settings", counts(results))
    }

    @Test
    fun `the summary of the mockup, every count once, in its order and blocked last`() {
        val results = (1..5).map { step(it, StepFinding.WillBeCreated) } + listOf(
            step(6, StepFinding.AlreadyThere),
            step(7, StepFinding.Different),
            step(8, StepFinding.Missing("m")),
            step(9, StepFinding.NotChecked("n"), blocked = "Destructive actions require Full control level"),
        )
        assertEquals(
            "5 will be created, 1 already there, 1 exists with different settings, 1 needs something that is missing, 1 could not be checked, 1 blocked",
            counts(results),
        )
    }

    @Test
    fun `a count of zero is left out, and one result is said as one`() {
        assertEquals("1 will be created", counts(listOf(step(1, StepFinding.WillBeCreated))))
        assertEquals("1 already there", counts(listOf(step(1, StepFinding.AlreadyThere))))
        assertEquals("2 already there", counts(listOf(step(1, StepFinding.AlreadyThere), step(2, StepFinding.AlreadyThere))))
        assertFalse(counts(listOf(step(1, StepFinding.WillBeCreated))).contains("0 "), "a zero count is drawn")
    }

    @Test
    fun `a blocked step counts under its result and as blocked`() {
        val text = counts(listOf(step(1, StepFinding.WillBeCreated, blocked = "r"), step(2, StepFinding.WillBeCreated)))
        assertEquals("2 will be created, 1 blocked", text)
    }

    @Test
    fun `a delete step is counted in its own words`() {
        val text = counts(listOf(step(1, StepFinding.WillBeDeleted), step(2, StepFinding.AlreadyGone)))
        assertTrue("1 will be deleted" in text, text)
        assertTrue("1 already gone" in text, text)
    }

    @Test
    fun `the summary names the time it was given and the connection`() {
        val text = PlannerTexts.checkSummary("prod-eu", "09:05", listOf(step(1, StepFinding.AlreadyThere)))
        assertTrue(text.startsWith("Checked against prod-eu at 09:05: "), text)
    }

    @Test
    fun `the summary promises nothing, and the caveat line says the results describe the broker as it is now`() {
        val caveat = PlannerTexts.CHECK_CAVEAT
        assertTrue(caveat.isNotBlank())
        val lower = caveat.lowercase()
        assertTrue("now" in lower && "promise" in lower, "the caveat must say that the results describe the broker now and are not a promise: \"$caveat\"")
        val summary = PlannerTexts.checkSummary("uat-eu", "14:02", listOf(step(1, StepFinding.WillBeCreated)))
        assertFalse(summary.lowercase().contains("promise") || summary.lowercase().contains("guarantee"))
        listOf(PlannerTexts.CHECK_STALE, caveat, summary).forEach {
            assertTrue(it.none { c -> c in "<>`*" } && "{" !in it && "%s" !in it && "null" !in it, "markup or unfilled placeholder in \"$it\"")
        }
    }

    @Test
    fun `the old placeholder text of the disabled button is gone`() {
        assertTrue(PlannerTexts::class.java.declaredFields.none { it.name == "CHECK_LATER" }, "PlannerTexts.CHECK_LATER must be deleted")
    }
}
