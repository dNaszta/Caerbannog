package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRunRules
import dev.caerbannog.core.migrations.echoableName
import dev.caerbannog.core.topology.BindingDestinationType

/** What the check says about one step. The core carries no wording beyond the fixed sentences; the screen (M9-06) words the labels. */
sealed interface StepFinding {
    data object WillBeCreated : StepFinding
    data object AlreadyThere : StepFinding
    data object Different : StepFinding

    /** A binding whose exchange or destination is neither on the broker nor declared by an earlier step; [what] is the fixed sentence. */
    data class Missing(val what: String) : StepFinding

    /** The step was not compared; [reason] is a fixed text. */
    data class NotChecked(val reason: String) : StepFinding

    data object WillBeDeleted : StepFinding
    data object AlreadyGone : StepFinding
}

/** [blocked] is the dry-run reason of that action (null when nothing blocks it); [notes] are fixed sentences about the finding. */
data class StepCheck(val stepId: Int, val finding: StepFinding, val blocked: String?, val notes: List<String> = emptyList()) {
    /** The lines under the step: why it is blocked, what the finding says in a sentence, then the notes. Fixed texts only. */
    val reasons: List<String>
        get() = listOfNotNull(
            blocked,
            when (finding) {
                is StepFinding.Missing -> finding.what
                is StepFinding.NotChecked -> finding.reason
                else -> null
            },
        ) + notes

    /** True when the lines are a warning about the step (it would stop, or cannot run), not just information. */
    val isProblem: Boolean get() = blocked != null || finding == StepFinding.Different || finding is StepFinding.Missing
}

sealed interface PlanCheckResult {
    /** Nothing was sent; [reason] is the connection's fixed reason. */
    data class Refused(val reason: String) : PlanCheckResult
    data class Checked(val steps: List<StepCheck>) : PlanCheckResult
}

/**
 * The check of a plan against the broker, from reads alone. It decides nothing: whether a plan can be saved or applied does not depend on it
 * (the apply keeps its own read-back and conflict rule). It reads one step at a time through the read-only [DeclareCheck] and is cancellable.
 */
object PlanCheck {
    const val DIFFERENT_NOTE: String = "Applying this step would stop here; nothing on the broker would be overwritten."
    const val NO_CHECK: String = "This connection cannot be checked."
    const val OTHER_VHOST: String = "A step for another virtual host is not read."
    const val NOT_READ: String = "This kind of step is not checked."

    /** [onStep] is told the 1-based position and the total before each step is read. */
    suspend fun run(
        plan: TopologyPlan,
        context: PlannerContext,
        check: DeclareCheck?,
        onStep: (position: Int, total: Int) -> Unit = { _, _ -> },
    ): PlanCheckResult {
        context.unavailableReason?.let { return PlanCheckResult.Refused(it) }
        if (context.connectionName == null) return PlanCheckResult.Refused(PlannerTexts.NOT_CONNECTED)
        if (check == null) return PlanCheckResult.Refused(NO_CHECK)

        val verdict = MigrationDryRunRules.evaluate(
            plan.steps.map { it.action }, context.hasConfigurePermission, context.controlLevel, context.vhost,
        )
        val declaredBefore = mutableSetOf<Pair<Kind, String>>()
        val total = plan.steps.size
        val results = plan.steps.mapIndexed { index, step ->
            onStep(index + 1, total)
            val blocked = verdict.actions[index].reason
            val finding = findingOf(step.action, context, check, declaredBefore)
            declaredBefore += declaredBy(step.action)
            StepCheck(step.id, finding, blocked, if (finding == StepFinding.Different) listOf(DIFFERENT_NOTE) else emptyList())
        }
        return PlanCheckResult.Checked(results)
    }

    private enum class Kind { Exchange, Queue }

    private suspend fun findingOf(
        action: MigrationAction,
        context: PlannerContext,
        check: DeclareCheck,
        declaredBefore: Set<Pair<Kind, String>>,
    ): StepFinding {
        if (action.isBrokerLevel) return StepFinding.NotChecked(NOT_READ)
        if (action.vhost != context.vhost) return StepFinding.NotChecked(OTHER_VHOST)
        if (action is MigrationAction.DeclareBinding) {
            ends(action).forEach { (kind, name) ->
                if ((kind to name) in declaredBefore) return@forEach
                when (val probe = check.find(probeOf(kind, name, context.vhost))) {
                    BrokerFinding.Absent -> return StepFinding.Missing(missingSentence(kind, name))
                    is BrokerFinding.Unreadable -> return StepFinding.NotChecked(probe.reason)
                    BrokerFinding.Same, BrokerFinding.Different -> Unit
                }
            }
        }
        val finding = check.find(action)
        return if (action.isDestructive) {
            when (finding) {
                BrokerFinding.Absent -> StepFinding.AlreadyGone
                BrokerFinding.Same, BrokerFinding.Different -> StepFinding.WillBeDeleted
                is BrokerFinding.Unreadable -> StepFinding.NotChecked(finding.reason)
            }
        } else {
            when (finding) {
                BrokerFinding.Absent -> StepFinding.WillBeCreated
                BrokerFinding.Same -> StepFinding.AlreadyThere
                BrokerFinding.Different -> StepFinding.Different
                is BrokerFinding.Unreadable -> StepFinding.NotChecked(finding.reason)
            }
        }
    }

    private fun ends(binding: MigrationAction.DeclareBinding): List<Pair<Kind, String>> = listOf(
        Kind.Exchange to binding.exchange,
        (if (binding.destinationType == BindingDestinationType.EXCHANGE) Kind.Exchange else Kind.Queue) to binding.queue,
    )

    private fun declaredBy(action: MigrationAction): List<Pair<Kind, String>> = when (action) {
        is MigrationAction.DeclareExchange -> listOf(Kind.Exchange to action.name)
        is MigrationAction.DeclareQueue -> listOf(Kind.Queue to action.name)
        is MigrationAction.DeclareStream -> listOf(Kind.Queue to action.name)
        else -> emptyList()
    }

    private fun probeOf(kind: Kind, name: String, vhost: String): MigrationAction = when (kind) {
        Kind.Exchange -> MigrationAction.DeclareExchange(name, vhost = vhost)
        Kind.Queue -> MigrationAction.DeclareQueue(name, vhost = vhost)
    }

    /** A name from a file goes into the sentence only through the echo rule. */
    private fun missingSentence(kind: Kind, name: String): String {
        val noun = if (kind == Kind.Exchange) "exchange" else "queue"
        val named = if (echoableName(name)) "$noun $name" else noun
        return "The $named is not on the broker and is not declared earlier in this plan."
    }
}
