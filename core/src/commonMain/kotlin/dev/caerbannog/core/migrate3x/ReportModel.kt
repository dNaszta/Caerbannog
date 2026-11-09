package dev.caerbannog.core.migrate3x

/** How serious a finding is. JSON: `blocker`, `warning`, `info`. */
enum class Severity { Blocker, Warning, Info }

/** What kind of problem a finding describes. [MessageLoss] findings always come first in a report (P16). */
enum class FindingClass { MessageLoss, Incompatibility, BehaviourChange, NotCovered, ClientCheck, Access }

/** The kind of item a finding is about; the declaration order is the sort order inside a report. */
enum class ItemKind { Queue, Exchange, Binding, Policy, OperatorPolicy, VirtualHost, User, Permission, TopicPermission, Parameter, Global }

/** A finding that only matters for one outcome of a queue. */
enum class AppliesWhen { Always, ConvertedToQuorum, KeptClassic }

/** Where the proof for a finding comes from. */
enum class EvidenceSource { Definitions, LiveQueueStats, LiveDeprecatedFeatures, KnownBrokerBehaviour }

/** A fact the user has to tick before a proposal is applied; [text] is what the screen and the export show. */
enum class Acknowledgement(val text: String) {
    OrderingByPriorityMayChange(ReportTexts.TICK_ORDERING),
    PubliclyKnownPassword(ReportTexts.TICK_KNOWN_PASSWORD),
}

/** What the review starts with for a finding. */
enum class DefaultDecision { Accept, Skip }

/** What the app proposes for a finding. The review turns a proposal into an action without parsing any text. */
sealed interface Proposal {
    data class ConvertQueueType(val target: QueueKind, val acknowledgement: Acknowledgement?) : Proposal
    data object KeepClassic : Proposal
    data class RemoveArgument(val argument: String) : Proposal
    data class SetArgument(val argument: String, val value: ArgumentValue) : Proposal
    data object ClearAutoDelete : Proposal
    data object MakeDurable : Proposal
    data class DropPolicyKeys(val keys: List<String>) : Proposal
    data class DeclareVirtualHost(val name: String) : Proposal

    /** Carries the user name, tags, algorithm and whether a hash exists; never the hash. */
    data class DeclareUser(
        val name: String,
        val tags: List<String>,
        val hashingAlgorithm: String?,
        val hasPasswordHash: Boolean,
        val acknowledgement: Acknowledgement?,
    ) : Proposal

    /** The patterns are read from the document by the access migration. */
    data class SetPermissions(val vhost: String, val user: String) : Proposal
    data class SetTopicPermissions(val vhost: String, val user: String, val exchange: String) : Proposal

    /** No action in the app: steps for the user. */
    data class Manual(val title: String, val text: String, val recommended: Boolean) : Proposal
    data object None : Proposal
}

/** One incompatibility with 4.x, or one note, about one item. */
data class Finding(
    /** `<rule>|<vhost or ->|<kind>|<name or ->|<about joined by ,>`; unique inside a report. */
    val id: String,
    val rule: String,
    val severity: Severity,
    val findingClass: FindingClass,
    val kind: ItemKind,
    /** Null for global items and for broker-level items (virtual host, user). */
    val vhost: String?,
    /** Never a secret; null for [ItemKind.Global]. */
    val name: String?,
    /** The argument names or policy keys the finding is about, sorted. */
    val about: List<String>,
    val appliesWhen: AppliesWhen,
    val message: String,
    val why: String,
    val proposal: Proposal,
    val alternatives: List<Proposal>,
    val defaultDecision: DefaultDecision,
    /** True only when the rule has spike evidence (see [RuleCatalog]). */
    val verified: Boolean,
    val evidence: EvidenceSource,
)

data class ReportSummary(
    val topologyItems: Int,
    val compatibleItems: Int,
    val needDecision: Int,
    val blockers: Int,
    val warnings: Int,
    val infos: Int,
    val messageLoss: Int,
)

/** [name] is the live connection name, null for a file. */
data class ReportSource(val name: String?, val sourceVersion: String?, val live: Boolean, val singleVirtualHost: String?)

data class CompatibilityReport(val source: ReportSource, val summary: ReportSummary, val findings: List<Finding>)
