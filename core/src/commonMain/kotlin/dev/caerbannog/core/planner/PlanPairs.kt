package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction

/**
 * Recognises a stream and its response stream (DLN) by name alone (A11). Display only: no marker is written to the file and nothing else
 * is derived from a pair, so removing the stream leaves its response stream an ordinary stream.
 */
object PlanPairs {
    /**
     * Maps the index of each response stream in [streamNames] to the index of its request stream: a name that gives a base through
     * [PlanNaming.baseOf] that is another name of the list. In list order of the response, so links are deterministic. The one rule behind
     * the plan's response links, the graph note and the summary sentence.
     */
    fun responseIndexes(streamNames: List<String>): Map<Int, Int> {
        val result = LinkedHashMap<Int, Int>()
        streamNames.forEachIndexed { index, name ->
            val base = PlanNaming.baseOf(name, DerivedWord.DLN) ?: return@forEachIndexed
            val request = streamNames.indexOfFirst { it == base }
            if (request >= 0 && request != index) result[index] = request
        }
        return result
    }

    /**
     * Maps the step id of each response stream to the step id of its request stream: a `DeclareStream` whose name gives a base through
     * [PlanNaming.baseOf] that is the name of another `DeclareStream` of the plan. In plan order of the response step, so links are deterministic.
     */
    fun responseOf(plan: TopologyPlan): Map<Int, Int> {
        val streams = plan.steps.filter { it.action is MigrationAction.DeclareStream }
        val byIndex = responseIndexes(streams.map { (it.action as MigrationAction.DeclareStream).name })
        return byIndex.entries.associate { (response, request) -> streams[response].id to streams[request].id }
    }

    /**
     * The names of the request streams whose response stream is the destination of an MQTT route among [actions] (A10, A11), each once, in the
     * order the request streams are declared. Only `DeclareStream` counts as a stream, so a queue of that name is not one. A route into the request
     * stream, or into a stream that is not a response stream, says nothing.
     */
    fun requestsAnsweredOverMqtt(actions: List<MigrationAction>): List<String> {
        val names = actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name }
        val routed = actions.mapNotNull { MqttRoutes.routeOf(it)?.destination }.toSet()
        val answered = responseIndexes(names).filterKeys { names[it] in routed }.values.toSet()
        return answered.sorted().map { names[it] }.distinct()
    }
}
