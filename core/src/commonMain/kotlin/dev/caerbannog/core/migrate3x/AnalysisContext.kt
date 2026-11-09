package dev.caerbannog.core.migrate3x

/** A policy or an operator policy with its pattern compiled once; [regex] is null when the pattern is not a valid regular expression. */
internal data class PolicyView(
    val kind: ItemKind,
    val vhost: String,
    val name: String,
    val pattern: String,
    val applyTo: String,
    val priority: Int,
    val definition: Map<String, ArgumentValue>,
    val regex: Regex?,
) {
    /** The definition keys that satisfy [wanted], sorted. */
    fun keys(wanted: (String) -> Boolean): List<String> = definition.keys.filter(wanted).sorted()

    val ref: ItemRef get() = ItemRef(kind, vhost, name)
}

/** What every rule can read: the document, the optional live facts, and values computed once per run. */
internal class AnalysisContext(val document: DefinitionsDocument, val live: LiveSourceFacts?) {
    /** Policies first, then operator policies. */
    val policies: List<PolicyView> =
        document.policies.map { view(ItemKind.Policy, it.vhost, it.name, it.pattern, it.applyTo, it.priority, it.definition) } +
            document.operatorPolicies.map { view(ItemKind.OperatorPolicy, it.vhost, it.name, it.pattern, it.applyTo, it.priority, it.definition) }

    private val mirroring = MirroringIndex(policies)

    val hasClassicQueue: Boolean = document.queues.any { it.isClassic }

    /** Major number of the source version, or null when absent or unreadable. */
    val sourceMajorVersion: Int? = document.sourceVersion?.takeWhileDigits()?.toIntOrNull()

    /** The policy that gives [queue] mirrors on a 3.x broker, or null when the queue is not mirrored. */
    fun mirroringPolicy(queue: QueueDefinition): PolicyView? = mirroring.mirroringPolicy(queue)

    private fun view(kind: ItemKind, vhost: String, name: String, pattern: String, applyTo: String, priority: Int, definition: Map<String, ArgumentValue>) =
        PolicyView(kind, vhost, name, pattern, applyTo, priority, definition, compileOrNull(pattern))

    private fun String.takeWhileDigits(): String = takeWhile { it in '0'..'9' }
}

private fun compileOrNull(pattern: String): Regex? =
    try {
        Regex(pattern)
    } catch (_: IllegalArgumentException) {
        null
    }
