package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction

/**
 * What the writer says about the ONE request it sent. [BadRequest] is a 400 on a queue or exchange declare: the writer does not decide what it
 * means (the executor reads the resource and decides; no broker text is ever matched). [Failed] carries a fixed reason.
 */
internal sealed interface WriteOutcome {
    data object Success : WriteOutcome
    data object BadRequest : WriteOutcome
    data class Failed(val reason: String) : WriteOutcome
}

/**
 * Sends the ONE request of an action (PUT, POST or DELETE, through the path allow-list) and maps the status to an outcome with a fixed reason
 * ([ExecutorReasons]); it sends nothing else: no read-back, no policy read. The only class, with the executor, that calls
 * `ManagementHttp.put` and `delete` (guard test). Status table: 2xx success; 404 on a DELETE success (already gone); 400 on a queue or exchange
 * declare is [WriteOutcome.BadRequest]; 401 and 403 not allowed; 5xx and silence no answer; anything else a refusal. The body is never read.
 */
internal class MigrationWriter(private val http: ManagementHttp, private val requests: ManagementRequests) {

    /** @throws PathNotAllowedException when the request is not of an allowed shape; nothing is sent then. */
    suspend fun write(action: MigrationAction): WriteOutcome {
        val request = MigrationRequests.requestFor(action) ?: MigrationDeleteRequests.requestFor(action)
            ?: return WriteOutcome.Failed(ExecutorReasons.NOT_A_REQUEST)
        val body = request.body?.toString()
        val reply = send(request.method, request.path, body)
        return outcome(action, request.method, reply)
    }

    /** The DELETE of one binding, at [path] built from the key the broker reported (see [MigrationDeleteRequests.bindingDeletePath]). */
    suspend fun deleteBinding(path: String): WriteOutcome = outcome(null, "DELETE", send("DELETE", path, null))

    private suspend fun send(method: String, path: String, body: String?): ManagementReply {
        val url = requests.absolute(ExecutorPathAllowList.checked(method, path))
        return when (method) {
            "PUT" -> http.put(url, body.orEmpty())
            "POST" -> http.post(url, body.orEmpty())
            else -> http.delete(url)
        }
    }

    private fun outcome(action: MigrationAction?, method: String, reply: ManagementReply): WriteOutcome {
        if (reply !is ManagementReply.Response) return failed(reply)
        return when {
            reply.status in SUCCESS_RANGE -> WriteOutcome.Success
            reply.status == NOT_FOUND && method == "DELETE" -> WriteOutcome.Success
            reply.status == BAD_REQUEST && isQueueOrExchangeDeclare(action) -> WriteOutcome.BadRequest
            else -> failed(reply)
        }
    }

    private fun failed(reply: ManagementReply) = WriteOutcome.Failed(ExecutorReasons.forReply(reply).reason)

    private fun isQueueOrExchangeDeclare(action: MigrationAction?) =
        action is MigrationAction.DeclareQueue || action is MigrationAction.DeclareExchange

    private companion object {
        val SUCCESS_RANGE = 200..299
        const val NOT_FOUND = 404
        const val BAD_REQUEST = 400
    }
}
