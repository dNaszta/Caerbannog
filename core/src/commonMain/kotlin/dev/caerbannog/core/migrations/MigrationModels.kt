package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.json.ArgumentValueJson
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * An individual topology action within a migration.
 */
sealed interface MigrationAction {
    val actionName: String
    val vhost: String
    val description: String
    val isDestructive: Boolean get() = false

    /** One-line summary of arguments or definition for the migrations screen; null when there is nothing to show. */
    val detail: String? get() = null

    data class DeclareQueue(
        val name: String,
        val type: String = "quorum",
        override val vhost: String = "/",
        val durable: Boolean = true,
        val arguments: Map<String, ArgumentValue> = emptyMap(),
        val autoDelete: Boolean = false,
    ) : MigrationAction {
        override val actionName: String get() = "declareQueue"
        override val description: String get() = "Declare $type queue $name"
        override val detail: String? get() = argumentsDetail(arguments)
    }

    data class DeleteQueue(
        val name: String,
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "deleteQueue"
        override val description: String get() = "Delete queue $name"
        override val isDestructive: Boolean get() = true
    }

    data class DeclareExchange(
        val name: String,
        val type: String = "direct",
        override val vhost: String = "/",
        val durable: Boolean = true,
        val autoDelete: Boolean = false,
        val internal: Boolean = false,
        val arguments: Map<String, ArgumentValue> = emptyMap(),
    ) : MigrationAction {
        override val actionName: String get() = "declareExchange"
        override val description: String get() = "Declare $type exchange $name"
        override val detail: String? get() = argumentsDetail(arguments)
    }

    data class DeleteExchange(
        val name: String,
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "deleteExchange"
        override val description: String get() = "Delete exchange $name"
        override val isDestructive: Boolean get() = true
    }

    data class DeclareBinding(
        val exchange: String,
        val queue: String,
        val routingKey: String = "",
        override val vhost: String = "/",
        val destinationType: BindingDestinationType = BindingDestinationType.QUEUE,
        val arguments: Map<String, ArgumentValue> = emptyMap(),
    ) : MigrationAction {
        override val actionName: String get() = "declareBinding"
        override val description: String
            get() {
                val target = if (destinationType == BindingDestinationType.EXCHANGE) "exchange $queue" else queue
                return if (routingKey.isNotBlank()) "Bind $exchange to $target with $routingKey" else "Bind $exchange to $target"
            }
        override val detail: String? get() = argumentsDetail(arguments)
    }

    data class DeleteBinding(
        val exchange: String,
        val queue: String,
        val routingKey: String = "",
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "deleteBinding"
        override val description: String
            get() = if (routingKey.isNotBlank()) {
                "Unbind $exchange from $queue with $routingKey"
            } else {
                "Unbind $exchange from $queue"
            }
        override val isDestructive: Boolean get() = true
    }

    data class DeclareStream(
        val name: String,
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "declareStream"
        override val description: String get() = "Declare stream $name"
    }

    data class DeleteStream(
        val name: String,
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "deleteStream"
        override val description: String get() = "Delete stream $name"
        override val isDestructive: Boolean get() = true
    }

    data class DeclarePolicy(
        val name: String,
        val pattern: String,
        val definition: Map<String, ArgumentValue> = emptyMap(),
        override val vhost: String = "/",
        val applyTo: String = "all",
        val priority: Int = 0,
    ) : MigrationAction {
        override val actionName: String get() = "declarePolicy"
        override val description: String get() = "Declare policy $name"
        override val detail: String get() = policyDetail(applyTo, priority, definition)
    }

    /** A 4.x operator policy: same shape as [DeclarePolicy]; the broker accepts only limit keys in it. */
    data class DeclareOperatorPolicy(
        val name: String,
        val pattern: String,
        val definition: Map<String, ArgumentValue> = emptyMap(),
        override val vhost: String = "/",
        val applyTo: String = "all",
        val priority: Int = 0,
    ) : MigrationAction {
        override val actionName: String get() = "declareOperatorPolicy"
        override val description: String get() = "Declare operator policy $name"
        override val detail: String get() = policyDetail(applyTo, priority, definition)
    }

    data class DeletePolicy(
        val name: String,
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "deletePolicy"
        override val description: String get() = "Delete policy $name"
        override val isDestructive: Boolean get() = true
    }
}

private const val MAX_DETAIL_LENGTH = 120

private fun argumentsDetail(arguments: Map<String, ArgumentValue>): String? =
    if (arguments.isEmpty()) null else oneLine(pairs(arguments))

private fun policyDetail(applyTo: String, priority: Int, definition: Map<String, ArgumentValue>): String {
    val head = "apply-to=$applyTo, priority=$priority"
    return oneLine(if (definition.isEmpty()) head else "$head; ${pairs(definition)}")
}

private fun pairs(entries: Map<String, ArgumentValue>): String =
    entries.toSortedMap().entries.joinToString(", ") { (key, value) -> "$key=${shown(value)}" }

private fun shown(value: ArgumentValue): String = when (value) {
    is ArgumentValue.Text -> value.value
    is ArgumentValue.Whole -> value.value.toString()
    is ArgumentValue.Decimal -> value.value.toString()
    is ArgumentValue.Flag -> value.value.toString()
    ArgumentValue.Nothing -> "null"
    is ArgumentValue.Items, is ArgumentValue.Table -> ArgumentValueJson.encode(value).toString()
}

/** Control characters and the Unicode line and paragraph separators become spaces so the text stays on one line; cut to [MAX_DETAIL_LENGTH] with an ellipsis. */
private fun oneLine(text: String): String {
    val flat = text.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")
    return if (flat.length > MAX_DETAIL_LENGTH) flat.take(MAX_DETAIL_LENGTH - 1) + "…" else flat
}

/**
 * Parsed migration file representation.
 */
data class MigrationFile(
    val version: String,
    val description: String,
    val up: List<MigrationAction>,
    val rawJson: String,
    val checksum: String,
)

/**
 * Lifecycle state of a migration file for a specific connection profile.
 */
sealed interface MigrationStatus {
    data object Pending : MigrationStatus
    data class Applied(val checksum: String) : MigrationStatus
    data class Modified(val recordedChecksum: String, val currentChecksum: String) : MigrationStatus
}

/**
 * Item in the migrations list for UI display.
 */
data class MigrationEntry(
    val file: MigrationFile,
    val status: MigrationStatus,
)

/**
 * Dry-run result for an individual action.
 */
data class ActionDryRun(
    val action: MigrationAction,
    val allowed: Boolean,
    val reason: String? = null,
)

/**
 * Dry-run evaluation of a migration file.
 */
data class MigrationDryRun(
    val version: String,
    val actions: List<ActionDryRun>,
    val canApply: Boolean,
    val blockedReason: String? = null,
)

/**
 * Outcome of attempting to apply migrations.
 */
sealed interface MigrationApplyResult {
    data class Success(val appliedVersions: List<String>) : MigrationApplyResult
    data class ActionFailed(
        val version: String,
        val action: MigrationAction,
        /** 1-based position of the failed action in the migration. */
        val index: Int,
        /** Number of actions of the migration. */
        val total: Int,
        val reason: String,
    ) : MigrationApplyResult
    data class Blocked(val reason: String) : MigrationApplyResult
    /** Every action was applied on the broker but the history could not be written; [reason] is fixed text. */
    data class NotRecorded(val version: String, val reason: String) : MigrationApplyResult
}
