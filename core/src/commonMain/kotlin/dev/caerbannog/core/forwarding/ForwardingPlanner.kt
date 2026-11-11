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
    /** Before any request: the level rule only (Read-only or no configure permission locks). */
    fun gate(context: ForwardingContext): List<ForwardingBlock> =
        if (context.level == TopologyControlLevel.READ_ONLY || !context.topologyUnlocked) listOf(ForwardingBlock.TopologyLocked) else emptyList()

    /** [gate] plus the rules over the dry run, in the fixed order of the brief. */
    fun plan(context: ForwardingContext, request: ForwardingRequest, facts: DryRunResult): ForwardingPlan {
        val tags = facts.userTags
        val blocks = gate(context) + listOfNotNull(
            ForwardingBlock.NoPolicymakerTag.takeIf { "policymaker" !in tags && "administrator" !in tags },
            // Without status the app could not see a wrong source password (the PUT answers 201) nor clean up after it.
            ForwardingBlock.CannotReadStatus.takeIf { "monitoring" !in tags && "administrator" !in tags },
            ForwardingBlock.ShovelPluginsOff.takeIf { !facts.shovelPluginsOn },
            ForwardingBlock.DestinationMissing.takeIf { facts.destination == DestinationQueue.Missing },
            facts.existingShovel?.let { ForwardingBlock.ShovelExists(it) },
        )
        val type = (facts.destination as? DestinationQueue.Exists)?.type
        val warnings = listOfNotNull(
            type?.takeIf { it != DestinationQueueType.QUORUM && it != DestinationQueueType.STREAM }?.let { ForwardingWarning.DestinationNotQuorumOrStream(it) },
            ForwardingWarning.KeepsShovelAndCredentials.takeIf { request.srcDeleteAfter == SrcDeleteAfter.NEVER },
            ForwardingWarning.NotEncrypted(request.source.connectionName).takeIf { !request.source.address.tls },
        )
        return ForwardingPlan(blocks, warnings)
    }
}
