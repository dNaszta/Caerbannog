package dev.caerbannog.core.migrate3x

internal const val MIRRORING_KEY = "ha-mode"

/**
 * Which policy mirrors a queue on the 3.x broker. RabbitMQ applies one policy per queue: among the policies of the queue's virtual
 * host that apply to queues and whose unanchored pattern matches the name, the highest priority wins. A tie is broken by the
 * policy name (the broker's own tie behaviour is unverified; the rule only makes the report deterministic). The queue is mirrored
 * when the winner has an `ha-mode` key. Operator policies never mirror, and a pattern the app cannot evaluate never matches (it is skipped when the winner is picked).
 */
internal class MirroringIndex(policies: List<PolicyView>) {
    private val candidatesByVhost: Map<String, List<PolicyView>> = policies
        .filter { it.kind == ItemKind.Policy && (it.applyTo == "all" || it.applyTo == "queues") }
        .groupBy { it.vhost }
        .mapValues { (_, inVhost) -> inVhost.sortedWith(compareByDescending<PolicyView> { it.priority }.thenBy { it.name }) }

    fun mirroringPolicy(queue: QueueDefinition): PolicyView? {
        val winner = candidatesByVhost[queue.vhost].orEmpty().firstOrNull { it.matcher?.matches(queue.name) == true }
        return winner?.takeIf { MIRRORING_KEY in it.definition }
    }
}
