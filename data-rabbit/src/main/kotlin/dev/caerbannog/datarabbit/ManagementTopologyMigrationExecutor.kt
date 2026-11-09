package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import io.ktor.client.HttpClient

/**
 * Applies one migration action to a 4.x broker (the rerun table of `docs/tasks/M5-08-real-migration-execution.md`): every declare is idempotent,
 * an existing resource with other settings is a conflict that is reported and never overwritten, deleted or recreated, every action is read back,
 * and a delete happens only for a delete action. It never overwrites: a queue, exchange or binding conflict is the broker's own 400, a policy is
 * read and compared before its PUT. Streams go through the stream protocol ([streams]); the management API is only read for them.
 * Composition of [MigrationWriter] (send, map status), [ConflictCheck] (policy equality) and [ReadBack] (verify); every request passes
 * [ExecutorPathAllowList]. Failure texts are fixed ([ExecutorReasons]); nothing is logged.
 */
class ManagementTopologyMigrationExecutor internal constructor(
    http: ManagementHttp,
    requests: ManagementRequests,
    private val streams: StreamManager,
    readBackDelay: suspend (Long) -> Unit,
) : TopologyMigrationExecutor {

    private val writer = MigrationWriter(http, requests)
    private val reader = ResourceReader(http, requests)
    private val readBack = ReadBack(reader, readBackDelay)

    override suspend fun execute(action: MigrationAction): ActionResult = try {
        when (action) {
            is MigrationAction.DeclarePolicy, is MigrationAction.DeclareOperatorPolicy -> declarePolicy(action)
            is MigrationAction.DeclareStream -> streamOutcome(streams.createStream(action.vhost, action.name), action)
            is MigrationAction.DeleteStream -> streamOutcome(streams.deleteStream(action.vhost, action.name), action)
            else -> writeAndVerify(action)
        }
    } catch (_: PathNotAllowedException) {
        // A name that cannot stand in a path (an empty one, for example) is refused before anything is sent.
        ExecutorReasons.failed(ExecutorReasons.NOT_A_REQUEST)
    }

    private suspend fun writeAndVerify(action: MigrationAction): ActionResult =
        when (val written = writer.write(action)) {
            ActionResult.Success -> readBack.verify(action)
            is ActionResult.Failed -> written
        }

    /** The policy is read first: absent is created, equal is a no-op with no request, different is a conflict and is never written over. */
    private suspend fun declarePolicy(action: MigrationAction): ActionResult {
        val reply = reader.read(action)
        if (reply !is ManagementReply.Response) return ExecutorReasons.forReply(reply)
        return when (reply.status) {
            NOT_FOUND -> writeAndVerify(action)
            OK -> when (ConflictCheck.comparePolicy(reply.body, action)) {
                PolicyComparison.SAME -> ActionResult.Success
                PolicyComparison.DIFFERENT -> ExecutorReasons.conflict(action)
            }
            else -> ExecutorReasons.forReply(reply)
        }
    }

    /**
     * A stream is created or deleted by the stream manager; the typed answer decides, never its text. Created: read back as a queue of type
     * stream. Deleted: read back as gone. `NotFound` on a delete is a stream that is already gone: a no-op.
     */
    private suspend fun streamOutcome(result: StreamManagementResult, action: MigrationAction): ActionResult = when (result) {
        StreamManagementResult.Success -> readBack.verify(action)
        StreamManagementResult.NotFound ->
            if (action is MigrationAction.DeleteStream) ActionResult.Success else ExecutorReasons.failed(ExecutorReasons.REFUSED)
        is StreamManagementResult.Refused -> ExecutorReasons.failed(ExecutorReasons.REFUSED)
        is StreamManagementResult.Failed -> ExecutorReasons.failed(ExecutorReasons.NO_ANSWER)
    }

    companion object {
        /**
         * TEST-ONLY seam for code outside this module that cannot see `ManagementHttp` (the acceptance glue, which runs a Ktor mock engine behind
         * the REAL executor). [baseUrl] must be `http://host:port` (a plain, TLS-off profile, D9a). Production code never calls it: the gateway
         * builds the executor for the connection's own transport (a guard test fails if a production file calls this).
         */
        fun overClient(
            client: HttpClient,
            baseUrl: String,
            username: String,
            password: String,
            streams: StreamManager,
            readBackDelay: suspend (Long) -> Unit,
        ): ManagementTopologyMigrationExecutor = ManagementTopologyMigrationExecutor(
            ManagementHttp(client, username, password),
            ManagementRequests(baseUrl, BrokerTransport.Plain),
            streams,
            readBackDelay,
        )

        private const val OK = 200
        private const val NOT_FOUND = 404
    }
}
