package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction

/**
 * Recognises a stream and its response stream (DLN) by name alone (A11). Display only: no marker is written to the file and nothing else
 * is derived from a pair, so removing the stream leaves its response stream an ordinary stream.
 */
object PlanPairs {
    /**
     * Maps the step id of each response stream to the step id of its request stream: a `DeclareStream` whose name gives a base through
     * [PlanNaming.baseOf] that is the name of another `DeclareStream` of the plan. In plan order of the response step, so links are deterministic.
     */
    fun responseOf(plan: TopologyPlan): Map<Int, Int> {
        val streams = plan.steps.mapNotNull { step -> (step.action as? MigrationAction.DeclareStream)?.let { step.id to it.name } }
        val result = LinkedHashMap<Int, Int>()
        for ((id, name) in streams) {
            val base = PlanNaming.baseOf(name, DerivedWord.DLN) ?: continue
            val request = streams.firstOrNull { (otherId, otherName) -> otherId != id && otherName == base } ?: continue
            result[id] = request.first
        }
        return result
    }
}
