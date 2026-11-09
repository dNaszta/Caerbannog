package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.streams.StreamManager
import io.ktor.client.HttpClient

/**
 * M5-08a COMPILE STUBS (test developer, step 2). DELETE THIS FILE at implementation; the real code replaces every declaration here.
 * Each body throws [NotImplementedError] or returns a wrong value, so the tests are red because the behaviour does not exist.
 * The signatures are the test developer's choice where the brief left them open (listed in the step 2 report for the architect).
 */

// ---- ManagementHttp.put and delete (members of ManagementHttp at implementation, built on one private send(block)) ----------------

internal suspend fun ManagementHttp.put(url: String, body: String): ManagementReply = throw NotImplementedError("M5-08a stub: ManagementHttp.put")

internal suspend fun ManagementHttp.delete(url: String): ManagementReply = throw NotImplementedError("M5-08a stub: ManagementHttp.delete")

// ---- MigrationWriter: sends the request of one NON-STREAM action (declare or delete) and maps the status ----------------------------

/**
 * `write(action)` sends exactly the request of the action: PUT for a queue, exchange, policy or operator policy, POST for a binding
 * (all from `MigrationRequests.requestFor`), DELETE for the delete actions (`MigrationDeleteRequests`, same encoding rules), and nothing
 * else: no GET, no read-back, no policy read (those are `ConflictCheck`, `ReadBack` and the executor). Status to outcome:
 * 2xx and (for a delete) 404 are [ActionResult.Success]; 400 with a `PRECONDITION_FAILED` reason is "<name> exists with different settings";
 * any other 400, 404 on a declare, 409 and other 3xx/4xx: "the broker refused the request"; 401 and 403: a reason containing "not allowed";
 * 5xx, timeouts, refused connections and TLS failures: a reason containing "the broker did not answer". The broker's body is never copied.
 */
internal class MigrationWriter(private val http: ManagementHttp, private val requests: ManagementRequests) {
    suspend fun write(action: MigrationAction): ActionResult = throw NotImplementedError("M5-08a stub: MigrationWriter.write")
}

// ---- ConflictCheck: policy equality -----------------------------------------------------------------------------------------------

internal enum class PolicyComparison { SAME, DIFFERENT }

internal object ConflictCheck {
    /**
     * [existingBody] is the broker's JSON for `GET /api/policies/{v}/{n}` (or operator-policies); [wanted] is a `DeclarePolicy` or
     * `DeclareOperatorPolicy`. SAME iff pattern, apply-to, priority and every definition entry are equal (numbers numerically, typed:
     * `5` equals `5.0`, never `"5"`; object key order irrelevant; the broker's extra keys and a missing key are DIFFERENT). A body that
     * is not a policy object is DIFFERENT (never overwrite what cannot be read).
     */
    fun comparePolicy(existingBody: String, wanted: MigrationAction): PolicyComparison = PolicyComparison.DIFFERENT
}

// ---- ReadBack: verify after each action --------------------------------------------------------------------------------------------

/**
 * `verify(action)` reads the resource back with GETs of the allowed collections and compares: a declare is verified present and equal
 * on type, durable, auto_delete, internal and every DECLARED argument (typed; arguments the broker adds are ignored); a stream is a queue
 * of type `stream` (GET queue detail); a binding is found in the list of source to destination; a delete is verified absent (GET 404).
 * Absent (or, for a delete, still present) is retried ONCE after `delayMillis(RETRY_DELAY_MILLIS)`; a mismatch is final, no retry.
 * Reasons: "<name> was not found after it was declared", "<name> was found with different settings after it was declared",
 * "<name> still exists after it was deleted" (the name only when short and plain), 401/403 "not allowed", no answer "the broker did not answer".
 */
internal class ReadBack(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val delayMillis: suspend (Long) -> Unit,
) {
    suspend fun verify(action: MigrationAction): ActionResult = throw NotImplementedError("M5-08a stub: ReadBack.verify")

    companion object {
        /** The named delay between the first read-back and its one retry. */
        const val RETRY_DELAY_MILLIS: Long = 0L
    }
}

// ---- the path allow-list -----------------------------------------------------------------------------------------------------------

/**
 * The only requests the executor may send (CLAUDE.md safety rule), checked on the raw encoded path without the base URL:
 *  - GET, PUT, POST, DELETE only; PUT and DELETE only where the action needs them (never anywhere else);
 *  - `/api/queues/{v}/{n}`, `/api/exchanges/{v}/{n}`, `/api/policies/{v}/{n}`, `/api/operator-policies/{v}/{n}` (exactly 4 segments:
 *    GET, PUT, DELETE), `/api/bindings/{v}/e/{src}/{q|e}/{dst}` (7 segments: GET, POST) and `.../{properties key}` (8 segments: DELETE);
 *  - no query string or fragment, no empty segment, no raw `.` or `..` segment. A segment named `get`, `contents` or `purge` is a NAME and is
 *    allowed in a name position; what is refused is any SUFFIX after a name (`.../orders/get`), which the exact shapes exclude.
 * The stub allows everything (wrong), so `ExecutorPathAllowListTest` is red.
 */
internal object ExecutorPathAllowList {
    fun isAllowed(method: String, path: String): Boolean = true
}

// ---- stream adapter mapping (ONE place that knows the client's "does not exist" failure) -----------------------------------------------

/**
 * Maps what the stream client threw on a DELETE to a typed result: a `StreamException` with response code 2 (STREAM_DOES_NOT_EXIST) or a
 * `StreamDoesNotExistException` is [dev.caerbannog.core.streams.StreamManagementResult.NotFound]; everything else is `Refused` with a text that
 * names the stream (as `RabbitMqStreamManager` does today). The mapping looks at the exception type and code, never at the message text.
 * `RabbitMqStreamManager.deleteStream` calls it. The stub answers Refused for everything (wrong).
 */
internal fun mapStreamDeleteFailure(name: String, failure: Exception): dev.caerbannog.core.streams.StreamManagementResult =
    dev.caerbannog.core.streams.StreamManagementResult.Refused("Broker refused to delete stream '$name': ${failure.message}")

// ---- the executor -------------------------------------------------------------------------------------------------------------------

/**
 * Composes [MigrationWriter], [ConflictCheck] and [ReadBack] per the umbrella table (docs/tasks/M5-08-real-migration-execution.md);
 * streams go through [streams]. `readBackDelay` is injected so no test sleeps (production: kotlinx `delay`).
 */
class ManagementTopologyMigrationExecutor internal constructor(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
    private val streams: StreamManager,
    private val readBackDelay: suspend (Long) -> Unit,
) : TopologyMigrationExecutor {

    override suspend fun execute(action: MigrationAction): ActionResult = throw NotImplementedError("M5-08a stub: ManagementTopologyMigrationExecutor.execute")

    companion object {
        /**
         * Seam for code outside this module that cannot see `ManagementHttp` (the acceptance glue, which runs a Ktor mock engine behind the
         * REAL executor). [baseUrl] must be `http://host:port` (a plain, TLS-off profile, D9a); the production wiring is the gateway's.
         */
        fun overClient(
            client: HttpClient,
            baseUrl: String,
            username: String,
            password: String,
            streams: StreamManager,
            readBackDelay: suspend (Long) -> Unit,
        ): ManagementTopologyMigrationExecutor = throw NotImplementedError("M5-08a stub: ManagementTopologyMigrationExecutor.overClient")
    }
}
