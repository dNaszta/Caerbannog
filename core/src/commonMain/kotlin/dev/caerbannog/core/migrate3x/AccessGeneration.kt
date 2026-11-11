package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationFile

/** Why a user of the export is not declared in the access migration. The app never invents a password. */
sealed interface NotMigratedReason {
    /** D2: `guest` is never generated; it has a publicly known password. */
    data object Guest : NotMigratedReason

    /** P12: the hash uses an algorithm 4.x does not accept; set a new password on the broker. */
    data class UnsupportedAlgorithm(val algorithm: String) : NotMigratedReason
    data object NoAlgorithm : NotMigratedReason

    /** External authentication: the export holds no hash. */
    data object NoPasswordHash : NotMigratedReason
}

data class NotMigratedUser(val name: String, val reason: NotMigratedReason)

/** A permission or topic permission of a user that is not declared: one row per user and virtual host. */
data class LeftOutPermission(val user: String, val vhost: String)

/** What the access file holds; counts only, never a hash. */
data class AccessSummary(val virtualHosts: Int, val users: Int, val permissions: Int, val topicPermissions: Int)

/** [file] is null when there is nothing to write. */
data class AccessGeneration(
    val file: MigrationFile?,
    val notMigrated: List<NotMigratedUser>,
    val leftOutPermissions: List<LeftOutPermission>,
    val summary: AccessSummary?,
)

/** M8-12b STUB: the pure generator of the access migration; it finds nothing until built. */
internal object AccessGenerator {
    fun generate(document: DefinitionsDocument, request: GenerationRequest): AccessGeneration = AccessGeneration(null, emptyList(), emptyList(), null) // M8-12b STUB
}
