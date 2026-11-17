package dev.caerbannog.core.planner

/**
 * What a policy applies to: the six values of `apply-to` the broker knows. [wire] is the text the migration format holds (`applyTo`), [label] the
 * words of the form.
 */
enum class PolicyTarget(val wire: String, val label: String) {
    QUORUM_QUEUES("quorum_queues", "Quorum queues"),
    CLASSIC_QUEUES("classic_queues", "Classic queues"),
    QUEUES("queues", "Queues"),
    STREAMS("streams", "Streams"),
    EXCHANGES("exchanges", "Exchanges"),
    ALL("all", "All"),
    ;

    companion object {
        /** The target of an exact wire text; null for anything else (a hand-written file: the policy is then read-only). */
        fun fromWire(wire: String): PolicyTarget? = entries.firstOrNull { it.wire == wire }
    }
}

/** One choice of a [PolicyValueKind.Choice]: [wire] is what the broker stores, [label] what the form shows. */
data class ChoiceValue(val wire: String, val label: String)

/** The type of a policy setting's value, as the form edits it. */
sealed interface PolicyValueKind {
    /** A whole number of [min] or more. */
    data class WholeNumber(val min: Long) : PolicyValueKind

    /** Digits and one unit of [MaxAgeUnits]; always text on the wire (a JSON number is a 500, S-F1c). */
    data object Duration : PolicyValueKind

    data object Text : PolicyValueKind

    data class Choice(val values: List<ChoiceValue>) : PolicyValueKind
}

/** The units of `max-age` the broker accepted: `Y M D h m s` (S-F1c and the live row of M9-08); `7d`, `7` and `abc` are 400. */
object MaxAgeUnits {
    val measured: Set<Char> = setOf('Y', 'M', 'D', 'h', 'm', 's')
}

/**
 * One setting the policy form offers. [evidence] says why it is offered: `effect:<Target>` (the key takes effect on that kind, measured in M9-00c),
 * `wrong-type:<id>` or `live:<id>`. A key without evidence is not in the catalog.
 */
data class PolicyKeyEntry(
    val key: String,
    val label: String,
    val kind: PolicyValueKind,
    val targets: Set<PolicyTarget>,
    val evidence: List<String>,
)

/**
 * The settings of the policy form, as data (M9-08). The whole-policy rule (S-F1b): a policy binds to a queue only when EVERY key is one the queue's
 * type honours, so a target offers only keys that take effect on what it applies to. `queues` and `all` offer the intersection of the three
 * queue kinds, because whether `apply-to: queues` includes streams is not measured. Adding a key is one entry plus its evidence.
 */
object PolicyKeyCatalog {
    private val quorum = PolicyTarget.QUORUM_QUEUES
    private val classic = PolicyTarget.CLASSIC_QUEUES
    private val streams = PolicyTarget.STREAMS
    private val generic = setOf(PolicyTarget.QUEUES, PolicyTarget.ALL)

    private val onQuorum = listOf("effect:QUORUM")
    private val onQuorumAndClassic = listOf("effect:QUORUM", "effect:CLASSIC")
    private val onAllQueues = listOf("effect:QUORUM", "effect:CLASSIC", "effect:STREAM")

    private const val EXCHANGE_ROW = "live:M9-08-alternate-exchange-on-exchange"

    val entries: List<PolicyKeyEntry> = listOf(
        PolicyKeyEntry("max-length", "Maximum number of messages", PolicyValueKind.WholeNumber(0), setOf(quorum, classic), onQuorumAndClassic),
        PolicyKeyEntry("max-length-bytes", "Keep at most (bytes)", PolicyValueKind.WholeNumber(0), setOf(quorum, classic, streams) + generic, onAllQueues),
        PolicyKeyEntry(
            "overflow", "When the queue is full",
            PolicyValueKind.Choice(
                listOf(
                    ChoiceValue("drop-head", "drop oldest"),
                    ChoiceValue("reject-publish", "refuse new"),
                    ChoiceValue("reject-publish-dlx", "refuse new and dead-letter"),
                ),
            ),
            setOf(quorum, classic), onQuorumAndClassic,
        ),
        PolicyKeyEntry("message-ttl", "Message time to live (ms)", PolicyValueKind.WholeNumber(0), setOf(quorum, classic), onQuorumAndClassic),
        PolicyKeyEntry("expires", "Delete the queue when unused for (ms)", PolicyValueKind.WholeNumber(1), setOf(quorum, classic), onQuorumAndClassic + "wrong-type:expires.zero"),
        PolicyKeyEntry("dead-letter-exchange", "Dead-letter exchange", PolicyValueKind.Text, setOf(quorum, classic), onQuorumAndClassic),
        PolicyKeyEntry("dead-letter-routing-key", "Dead-letter routing key", PolicyValueKind.Text, setOf(quorum, classic), onQuorumAndClassic),
        PolicyKeyEntry(
            "dead-letter-strategy", "Dead-letter strategy",
            PolicyValueKind.Choice(listOf(ChoiceValue("at-least-once", "at least once"))),
            setOf(quorum), onQuorum,
        ),
        PolicyKeyEntry("delivery-limit", "Delivery limit", PolicyValueKind.WholeNumber(0), setOf(quorum), onQuorum),
        PolicyKeyEntry(
            "queue-leader-locator", "Leader locator",
            PolicyValueKind.Choice(listOf(ChoiceValue("balanced", "balanced"))),
            setOf(quorum, classic, streams) + generic, onAllQueues,
        ),
        PolicyKeyEntry("target-group-size", "Target number of members", PolicyValueKind.WholeNumber(1), setOf(quorum), onQuorum + "wrong-type:target-group-size.zero"),
        PolicyKeyEntry("max-age", "Keep messages for", PolicyValueKind.Duration, setOf(streams), listOf("effect:STREAM", "live:M9-08-max-age-units")),
        PolicyKeyEntry("stream-max-segment-size-bytes", "Segment size (bytes)", PolicyValueKind.WholeNumber(1), setOf(streams), listOf("effect:STREAM")),
        PolicyKeyEntry(
            "alternate-exchange", "Alternate exchange", PolicyValueKind.Text,
            setOf(quorum, classic, streams, PolicyTarget.EXCHANGES) + generic, onAllQueues + EXCHANGE_ROW,
        ),
    )

    private val byKey: Map<String, PolicyKeyEntry> = entries.associateBy { it.key }

    /** The entries a policy for [target] may hold, in catalog order. */
    fun offeredFor(target: PolicyTarget): List<PolicyKeyEntry> = entries.filter { target in it.targets }

    fun entry(key: String): PolicyKeyEntry? = byKey[key]
}
