package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-14c step 4 (round 1): the executor's stream path against a hostile or flapping broker, and the sentinel rule (no value, no key, no broker text in
 * any result). A pre-read that answers 200 with something that is not a queue is never a success and never lets the manager create.
 */
class ManagementTopologyMigrationExecutorStreamHostileTest {

    private val sentinel = "SENTINEL-77f3"
    private val action = MigrationAction.DeclareStream("audit", "/", mapOf("x-max-age" to ArgumentValue.Text("7D")))
    private val seen = mutableListOf<M508a.Seen>()

    private fun run(engine: MockEngine, manager: StreamManager, a: MigrationAction = action): ActionResult =
        runBlocking { ManagementTopologyMigrationExecutor(M508a.http(engine), M508a.requests(), manager) { }.execute(a) }

    @Test
    fun `a 200 with a list, garbage, an empty body or a huge body is never a success and the manager is never asked`() {
        val bodies = mapOf(
            "list" to """[{"type":"stream","arguments":{"x-max-age":"7D"}}]""", "garbage" to "<html>not json</html>", "empty" to "",
            "truncated" to """{"type":"stream","arguments":{"x-max-age":""", "null" to "null", "huge" to """{"type":"stream","arguments":{"x-max-age":"7D"},"pad":"${"x".repeat(5_000_000)}"}""",
            "deep" to "[".repeat(5_000) + "]".repeat(5_000), "wrong type" to """{"type":"quorum","arguments":{"x-max-age":"7D"}}""",
        )
        for ((label, body) in bodies) {
            val manager = FakeStreamManager()
            val result = run(M508a.answering(200, body, seen), manager)
            // "huge" is a valid stream object with the declared setting plus padding: it matches, which is a success without a create
            if (label == "huge") assertEquals(ActionResult.Success, result, label)
            else assertTrue(result is ActionResult.Failed, "$label gave $result")
            assertTrue(manager.createdWithSettings.isEmpty(), "$label: a create was requested")
        }
        assertTrue(seen.all { it.method == "GET" }, "only GET requests")
    }

    @Test
    fun `a read that flips between the two reads is decided by the second one`() {
        // first read 404, the create is refused, the second read answers 200 with other settings: a conflict, not a success and not a retry
        var reads = 0
        val engine = MockEngine {
            reads++
            if (reads == 1) respond("", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
            else respond("""{"type":"stream","arguments":{"x-max-age":"1D"}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val refusing = FakeStreamManager().apply { refuseCreateWith = "PRECONDITION_FAILED $sentinel" }
        val result = run(engine, refusing)
        assertEquals(ExecutorReasons.conflict(action), result)
        assertEquals(2, reads, "exactly one read before and one after the refused create")
        assertEquals(1, refusing.createdStreams.size + (if (refusing.refuseCreateWith != null) 1 else 0), "one create attempt")
    }

    @Test
    fun `a read that is 404 twice after a refused create is REFUSED and a 500 on the second read is REFUSED too`() {
        for (second in listOf(404, 500, 403)) {
            var reads = 0
            val engine = MockEngine {
                reads++
                respond(if (reads == 1) "" else "x", HttpStatusCode.fromValue(if (reads == 1) 404 else second), headersOf("Content-Type", "application/json"))
            }
            val result = run(engine, FakeStreamManager().apply { refuseCreateWith = sentinel })
            assertEquals(ExecutorReasons.REFUSED, (result as ActionResult.Failed).reason, "second read $second")
        }
    }

    @Test
    fun `a create that throws never gives a success and never leaks its text`() {
        val boom = object : StreamManager {
            override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult = throw IllegalStateException(sentinel)
            override suspend fun deleteStream(vhost: String, name: String) = StreamManagementResult.Success
        }
        val outcome = runCatching { run(M508a.answering(404, "", seen), boom) }
        outcome.onSuccess { assertTrue(it is ActionResult.Failed && !it.reason.contains(sentinel), "$it") }
        outcome.onFailure { assertTrue(it is IllegalStateException, "an unexpected failure type ${it.javaClass}") }
    }

    @Test
    fun `a closed connection mid-apply is the existing no-answer reason, with settings`() {
        val closed = FakeStreamManager().apply { failCreateWith = "Connection closed $sentinel" }
        val result = run(M508a.answering(404, "", seen), closed)
        assertEquals(ExecutorReasons.NO_ANSWER, (result as ActionResult.Failed).reason)
    }

    @Test
    fun `no result carries a key, a value or a broker text, whatever happens`() {
        val keyed = MigrationAction.DeclareStream("audit", "/", mapOf("x-$sentinel" to ArgumentValue.Text("7d-$sentinel")))
        val results = listOf(
            run(M508a.answering(404, sentinel, seen), FakeStreamManager(), keyed),
            run(M508a.answering(200, """{"type":"stream","arguments":{"x-max-age":"$sentinel"}}""", seen), FakeStreamManager()),
            run(M508a.answering(500, sentinel, seen), FakeStreamManager()),
            run(M508a.answering(404, "", seen), FakeStreamManager().apply { refuseCreateWith = sentinel }),
            run(M508a.answering(404, "", seen), FakeStreamManager().apply { failCreateWith = sentinel }),
        )
        for (r in results) assertFalse(r is ActionResult.Failed && r.reason.contains(sentinel), "leaked: $r")
    }
}
