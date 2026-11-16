package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * A problem of a plan. [stepId] is the step it is about, or null when it is about the plan as a whole. A blocking problem stops saving;
 * a note ([blocking] false) does not.
 */
data class PlanProblem(val stepId: Int?, val message: String, val blocking: Boolean)

object PlanValidation {
    fun problems(plan: TopologyPlan): List<PlanProblem> =
        duplicates(plan) + bindingsBeforeTheirTargets(plan) + parserRoundTrip(plan)

    /** The same queue, exchange or stream declared again, and a queue and a stream of one name. The later step carries the problem. */
    private fun duplicates(plan: TopologyPlan): List<PlanProblem> {
        val result = mutableListOf<PlanProblem>()
        val declared = mutableListOf<Pair<PlanStep, Declared>>()
        for (step in plan.steps) {
            val now = declaredBy(step.action) ?: continue
            val earlier = declared.firstOrNull { (_, d) -> d.name == now.name && (d.kind == now.kind || d.isQueueOrStream() && now.isQueueOrStream()) }
            if (earlier != null) {
                val (_, before) = earlier
                result += when {
                    before.kind != now.kind -> PlanProblem(step.id, PlannerTexts.queueAndStreamOfOneName(now.name), blocking = true)
                    before.action != now.action -> PlanProblem(step.id, PlannerTexts.declaredWithOtherSettings(now.name), blocking = true)
                    else -> PlanProblem(step.id, PlannerTexts.declaredTwice(now.name), blocking = false)
                }
            }
            declared += step to now
        }
        return result
    }

    private fun bindingsBeforeTheirTargets(plan: TopologyPlan): List<PlanProblem> {
        val result = mutableListOf<PlanProblem>()
        plan.steps.forEachIndexed { index, step ->
            val binding = step.action as? MigrationAction.DeclareBinding ?: return@forEachIndexed
            val needs = listOf(
                Need("exchange", binding.exchange) { it.kind == Kind.EXCHANGE },
                if (binding.destinationType == BindingDestinationType.EXCHANGE) {
                    Need("exchange", binding.queue) { it.kind == Kind.EXCHANGE }
                } else {
                    Need("queue", binding.queue) { it.kind == Kind.QUEUE || it.kind == Kind.STREAM }
                },
            )
            for (need in needs) {
                val declares = { s: PlanStep -> declaredBy(s.action)?.let { it.name == need.name && need.accepts(it) } == true }
                if (plan.steps.take(index).any(declares)) continue
                val later = plan.steps.drop(index + 1).indexOfFirst(declares)
                if (later < 0) continue
                val laterStep = plan.steps[index + 1 + later]
                val kind = if (need.kind == "queue" && declaredBy(laterStep.action)?.kind == Kind.STREAM) "stream" else need.kind
                result += PlanProblem(step.id, PlannerTexts.declaredLater(kind, need.name, index + 2 + later), blocking = true)
            }
        }
        return result
    }

    /** What the file will be: if the parser refuses the plan, its fixed reason is the problem, unchanged. */
    private fun parserRoundTrip(plan: TopologyPlan): List<PlanProblem> {
        if (plan.steps.isEmpty()) return emptyList()
        val reason = FormChecks.roundTrip(plan.description, plan.steps.map { it.action }) ?: return emptyList()
        return listOf(PlanProblem(null, reason, blocking = true))
    }

    private enum class Kind { QUEUE, EXCHANGE, STREAM }

    private class Need(val kind: String, val name: String, val accepts: (Declared) -> Boolean)

    private class Declared(val kind: Kind, val name: String, val action: MigrationAction) {
        fun isQueueOrStream(): Boolean = kind == Kind.QUEUE || kind == Kind.STREAM
    }

    private fun declaredBy(action: MigrationAction): Declared? = when (action) {
        is MigrationAction.DeclareQueue -> Declared(Kind.QUEUE, action.name, action)
        is MigrationAction.DeclareExchange -> Declared(Kind.EXCHANGE, action.name, action)
        is MigrationAction.DeclareStream -> Declared(Kind.STREAM, action.name, action)
        else -> null
    }
}
