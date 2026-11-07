package dev.caerbannog.core.migrations

/**
 * An individual topology action within a migration.
 */
sealed interface MigrationAction {
    val actionName: String
    val vhost: String
    val description: String
    val isDestructive: Boolean get() = false

    data class DeclareQueue(
        val name: String,
        val type: String = "quorum",
        override val vhost: String = "/",
        val durable: Boolean = true,
        val arguments: Map<String, String> = emptyMap(),
    ) : MigrationAction {
        override val actionName: String get() = "declareQueue"
        override val description: String get() = "Declare $type queue $name"
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
    ) : MigrationAction {
        override val actionName: String get() = "declareExchange"
        override val description: String get() = "Declare $type exchange $name"
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
    ) : MigrationAction {
        override val actionName: String get() = "declareBinding"
        override val description: String
            get() = if (routingKey.isNotBlank()) {
                "Bind $exchange to $queue with $routingKey"
            } else {
                "Bind $exchange to $queue"
            }
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
        val definition: Map<String, String> = emptyMap(),
        override val vhost: String = "/",
    ) : MigrationAction {
        override val actionName: String get() = "declarePolicy"
        override val description: String get() = "Declare policy $name"
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
        val reason: String,
    ) : MigrationApplyResult
    data class Blocked(val reason: String) : MigrationApplyResult
}
