package dev.caerbannog.ui.planner

import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.StepForm

/**
 * Events out of the planner screen (state in, events out). Each one is asynchronous in the view model: read the result from the state.
 * No event reaches the broker: the planner changes a plan, a draft and, on [saveAsMigration], one new migration file. A later addition needs a
 * default body so that existing implementations keep compiling.
 */
interface PlannerEvents {
    fun openWizard()
    fun chooseKind(kind: String)
    fun updateForm(form: StepForm)
    fun next()
    fun back()
    fun cancelWizard()
    fun addToPlan()

    fun selectStep(id: Int)
    fun editStep(id: Int)
    fun removeStep(id: Int)
    fun moveStepUp(id: Int)
    fun moveStepDown(id: Int)

    fun setDescription(text: String)
    fun setVirtualHost(vhost: String)
    fun setNamingStyle(style: NamingStyle)

    /** The Graph and JSON switch of the review card: a choice of view, nothing in the plan changes. */
    fun selectReviewView(view: ReviewView)

    fun chooseFolder()
    fun saveAsMigration()
    fun dismiss()
    fun startNewPlan()
    fun confirm()
    fun cancelQuestion()
    fun retryDraft()

    /** Reads the broker (only reads) and shows a result per step; the plan, the draft and Save are not touched. */
    fun check()

    /** The screen is left: a description still waiting for its quiet time is written now. */
    fun flush()

    /** Shows the folder of [path] in the file manager (the unreadable-draft banner). A convenience: it never fails. */
    fun showFolder(path: String) = Unit
}
