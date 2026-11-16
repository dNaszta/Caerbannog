package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction

/** A plan is the content of a migration (spec A1): a description, one virtual host, and steps that are each exactly one action. */
data class TopologyPlan(
    val description: String = "",
    val vhost: String = "/",
    val steps: List<PlanStep> = emptyList(),
) {
    /** Adds [actions] at the end; every one gets an id no live step has. */
    fun append(actions: List<MigrationAction>): TopologyPlan {
        val first = (steps.maxOfOrNull { it.id } ?: 0) + 1
        return copy(steps = steps + actions.mapIndexed { i, action -> PlanStep(first + i, action) })
    }

    /** Swaps the action of the step [id], keeping its place and id; an unknown id changes nothing. */
    fun replace(id: Int, action: MigrationAction): TopologyPlan =
        copy(steps = steps.map { if (it.id == id) it.copy(action = action) else it })

    fun remove(id: Int): TopologyPlan = copy(steps = steps.filterNot { it.id == id })

    fun moveUp(id: Int): TopologyPlan = swap(steps.indexOfFirst { it.id == id }, -1)

    fun moveDown(id: Int): TopologyPlan = swap(steps.indexOfFirst { it.id == id }, 1)

    fun withDescription(text: String): TopologyPlan = copy(description = text)

    /** Moves the plan and every step to [vhost]. The access actions are broker-level and have none of their own, so they stay as they are. */
    fun withVhost(vhost: String): TopologyPlan =
        copy(vhost = vhost, steps = steps.map { it.copy(action = it.action.onVhost(vhost)) })

    companion object {
        /**
         * The plan of a stored file: every action becomes a step with the id 1..n, and the plan's virtual host is that of the first action that
         * has one (an access action is broker-level and names none), else [defaultVhost]. How a draft is loaded; opening a file uses it too.
         */
        fun of(description: String, actions: List<MigrationAction>, defaultVhost: String): TopologyPlan = TopologyPlan(
            description = description,
            vhost = actions.firstOrNull { !it.isBrokerLevel }?.vhost ?: defaultVhost,
            steps = actions.mapIndexed { i, action -> PlanStep(i + 1, action) },
        )
    }

    private fun swap(index: Int, by: Int): TopologyPlan {
        val other = index + by
        if (index < 0 || other !in steps.indices) return this
        val moved = steps.toMutableList()
        moved[index] = steps[other]
        moved[other] = steps[index]
        return copy(steps = moved)
    }
}

/** [id] is a session-only key for the list (never written to a file); [action] is the existing migration action, unchanged. */
data class PlanStep(val id: Int, val action: MigrationAction) {
    /** The first line of the step: the same words as the dry run and the migrations list. */
    val title: String get() = action.description

    /** The second line of the step, or null. */
    val detail: String? get() = action.detail
}

private fun MigrationAction.onVhost(vhost: String): MigrationAction = when (this) {
    is MigrationAction.DeclareQueue -> copy(vhost = vhost)
    is MigrationAction.DeleteQueue -> copy(vhost = vhost)
    is MigrationAction.DeclareExchange -> copy(vhost = vhost)
    is MigrationAction.DeleteExchange -> copy(vhost = vhost)
    is MigrationAction.DeclareBinding -> copy(vhost = vhost)
    is MigrationAction.DeleteBinding -> copy(vhost = vhost)
    is MigrationAction.DeclareStream -> copy(vhost = vhost)
    is MigrationAction.DeleteStream -> copy(vhost = vhost)
    is MigrationAction.DeclarePolicy -> copy(vhost = vhost)
    is MigrationAction.DeclareOperatorPolicy -> copy(vhost = vhost)
    is MigrationAction.DeletePolicy -> copy(vhost = vhost)
    is MigrationAction.DeclareVhost,
    is MigrationAction.DeclareUser,
    is MigrationAction.SetPermissions,
    is MigrationAction.SetTopicPermissions,
    -> this
}
