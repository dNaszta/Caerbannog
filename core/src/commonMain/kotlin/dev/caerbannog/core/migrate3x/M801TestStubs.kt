package dev.caerbannog.core.migrate3x

// =====================================================================================================
// M8-01 STEP 2 COMPILE STUBS (test developer). NOT PRODUCTION CODE.
//
// Only the signatures of docs/tasks/M8-01-definitions-model-and-file-import.md, so that the tests compile
// and fail on NotImplementedError or on wrong values. The senior developer REPLACES this file: split it into
// DefinitionsModel.kt, ArgumentValue.kt, SecretValue.kt and DefinitionsParser.kt as the brief says, and delete
// this file. Nothing in here implements behaviour. Names the brief leaves open (chosen by the test developer,
// change them with the tests): UnassignedDefinitions.queueCount/exchangeCount/bindingCount/policyCount.
// =====================================================================================================

const val MAX_DEFINITIONS_FILE_BYTES: Long = 64L * 1024 * 1024

/** Nesting limit of Items/Table in an ArgumentValue (top-level map is level 0). */
const val MAX_ARGUMENT_DEPTH: Int = 32

// ---- ArgumentValue ----
sealed interface ArgumentValue {
    data class Text(val value: String) : ArgumentValue
    data class Whole(val value: Long) : ArgumentValue
    data class Decimal(val value: Double) : ArgumentValue
    data class Flag(val value: Boolean) : ArgumentValue
    data class Items(val items: List<ArgumentValue>) : ArgumentValue
    data class Table(val entries: Map<String, ArgumentValue>) : ArgumentValue
    data object Nothing : ArgumentValue
}

// ---- SecretValue (stub: every behaviour throws; the real one is a plain class, see the brief) ----
class SecretValue private constructor(private val text: String) {
    override fun toString(): String = TODO("M8-01: constant SecretValue(***)")
    fun matches(other: SecretValue): Boolean = TODO("M8-01: content compare")
    fun expose(): String = TODO("M8-01: the text")

    companion object {
        fun of(text: String): SecretValue = SecretValue(text)
    }
}

// ---- Model ----
sealed interface DefinitionsOrigin {
    data object FullExport : DefinitionsOrigin
    data class SingleVirtualHost(val assignedName: String) : DefinitionsOrigin
}

enum class BindingTarget { Queue, Exchange }

sealed interface QueueKind {
    data object Classic : QueueKind
    data object Quorum : QueueKind
    data object Stream : QueueKind
    data class Other(val name: String) : QueueKind
}

data class VirtualHostDefinition(val name: String, val description: String, val tags: List<String>, val defaultQueueType: String?)

data class QueueDefinition(
    val vhost: String,
    val name: String,
    val durable: Boolean = true,
    val autoDelete: Boolean = false,
    val type: QueueKind,
    val arguments: Map<String, ArgumentValue>,
)

data class ExchangeDefinition(
    val vhost: String,
    val name: String,
    val type: String,
    val durable: Boolean = true,
    val autoDelete: Boolean = false,
    val internal: Boolean = false,
    val arguments: Map<String, ArgumentValue>,
)

data class BindingDefinition(
    val vhost: String,
    val source: String,
    val destination: String,
    val destinationKind: BindingTarget,
    val routingKey: String = "",
    val arguments: Map<String, ArgumentValue>,
)

data class PolicyDefinition(
    val vhost: String,
    val name: String,
    val pattern: String,
    val applyTo: String = "all",
    val priority: Int = 0,
    val definition: Map<String, ArgumentValue>,
)

data class OperatorPolicyDefinition(
    val vhost: String,
    val name: String,
    val pattern: String,
    val applyTo: String = "all",
    val priority: Int = 0,
    val definition: Map<String, ArgumentValue>,
)

data class UserDefinition(
    val name: String,
    val passwordHash: SecretValue?,
    val hashingAlgorithm: String?,
    val tags: List<String>,
    val limits: Map<String, ArgumentValue>,
)

data class PermissionDefinition(val vhost: String, val user: String, val configure: String, val read: String, val write: String)

data class TopicPermissionDefinition(val vhost: String, val user: String, val exchange: String, val write: String, val read: String)

data class UnparsedParameter(val vhost: String?, val component: String, val name: String)

data class DefinitionsDocument(
    val sourceVersion: String?,
    val virtualHosts: List<VirtualHostDefinition>,
    val queues: List<QueueDefinition>,
    val exchanges: List<ExchangeDefinition>,
    val bindings: List<BindingDefinition>,
    val policies: List<PolicyDefinition>,
    val operatorPolicies: List<OperatorPolicyDefinition>,
    val users: List<UserDefinition>,
    val permissions: List<PermissionDefinition>,
    val topicPermissions: List<TopicPermissionDefinition>,
    val otherParameters: List<UnparsedParameter>,
    val origin: DefinitionsOrigin = DefinitionsOrigin.FullExport,
)

// ---- Unassigned draft (a per-virtual-host export) ----
class UnassignedDefinitions internal constructor(
    val sourceVersion: String?,
    val queueCount: Int,
    val exchangeCount: Int,
    val bindingCount: Int,
    val policyCount: Int,
) {
    fun assignVirtualHost(name: String): VirtualHostAssignment = TODO("M8-01: validate and assign")
}

sealed interface VirtualHostAssignment {
    data class Assigned(val document: DefinitionsDocument) : VirtualHostAssignment
    data class InvalidName(val reason: String) : VirtualHostAssignment
}

// ---- Parser ----
sealed interface DefinitionsRejection {
    val message: String

    data object NotJson : DefinitionsRejection {
        override val message: String = ""
    }

    data object NoDefinitions : DefinitionsRejection {
        override val message: String = ""
    }

    data object NotDefinitionsFile : DefinitionsRejection {
        override val message: String = ""
    }

    data class Malformed(val section: String) : DefinitionsRejection {
        override val message: String get() = ""
    }
}

sealed interface DefinitionsParseResult {
    data class Parsed(val document: DefinitionsDocument) : DefinitionsParseResult
    data class NeedsVirtualHost(val draft: UnassignedDefinitions) : DefinitionsParseResult
    data class Rejected(val reason: DefinitionsRejection) : DefinitionsParseResult
}

object DefinitionsParser {
    fun parse(text: String): DefinitionsParseResult = TODO("M8-01: the parser")
}

// ---- File loading ----
sealed interface FileLoadResult {
    data class Loaded(val text: String) : FileLoadResult
    data class Unreadable(val reason: String) : FileLoadResult
    data class TooLarge(val bytes: Long, val limit: Long) : FileLoadResult
}

fun interface DefinitionsFileLoader {
    suspend fun load(path: String): FileLoadResult
}
