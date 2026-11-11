package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult

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
) {
    /** Without the file text (it holds the hashes). */
    override fun toString(): String = "AccessGeneration(file=${file?.let { "version ${it.version}, ${it.up.size} actions" }}, notMigrated=$notMigrated, leftOutPermissions=$leftOutPermissions, summary=$summary)"
}

/**
 * The pure generator of the access migration (D1 to D5). Declares every virtual host except `/` (only the settings the export carries), every user with
 * an allow-listed hash that is not `guest`, and the permissions and topic permissions of those users on declared virtual hosts or `/`. Everything else is
 * listed, never invented. A per-virtual-host export has no access data. The hash passes through as a `SecretValue` and appears only in the file.
 */
internal object AccessGenerator {
    private const val DEFAULT_VHOST = "/"
    private const val GUEST = "guest"

    fun generate(document: DefinitionsDocument, request: GenerationRequest): AccessGeneration {
        if (document.origin is DefinitionsOrigin.SingleVirtualHost) return AccessGeneration(null, emptyList(), emptyList(), null)

        val declared = LinkedHashMap<String, MigrationAction.DeclareUser>()
        val notMigrated = ArrayList<NotMigratedUser>()
        for (user in document.users.sortedBy { it.name }) {
            val hash = user.passwordHash
            val algorithm = user.hashingAlgorithm
            val reason: NotMigratedReason? = when {
                user.name == GUEST -> NotMigratedReason.Guest
                hash == null -> NotMigratedReason.NoPasswordHash
                algorithm == null -> NotMigratedReason.NoAlgorithm
                algorithm !in allowedHashingAlgorithms -> NotMigratedReason.UnsupportedAlgorithm(algorithm)
                else -> null
            }
            if (reason != null) notMigrated += NotMigratedUser(user.name, reason)
            else declared[user.name] = MigrationAction.DeclareUser(user.name, hash!!, algorithm!!, user.tags)
        }

        val vhosts = document.virtualHosts.filter { it.name != DEFAULT_VHOST }.sortedBy { it.name }
            .map { MigrationAction.DeclareVhost(it.name, it.description, it.tags, it.defaultQueueType) }
        val knownVhosts = document.virtualHosts.map { it.name }.toSet() + DEFAULT_VHOST

        val permissions = document.permissions.filter { it.user in declared && it.vhost in knownVhosts }
            .sortedWith(compareBy({ it.vhost }, { it.user }))
            .map { MigrationAction.SetPermissions(it.vhost, it.user, it.configure, it.write, it.read) }
        val topic = document.topicPermissions.filter { it.user in declared && it.vhost in knownVhosts }
            .sortedWith(compareBy({ it.vhost }, { it.user }, { it.exchange }))
            .map { MigrationAction.SetTopicPermissions(it.vhost, it.user, it.exchange, it.write, it.read) }

        // One row per user and virtual host: the topic permissions of the same pair are folded in.
        val leftOut = (document.permissions.map { it.user to it.vhost } + document.topicPermissions.map { it.user to it.vhost })
            .filter { (user, _) -> user !in declared }
            .distinct().sortedWith(compareBy({ it.first }, { it.second })).map { LeftOutPermission(it.first, it.second) }

        val actions = vhosts + declared.values.toList() + permissions + topic
        if (actions.isEmpty()) return AccessGeneration(null, notMigrated, leftOut, null)
        val raw = MigrationParser.exportToJson("0000", "Access migrated from RabbitMQ 3.x ${request.source}", actions)
        val file = (MigrationParser.parse(raw) as? MigrationValidationResult.Valid)?.migration ?: error("a generated access migration does not parse")
        return AccessGeneration(file, notMigrated, leftOut, AccessSummary(vhosts.size, declared.size, permissions.size, topic.size))
    }
}
