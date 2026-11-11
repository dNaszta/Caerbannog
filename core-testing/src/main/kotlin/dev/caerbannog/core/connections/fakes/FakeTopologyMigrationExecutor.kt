package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.QueueRow

/**
 * Fake implementation of [TopologyMigrationExecutor] for BDD and unit tests.
 * Directly applies changes to [FakeTopologyCatalog] and/or [FakeStreamCatalog].
 */
class FakeTopologyMigrationExecutor(
    private val topologyCatalog: FakeTopologyCatalog? = null,
    private val streamCatalog: FakeStreamCatalog? = null,
) : TopologyMigrationExecutor {

    val executedActions = mutableListOf<MigrationAction>()

    // M8-04: what the broker would hold, keyed by (vhost, name), so tests can assert typed values. Read-only views.
    private val queues = linkedMapOf<Pair<String, String>, MigrationAction.DeclareQueue>()
    private val policies = linkedMapOf<Pair<String, String>, MigrationAction.DeclarePolicy>()
    private val operatorPolicies = linkedMapOf<Pair<String, String>, MigrationAction.DeclareOperatorPolicy>()
    val declaredQueues: Map<Pair<String, String>, MigrationAction.DeclareQueue> get() = queues
    val declaredPolicies: Map<Pair<String, String>, MigrationAction.DeclarePolicy> get() = policies
    val declaredOperatorPolicies: Map<Pair<String, String>, MigrationAction.DeclareOperatorPolicy> get() = operatorPolicies
    // M8-12a: what the 4.x broker holds of the access items. The hash of a user lives only here, as a SecretValue, and is only compared with `matches`.
    class ExistingVhost(val description: String = "", val tags: List<String> = emptyList(), val defaultQueueType: String? = null)
    class ExistingUser(val hash: SecretValue, val algorithm: String, val tags: List<String>)
    val vhosts = linkedMapOf<String, ExistingVhost>()
    val users = linkedMapOf<String, ExistingUser>()
    val permissions = linkedMapOf<Pair<String, String>, MigrationAction.SetPermissions>()
    val topicPermissions = linkedMapOf<Triple<String, String, String>, MigrationAction.SetTopicPermissions>()

    /** `/` exists on every broker with its own settings (D5: exempt). */
    init { vhosts["/"] = ExistingVhost(description = "Default virtual host") }

    fun existingVhost(name: String, description: String = "", tags: List<String> = emptyList(), defaultQueueType: String? = null) { vhosts[name] = ExistingVhost(description, tags, defaultQueueType) }
    fun existingUser(name: String, hash: String, algorithm: String = "rabbit_password_hashing_sha256", tags: List<String> = emptyList()) { users[name] = ExistingUser(SecretValue.of(hash), algorithm, tags) }
    fun existingPermissions(vhost: String, user: String, configure: String, write: String, read: String) { permissions[vhost to user] = MigrationAction.SetPermissions(vhost, user, configure, write, read) }

    var failingActionIndex: Int? = null
    var failingActionName: String? = null
    var failureReason: String = "Action refused by broker"

    override suspend fun execute(action: MigrationAction): ActionResult {
        val currentIndex = executedActions.size
        executedActions += action

        if (failingActionIndex == currentIndex || action.actionName == failingActionName) {
            return ActionResult.Failed(failureReason)
        }

        // Apply changes to fake catalogs idempotently
        when (action) {
            is MigrationAction.DeclareQueue -> {
                queues[action.vhost to action.name] = action
                topologyCatalog?.addQueue(
                    QueueRow(
                        name = action.name,
                        vhost = action.vhost,
                        type = action.type,
                        readyMessages = 0,
                        unackedMessages = 0,
                        consumers = 0,
                        leader = "rabbit@node-1",
                        members = listOf("rabbit@node-1"),
                        online = listOf("rabbit@node-1"),
                        policy = null,
                        state = "running",
                    )
                )
            }

            is MigrationAction.DeleteQueue -> {
                topologyCatalog?.deleteQueue(action.name, action.vhost)
            }

            is MigrationAction.DeclareExchange -> {
                topologyCatalog?.addExchange(
                    ExchangeRow(
                        name = action.name,
                        vhost = action.vhost,
                        type = action.type,
                        durable = action.durable,
                        autoDelete = action.autoDelete,
                        internal = action.internal,
                    )
                )
            }

            is MigrationAction.DeleteExchange -> {
                topologyCatalog?.deleteExchange(action.name, action.vhost)
            }

            is MigrationAction.DeclareBinding -> {
                topologyCatalog?.addBinding(
                    BindingRow(
                        source = action.exchange,
                        vhost = action.vhost,
                        destination = action.queue,
                        destinationType = action.destinationType,
                        routingKey = action.routingKey,
                    )
                )
            }

            is MigrationAction.DeleteBinding -> {
                topologyCatalog?.deleteBinding(action.exchange, action.queue, action.routingKey, action.vhost)
            }

            is MigrationAction.DeclareStream -> {
                topologyCatalog?.addStream(action.name, action.vhost)
                streamCatalog?.addStream(FakeStreamCatalog.stream(action.name, vhost = action.vhost))
            }

            is MigrationAction.DeleteStream -> {
                topologyCatalog?.deleteQueue(action.name, action.vhost)
                streamCatalog?.deleteStream(action.name, action.vhost)
            }

            is MigrationAction.DeclarePolicy -> policies[action.vhost to action.name] = action
            is MigrationAction.DeclareOperatorPolicy -> operatorPolicies[action.vhost to action.name] = action
            is MigrationAction.DeletePolicy -> policies.remove(action.vhost to action.name)

            is MigrationAction.DeclareVhost -> return declareVhost(action)
            is MigrationAction.DeclareUser -> return declareUser(action)
            is MigrationAction.SetPermissions -> return setPermissions(action)
            is MigrationAction.SetTopicPermissions -> return setTopicPermissions(action)
        }

        return ActionResult.Success
    }

    private fun conflict(reason: String) = ActionResult.Failed(reason, FailureKind.Conflict)

    /** The default virtual host exists on every broker with its own settings: exempt (D5). Only the settings the action carries are compared. */
    private fun declareVhost(action: MigrationAction.DeclareVhost): ActionResult {
        if (action.name == "/") return ActionResult.Success
        val existing = vhosts[action.name]
        if (existing == null) {
            vhosts[action.name] = ExistingVhost(action.vhostDescription, action.tags, action.defaultQueueType)
            return ActionResult.Success
        }
        val what = when {
            action.vhostDescription.isNotEmpty() && action.vhostDescription != existing.description -> "a different description"
            action.tags.isNotEmpty() && action.tags.toSet() != existing.tags.toSet() -> "different tags"
            action.defaultQueueType != null && action.defaultQueueType != existing.defaultQueueType -> "a different default queue type"
            else -> return ActionResult.Success
        }
        return conflict("The virtual host ${action.name} exists with $what")
    }

    // The hash is only compared with `matches` and never put in a reason.
    private fun declareUser(action: MigrationAction.DeclareUser): ActionResult {
        val existing = users[action.name]
        if (existing == null) {
            users[action.name] = ExistingUser(action.passwordHash, action.hashingAlgorithm, action.tags)
            return ActionResult.Success
        }
        val what = when {
            !existing.hash.matches(action.passwordHash) -> "a different password hash"
            existing.tags.toSet() != action.tags.toSet() -> "different tags"
            else -> return ActionResult.Success
        }
        return conflict("The user ${action.name} exists with $what")
    }

    private fun setPermissions(action: MigrationAction.SetPermissions): ActionResult {
        val existing = permissions[action.vhost to action.user]
        if (existing == null) { permissions[action.vhost to action.user] = action; return ActionResult.Success }
        return if (existing == action) ActionResult.Success else conflict("The permissions of ${action.user} on ${action.vhost} exist with other patterns")
    }

    private fun setTopicPermissions(action: MigrationAction.SetTopicPermissions): ActionResult {
        val key = Triple(action.vhost, action.user, action.exchange)
        val existing = topicPermissions[key]
        if (existing == null) { topicPermissions[key] = action; return ActionResult.Success }
        return if (existing == action) ActionResult.Success else conflict("The topic permissions of ${action.user} on ${action.vhost} exist with other patterns")
    }

    fun clear() {
        executedActions.clear()
        queues.clear(); policies.clear(); operatorPolicies.clear()
        failingActionIndex = null
        failingActionName = null
        vhosts.keys.retainAll(setOf("/")); users.clear(); permissions.clear(); topicPermissions.clear()
    }
}
