package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.TopologyControlLevel

data class ForwardingContext(val level: TopologyControlLevel, val topologyUnlocked: Boolean)

sealed interface ForwardingBlock {
    data object TopologyLocked : ForwardingBlock
    data object NoPolicymakerTag : ForwardingBlock
    data object CannotReadStatus : ForwardingBlock
    data object ShovelPluginsOff : ForwardingBlock
    data object DestinationMissing : ForwardingBlock
    data class ShovelExists(val status: ShovelStatus) : ForwardingBlock
}

sealed interface ForwardingWarning {
    data class DestinationNotQuorumOrStream(val type: DestinationQueueType) : ForwardingWarning
    data object KeepsShovelAndCredentials : ForwardingWarning
    data class NotEncrypted(val connectionName: String) : ForwardingWarning
}

data class ForwardingPlan(val blocks: List<ForwardingBlock>, val warnings: List<ForwardingWarning>) {
    val canStart: Boolean get() = blocks.isEmpty()
}

object ForwardingPlanner {
    fun gate(context: ForwardingContext): List<ForwardingBlock> = emptyList() // M8-07 STUB

    fun plan(context: ForwardingContext, request: ForwardingRequest, facts: DryRunResult): ForwardingPlan = ForwardingPlan(emptyList(), emptyList()) // M8-07 STUB
}
