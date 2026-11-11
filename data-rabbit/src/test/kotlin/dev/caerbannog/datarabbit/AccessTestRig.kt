package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.UnavailableStreamManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.opentest4j.AssertionFailedError
import java.io.IOException

/** M8-12c: the REAL executor over a Ktor MockEngine whose replies are scripted by (method, encoded path). Records every request. */
internal class AccessRig(private val script: (String, String) -> Pair<Int, String>?) {
    val seen = mutableListOf<M508a.Seen>()

    private val engine = MockEngine { request ->
        val body = String(request.body.toByteArray())
        seen += M508a.Seen(request.method.value, request.url.encodedPath, request.headers["Authorization"], request.headers["Content-Type"] ?: request.body.contentType?.toString(), body)
        val reply = script(request.method.value, request.url.encodedPath) ?: (404 to "")
        if (reply.first < 0) throw IOException("refused")
        respond(reply.second, HttpStatusCode.fromValue(reply.first), headersOf("Content-Type", "application/json"))
    }

    private val executor = ManagementTopologyMigrationExecutor.overClient(HttpClient(engine), "http://broker.example:15672", "svc", "pw", UnavailableStreamManager) { }

    /** Runs one action; a thrown exception is reported as a failed assertion with its class (a stub must not read as an error). */
    fun run(action: MigrationAction): ActionResult = try {
        runBlocking { withTimeout(10_000) { executor.execute(action) } }
    } catch (e: Throwable) {
        throw AssertionFailedError("the executor threw ${e::class.simpleName} for $action")
    }

    val calls: List<String> get() = seen.map { "${it.method} ${it.path}" }
    val writes: List<M508a.Seen> get() = seen.filter { it.method != "GET" }

    companion object {
        fun conflictReason(result: ActionResult): String {
            val failed = result as? ActionResult.Failed ?: throw AssertionFailedError("expected a Conflict but got $result")
            if (failed.kind != FailureKind.Conflict) throw AssertionFailedError("expected a Conflict but got $failed")
            return failed.reason
        }
    }
}
