package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import kotlinx.serialization.json.JsonObject

/**
 * Deletes ONE binding by the properties key the broker itself reports, never by a key computed here: (1) read the list of the bindings between
 * the source and the destination; (2) select those with the action's routing key and no arguments; (3) none is already gone (no DELETE), more
 * than one or a key that is not of the broker's quoting is "could not be identified" (no DELETE); (4) DELETE that one; (5) read the WHOLE list
 * back: the target must be gone and every other binding still there. If another one vanished the run fails and nothing is recreated.
 */
internal class BindingDelete(
    private val reader: ResourceReader,
    private val writer: MigrationWriter,
    private val readBack: ReadBack,
) {

    /** @throws PathNotAllowedException when the list has no allowed path; nothing is sent then. */
    suspend fun delete(action: MigrationAction.DeleteBinding): ActionResult {
        val reply = reader.read(action)
        if (reply !is ManagementReply.Response) return ExecutorReasons.forReply(reply)
        return when (reply.status) {
            NOT_FOUND -> ActionResult.Success
            OK -> deleteFrom(action, BindingList.entries(reply.body))
            else -> ExecutorReasons.forReply(reply)
        }
    }

    private suspend fun deleteFrom(action: MigrationAction.DeleteBinding, entries: List<JsonObject>?): ActionResult {
        if (entries == null) return ExecutorReasons.failed(ExecutorReasons.BINDING_NOT_IDENTIFIED)
        val target = BindingList.plain(entries, action.routingKey).let { if (it.isEmpty()) return ActionResult.Success else it.singleOrNull() }
        val key = target?.let(BindingList::propertiesKey)?.takeIf(ExecutorPathAllowList::isPropertiesKey)
            ?: return ExecutorReasons.failed(ExecutorReasons.BINDING_NOT_IDENTIFIED)
        val path = MigrationDeleteRequests.bindingDeletePath(action, key)
        return when (val written = writer.deleteBinding(path)) {
            WriteOutcome.Success -> readBack.verifyBindingRemoved(action, target, entries - target)
            WriteOutcome.BadRequest -> ExecutorReasons.failed(ExecutorReasons.REFUSED)
            is WriteOutcome.Failed -> ExecutorReasons.failed(written.reason)
        }
    }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
