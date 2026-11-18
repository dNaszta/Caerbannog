package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-12 step 2: the two new questions, word for word (brief and mockup 13). RED by compilation until `PlannerQuestion.DiscardChanges` and
 * `PlannerQuestion.CopyOverDraft` exist. `CopyOverDraft(version)` is asked on the Migrations screen with the existing draft dialog
 * (`DismissDraftDialog` takes a `PlannerQuestion`), before the steps of a file replace a draft that has steps.
 */
class PlannerQuestionEditFileTest {
    @Test
    fun `leaving a file with changes asks Discard the changes to the version`() {
        val q = PlannerQuestion.DiscardChanges("0005")
        assertEquals("Discard the changes to 0005?", q.text)
        assertEquals("Discard changes", q.confirmLabel)
        assertEquals("Keep editing", q.cancelLabel)
    }

    @Test
    fun `copying over a draft with steps asks first`() {
        val q = PlannerQuestion.CopyOverDraft("0002")
        assertEquals("Dismiss the current plan and copy 0002 into a new plan?", q.text)
        assertEquals("Dismiss and copy", q.confirmLabel)
        assertEquals("Keep the plan", q.cancelLabel)
    }

    @Test
    fun `a version that may not be echoed is left out of the sentence`() {
        // versions are digits in practice; a hand-written file name is not trusted (the echo rule)
        assertEquals("Discard the changes to this file?", PlannerQuestion.DiscardChanges("a \"b\"").text)
        assertEquals("Dismiss the current plan and copy this file into a new plan?", PlannerQuestion.CopyOverDraft("a \"b\"").text)
    }
}
