package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonObject

/**
 * Verifies an action after it was sent, by reading the resource back with an allowed GET. A declare must be there as declared; a delete
 * must be gone. "Absent" (and, for a delete, "still there") is retried ONCE after [delayMillis] of [RETRY_DELAY_MILLIS], because a broker
 * may need a moment; a mismatch is final. The delay is injected so no test sleeps.
 */
internal class ReadBack(
    private val reader: ResourceReader,
    private val delayMillis: suspend (Long) -> Unit,
) {
    internal constructor(http: ManagementHttp, requests: ManagementRequests, delayMillis: suspend (Long) -> Unit) :
        this(ResourceReader(http, requests), delayMillis)

    /** What one look at the broker found: a final answer, or something that may still change. */
    private sealed interface Probe {
        data class Final(val result: ActionResult) : Probe
        data class Pending(val failure: ActionResult.Failed) : Probe
    }

    /** @throws PathNotAllowedException when the resource has no allowed path; nothing is sent then. */
    suspend fun verify(action: MigrationAction): ActionResult = settle { probe(action) }

    /**
     * After a binding DELETE: reads the WHOLE list. The [target] must be gone and every one of [siblings] still there; a sibling that vanished is
     * final (the run stops, nothing is recreated).
     */
    suspend fun verifyBindingRemoved(action: MigrationAction.DeleteBinding, target: JsonObject, siblings: List<JsonObject>): ActionResult =
        settle { probeBindingList(action, target, siblings) }

    /** Looks once; when the answer may still change, once more after the named delay. */
    private suspend fun settle(look: suspend () -> Probe): ActionResult {
        val first = look()
        if (first is Probe.Final) return first.result
        delayMillis(RETRY_DELAY_MILLIS)
        return when (val second = look()) {
            is Probe.Final -> second.result
            is Probe.Pending -> second.failure
        }
    }

    private suspend fun probeBindingList(action: MigrationAction.DeleteBinding, target: JsonObject, siblings: List<JsonObject>): Probe {
        val reply = reader.read(action)
        if (reply !is ManagementReply.Response) return Probe.Final(ExecutorReasons.forReply(reply))
        val remaining = when (reply.status) {
            OK -> BindingList.entries(reply.body) ?: return Probe.Final(ExecutorReasons.failed(ExecutorReasons.BINDING_NOT_IDENTIFIED))
            NOT_FOUND -> emptyList()
            else -> return Probe.Final(ExecutorReasons.forReply(reply))
        }
        return when {
            target in remaining -> Probe.Pending(ExecutorReasons.stillThereAfterDelete(action))
            !remaining.containsAll(siblings) -> Probe.Final(ExecutorReasons.failed(ExecutorReasons.OTHER_BINDING_REMOVED))
            else -> Probe.Final(ActionResult.Success)
        }
    }

    private suspend fun probe(action: MigrationAction): Probe {
        val reply = reader.read(action)
        if (reply !is ManagementReply.Response) return Probe.Final(ExecutorReasons.forReply(reply))
        return when {
            reply.status == OK -> found(action, reply.body)
            reply.status == NOT_FOUND -> absent(action)
            else -> Probe.Final(ExecutorReasons.forReply(reply))
        }
    }

    private fun found(action: MigrationAction, body: String): Probe = when (action) {
        is MigrationAction.DeleteBinding ->
            if (BindingList.entries(body)?.let { BindingList.plain(it, action.routingKey).isNotEmpty() } == true) Probe.Pending(ExecutorReasons.stillThereAfterDelete(action))
            else Probe.Final(ActionResult.Success)
        is MigrationAction.DeleteQueue, is MigrationAction.DeleteExchange, is MigrationAction.DeleteStream, is MigrationAction.DeletePolicy ->
            Probe.Pending(ExecutorReasons.stillThereAfterDelete(action))
        // A binding is its key and arguments: another one between the same two is a missing binding, not a changed one.
        is MigrationAction.DeclareBinding ->
            if (DeclaredResourceMatcher.matches(action, body)) Probe.Final(ActionResult.Success)
            else Probe.Pending(ExecutorReasons.notFoundAfterDeclare(action))
        else ->
            if (DeclaredResourceMatcher.matches(action, body)) Probe.Final(ActionResult.Success)
            else Probe.Final(ExecutorReasons.differentAfterDeclare(action))
    }

    private fun absent(action: MigrationAction): Probe =
        if (action.isDestructive) Probe.Final(ActionResult.Success) else Probe.Pending(ExecutorReasons.notFoundAfterDeclare(action))

    companion object {
        /** The named delay between the first read-back and its one retry. */
        const val RETRY_DELAY_MILLIS: Long = 500L

        private const val OK = 200
        private const val NOT_FOUND = 404
    }
}
