package dev.caerbannog.ui.migrate3x

import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.ReviewItem

/**
 * M8-06b STUB (test developer): the events of the review, generate and save part of the screen, one method per view model event of
 * `docs/tasks/M8-06b-review-generate-save.md` "View model API". The Compose tests pass an implementation that delegates to a real view
 * model. The developer may reshape this (for example single lambdas) but then updates `MigrateFromThreeTestKit.kt` (`ReviewEvents` use) with it.
 * Every method does nothing by default, so the 06a screen and its tests are not affected.
 */
interface ReviewEvents {
    fun openItem(item: ReviewItem) = Unit
    fun closeItem() = Unit
    fun chooseAlternative(findingId: String, index: Int) = Unit
    fun setTick(ack: Acknowledgement, on: Boolean) = Unit
    fun accept() = Unit
    fun skip() = Unit
    fun unskip(item: ReviewItem) = Unit
    fun startReplace() = Unit
    fun setReplaceText(text: String) = Unit
    fun submitReplace(text: String) = Unit
    fun backToProposal() = Unit
    fun showNextUndecided() = Unit
    fun generate() = Unit
    fun generateAgain() = Unit
    fun cancelGenerateAgain() = Unit
    fun chooseFolder() = Unit
    fun retrySave() = Unit
    fun exportFiles() = Unit
    fun dismissResult() = Unit

    companion object {
        val None: ReviewEvents = object : ReviewEvents {}
    }
}
