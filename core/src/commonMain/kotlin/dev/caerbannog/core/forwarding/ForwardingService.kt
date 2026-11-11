package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.TopologyControlLevel

sealed interface Prepared {
    data class Ready(val plan: ForwardingPlan, val facts: DryRunResult, val confirmation: ForwardingConfirmation) : Prepared
    /** [facts] null when the gate blocked: no request was sent. */
    data class Blocked(val plan: ForwardingPlan, val facts: DryRunResult?) : Prepared
    data class ProbeFailed(val outcome: DryRunOutcome) : Prepared
}

sealed interface StartResult {
    /** Submitted; the state is Started(0). */
    data object Started : StartResult
    data object NotConfirmed : StartResult
    data class Blocked(val plan: ForwardingPlan) : StartResult
    data class Refused(val reason: String) : StartResult
    data class ProbeFailed(val outcome: DryRunOutcome) : StartResult
    data class Failed(val reason: String) : StartResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data object NeedsFullControl : RemoveResult
    data class Failed(val reason: String) : RemoveResult
}

/**
 * The safeguards of spec 9a in code, so no screen can skip them. [context] is read at every call: the level can change while the dialog is open.
 */
class ForwardingService(private val gateway: ShovelGateway, private val context: () -> ForwardingContext) {
    /** "Choose to forward": the gate first (a locked connection sends nothing), then one dry run and the plan. */
    suspend fun prepare(request: ForwardingRequest): Prepared {
        val ctx = context()
        val locked = ForwardingPlanner.gate(ctx)
        if (locked.isNotEmpty()) return Prepared.Blocked(ForwardingPlan(locked, emptyList()), null)
        return when (val outcome = gateway.dryRun(request)) {
            is DryRunOutcome.Done -> {
                val plan = ForwardingPlanner.plan(ctx, request, outcome.result)
                if (plan.canStart) Prepared.Ready(plan, outcome.result, ForwardingConfirmations.of(request, plan)) else Prepared.Blocked(plan, outcome.result)
            }
            else -> Prepared.ProbeFailed(outcome)
        }
    }

    /** Refuses unless [typedName] is the source queue name exactly; repeats the dry run so a shovel that appeared meanwhile is never overwritten. */
    suspend fun start(request: ForwardingRequest, typedName: String): StartResult {
        if (typedName != request.source.queue) return StartResult.NotConfirmed
        val ctx = context()
        val locked = ForwardingPlanner.gate(ctx)
        if (locked.isNotEmpty()) return StartResult.Blocked(ForwardingPlan(locked, emptyList()))
        val outcome = gateway.dryRun(request)
        if (outcome !is DryRunOutcome.Done) return StartResult.ProbeFailed(outcome)
        val plan = ForwardingPlanner.plan(ctx, request, outcome.result)
        if (!plan.canStart) return StartResult.Blocked(plan)
        return when (val started = gateway.start(request)) {
            StartOutcome.Submitted -> StartResult.Started
            is StartOutcome.Refused -> StartResult.Refused(started.reason)
            StartOutcome.NotPermitted -> StartResult.Failed("The broker did not allow creating the shovel")
            StartOutcome.Unreachable -> StartResult.Failed("The broker could not be reached")
            is StartOutcome.Failed -> StartResult.Failed(started.reason)
        }
    }

    /** One status poll. A terminated shovel is removed once (cleanup, allowed wherever creating was) and reported as failed. */
    suspend fun poll(request: ForwardingRequest, previous: ForwardingState): ForwardingState {
        if (previous is ForwardingState.Finished || previous is ForwardingState.Failed) return previous
        val next = ForwardingProgress.next(previous, gateway.status(request.shovel))
        if (next !is ForwardingState.Failed) return next
        val ctx = context()
        val cleaned = ForwardingPlanner.gate(ctx).isEmpty() && gateway.remove(request.shovel) == RemoveOutcome.Removed
        return next.copy(cleanedUp = cleaned)
    }

    /** The user's Remove: needs Full control (P4). Deletes the shovel parameter only, never a queue. */
    suspend fun remove(shovel: ShovelRef): RemoveResult {
        val ctx = context()
        if (ctx.level != TopologyControlLevel.FULL_CONTROL || !ctx.topologyUnlocked) return RemoveResult.NeedsFullControl
        return when (val removed = gateway.remove(shovel)) {
            RemoveOutcome.Removed, RemoveOutcome.NotFound -> RemoveResult.Removed
            RemoveOutcome.NotPermitted -> RemoveResult.Failed("The broker did not allow removing the shovel")
            RemoveOutcome.Unreachable -> RemoveResult.Failed("The broker could not be reached")
            is RemoveOutcome.Failed -> RemoveResult.Failed(removed.reason)
        }
    }
}
