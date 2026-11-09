package dev.caerbannog.core.migrate3x

// M8-03 step 2 (test developer): COMPILE STUBS ONLY. The senior developer replaces this whole file with the real
// types (split into the files named in the brief) and deletes it. Signatures are the brief's; bodies return wrong
// (empty) values so the tests are red for the right reason, not because of a compile error. No behaviour lives here.

enum class Severity { Blocker, Warning, Info }
enum class FindingClass { MessageLoss, Incompatibility, BehaviourChange, NotCovered, ClientCheck, Access }
enum class ItemKind { Queue, Exchange, Binding, Policy, OperatorPolicy, VirtualHost, User, Permission, TopicPermission, Parameter, Global }
enum class AppliesWhen { Always, ConvertedToQuorum, KeptClassic }
enum class EvidenceSource { Definitions, LiveQueueStats, LiveDeprecatedFeatures, KnownBrokerBehaviour }
enum class Acknowledgement { OrderingByPriorityMayChange, PubliclyKnownPassword }
enum class DefaultDecision { Accept, Skip }

sealed interface Proposal {
    data class ConvertQueueType(val target: QueueKind, val acknowledgement: Acknowledgement?) : Proposal
    data object KeepClassic : Proposal
    data class RemoveArgument(val argument: String) : Proposal
    data class SetArgument(val argument: String, val value: ArgumentValue) : Proposal
    data object ClearAutoDelete : Proposal
    data object MakeDurable : Proposal
    data class DropPolicyKeys(val keys: List<String>) : Proposal
    data class DeclareVirtualHost(val name: String) : Proposal
    data class DeclareUser(
        val name: String,
        val tags: List<String>,
        val hashingAlgorithm: String?,
        val hasPasswordHash: Boolean,
        val acknowledgement: Acknowledgement?,
    ) : Proposal
    data class SetPermissions(val vhost: String, val user: String) : Proposal
    data class SetTopicPermissions(val vhost: String, val user: String, val exchange: String) : Proposal
    data class Manual(val title: String, val text: String, val recommended: Boolean) : Proposal
    data object None : Proposal
}

data class Finding(
    val id: String,
    val rule: String,
    val severity: Severity,
    val findingClass: FindingClass,
    val kind: ItemKind,
    val vhost: String?,
    val name: String?,
    val about: List<String>,
    val appliesWhen: AppliesWhen,
    val message: String,
    val why: String,
    val proposal: Proposal,
    val alternatives: List<Proposal>,
    val defaultDecision: DefaultDecision,
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

data class ReportSource(val name: String?, val sourceVersion: String?, val live: Boolean, val singleVirtualHost: String?)
data class CompatibilityReport(val source: ReportSource, val summary: ReportSummary, val findings: List<Finding>)

data class QueueRef(val vhost: String, val name: String)
enum class ClassicIndexVersion { V1, V2, Unknown }
data class LiveSourceFacts(
    val connectionName: String,
    val brokerVersion: String?,
    val classicIndexVersions: Map<QueueRef, ClassicIndexVersion> = emptyMap(),
    val deprecatedFeaturesInUse: Set<String> = emptySet(),
)

data class RuleInfo(val id: String, val verified: Boolean, val evidence: String)

object RuleCatalog {
    val all: List<RuleInfo> = emptyList()
}

object CompatibilityAnalyzer {
    fun analyze(document: DefinitionsDocument, live: LiveSourceFacts? = null): CompatibilityReport =
        CompatibilityReport(ReportSource(null, null, false, null), ReportSummary(0, 0, 0, 0, 0, 0, 0), emptyList())
}

object CompatibilityReportJson {
    const val SCHEMA: String = "caerbannog.compat-report/1"
    fun encode(report: CompatibilityReport): String = "{}"
}

object CompatibilityReportText {
    fun render(report: CompatibilityReport): String = ""
}
