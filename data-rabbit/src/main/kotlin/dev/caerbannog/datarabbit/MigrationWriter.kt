package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction

/**
 * Sends the ONE request of a non-stream action (PUT, POST or DELETE, through the path allow-list) and maps the status to success or a fixed
 * reason ([ExecutorReasons]); it sends nothing else: no read-back, no policy read. The only class, with the executor, that calls
 * `ManagementHttp.put` and `delete` (guard test). Status table: 2xx success; 404 on a DELETE success (already gone); 400 with a
 * `PRECONDITION_FAILED` reason is the conflict; 401 and 403 not allowed; 5xx and silence no answer; anything else a refusal.
 */
/**
 * M5-08a R1 STUB (test developer): what the writer says about the ONE request it sent. [BadRequest] is a 400 on a queue or exchange declare:
 * the writer does NOT decide what it means (the executor reads the resource and decides; no broker text is ever matched). [Failed] carries a fixed reason.
 */
internal sealed interface WriteOutcome {
    data object Success : WriteOutcome
    data object BadRequest : WriteOutcome
    data class Failed(val reason: String) : WriteOutcome
}

internal class MigrationWriter(private val http: ManagementHttp, private val requests: ManagementRequests) {

    /** @throws PathNotAllowedException when the request is not of an allowed shape; nothing is sent then. */
    suspend fun write(action: MigrationAction): WriteOutcome { // M5-08a R1 STUB: still decides by text
        val request = MigrationRequests.requestFor(action) ?: MigrationDeleteRequests.requestFor(action)
            ?: return WriteOutcome.Failed(ExecutorReasons.NOT_A_REQUEST)
        val url = requests.absolute(ExecutorPathAllowList.checked(request.method, request.path))
        val reply = when (request.method) {
            "PUT" -> http.put(url, request.body.toString())
            "POST" -> http.post(url, request.body.toString())
            else -> http.delete(url)
        }
        return outcome(action, request.method, reply)
    }

    private fun outcome(action: MigrationAction, method: String, reply: ManagementReply): WriteOutcome {
        if (reply !is ManagementReply.Response) return WriteOutcome.Failed(ExecutorReasons.forReply(reply).reason)
        return when {
            reply.status in SUCCESS_RANGE -> WriteOutcome.Success
            reply.status == NOT_FOUND && method == "DELETE" -> WriteOutcome.Success
            reply.status == BAD_REQUEST && PRECONDITION_FAILED in reply.body -> WriteOutcome.Failed(ExecutorReasons.conflict(action).reason)
            else -> WriteOutcome.Failed(ExecutorReasons.forReply(reply).reason)
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val NOT_FOUND = 404
        const val BAD_REQUEST = 400
        const val PRECONDITION_FAILED = "PRECONDITION_FAILED"
    }
}
