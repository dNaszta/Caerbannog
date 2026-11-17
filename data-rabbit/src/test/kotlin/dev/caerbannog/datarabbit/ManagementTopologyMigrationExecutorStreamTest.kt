package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-14c step 2b: the executor's stream path. A `DeclareStream` WITH settings is READ FIRST (like a policy): 404 = create through the stream manager
 * with the semantic [StreamSettings], then read back; 200 and matching = Success with NO create; 200 and not matching = the existing conflict
 * ("exists with different settings") with NO create, no delete, nothing changed; an unreadable answer = the existing fixed reason. A `Refused` from
 * the manager after an ABSENT read is decided by FACTS: one more read, 404 = REFUSED, 200 and matching = Success (another process made the same
 * stream in the gap), 200 and not matching = conflict; never by the broker's text or code. A step WITHOUT settings keeps today's path exactly (no pre-read).
 * The apply never alters or deletes a stream and sends no `PUT` for one.
 */
class ManagementTopologyMigrationExecutorStreamTest {

    private val broker = FakeManagementBroker()
    private val delays = mutableListOf<Long>()
    private val seen = mutableListOf<M508a.Seen>()
    private val sentinel = "SENTINEL-broker-text-77c1"

    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val length = "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000)
    private val locator = "x-queue-leader-locator" to ArgumentValue.Text("balanced")
    private fun stream(vararg arguments: Pair<String, ArgumentValue>, name: String = "audit") = MigrationAction.DeclareStream(name, "/", arguments.toMap())

    private fun exec(action: MigrationAction, streams: StreamManager = broker.streamManager()): ActionResult =
        runBlocking { M508a.executor(broker, streams, delays, seen).execute(action) }
    private fun wire() = seen.map { it.method + " " + it.path }
    private val get = "GET /api/queues/%2F/audit"

    private fun held(vararg arguments: Pair<String, JsonPrimitive>) { broker.addQueue("/", "audit", type = "stream", arguments = arguments.toMap()) }

    // ---- absent: create with the settings, then read back ----

    @Test
    fun `an absent stream with settings is read, created with the settings as semantic values and read back`() {
        val manager = FakeStreamManager()
        val action = stream(age, length, locator)
        // the fake manager does not touch the broker, so make the stream appear as the real manager would
        val creating = object : StreamManager by manager {
            override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult {
                broker.addQueue(vhost, name, type = "stream", arguments = mapOf("x-max-age" to JsonPrimitive("7D"), "x-max-length-bytes" to JsonPrimitive(1_000_000_000L), "x-queue-leader-locator" to JsonPrimitive("balanced")))
                return manager.createStream(vhost, name, settings)
            }
        }
        assertEquals(ActionResult.Success, exec(action, creating))
        assertEquals(listOf(Triple("/", "audit", StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, leaderLocator = "balanced"))), manager.createdWithSettings)
        assertEquals(listOf(get, get), wire(), "one read before the create, one read-back; no PUT, no DELETE")
    }

    @Test
    fun `with the honest fake broker the stream ends with the declared settings and the default locator, as the real one`() {
        assertEquals(ActionResult.Success, exec(stream(age)))
        val args = broker.queueArguments("/", "audit")!!
        assertEquals(JsonPrimitive("7D"), args["x-max-age"], "text as written")
        assertEquals(JsonPrimitive("balanced"), args["x-queue-leader-locator"], "the broker adds the default (S-14c-5)")
        assertEquals(JsonPrimitive("stream"), args["x-queue-type"])
        assertTrue(broker.writes.isEmpty(), "a stream is never written over HTTP: ${broker.writes}")
        assertTrue(broker.deletes.isEmpty())
    }

    @Test
    fun `numbers reach the manager as numbers and the broker shows them as numbers`() {
        val manager = FakeStreamManager()
        exec(stream(length, "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(100_000_000)), manager)
        assertEquals(StreamSettings(maxLengthBytes = 1_000_000_000, segmentSizeBytes = 100_000_000), manager.createdWithSettings.single().third)
    }

    // ---- present and the same: no create ----

    @Test
    fun `a stream that exists with the same settings is a no-op with one read and no create request`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced"))
        val manager = FakeStreamManager()
        val before = broker.snapshot()
        assertEquals(ActionResult.Success, exec(stream(age), manager))
        assertEquals(emptyList<Triple<String, String, StreamSettings>>(), manager.createdWithSettings, "the stream manager must not be asked")
        assertEquals(emptyList<Pair<String, String>>(), manager.createdStreams)
        assertEquals(listOf(get), wire())
        assertEquals(before, broker.snapshot())
    }

    @Test
    fun `a balanced the step did not declare is no difference`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced"), "x-max-length-bytes" to JsonPrimitive(5))
        val manager = FakeStreamManager()
        assertEquals(ActionResult.Success, exec(stream(age), manager))
        assertTrue(manager.createdWithSettings.isEmpty())
    }

    @Test
    fun `a stream that exists with the same four settings is a no-op`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-max-length-bytes" to JsonPrimitive(1_000_000_000L), "x-queue-leader-locator" to JsonPrimitive("balanced"))
        val manager = FakeStreamManager()
        assertEquals(ActionResult.Success, exec(stream(age, length, locator), manager))
        assertTrue(manager.createdWithSettings.isEmpty())
    }

    // ---- present and different: the existing conflict, nothing created, deleted or changed ----

    @Test
    fun `a stream that exists with other retention is a conflict, the manager is not asked, nothing changes`() {
        held("x-max-age" to JsonPrimitive("1D"), "x-queue-leader-locator" to JsonPrimitive("balanced"))
        val manager = FakeStreamManager()
        val before = broker.snapshot()
        assertEquals(M508a.conflict("audit"), M508a.failedReason(exec(stream(age), manager)))
        assertTrue(manager.createdWithSettings.isEmpty() && manager.createdStreams.isEmpty() && manager.deletedStreams.isEmpty(), "no create, no delete")
        assertEquals(before, broker.snapshot())
        assertEquals(listOf(get), wire())
    }

    @Test
    fun `the conflict is the existing kind, so the apply stops with Conflict and not as a failure to try again`() {
        held("x-max-age" to JsonPrimitive("1D"))
        val result = exec(stream(age))
        assertTrue(result is ActionResult.Failed && result.kind == dev.caerbannog.core.migrations.FailureKind.Conflict, "got $result")
    }

    @Test
    fun `a stream with no retention, another locator, another number, or a text for a number is a conflict`() {
        broker.addQueue("/", "bare", type = "stream")
        assertEquals(M508a.conflict("bare"), M508a.failedReason(exec(stream(age, name = "bare"))))
        broker.addQueue("/", "local", type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("client-local")))
        assertEquals(M508a.conflict("local"), M508a.failedReason(exec(stream(locator, name = "local"))))
        broker.addQueue("/", "num", type = "stream", arguments = mapOf("x-max-length-bytes" to JsonPrimitive(5)))
        assertEquals(M508a.conflict("num"), M508a.failedReason(exec(stream(length, name = "num"))))
        broker.addQueue("/", "txt", type = "stream", arguments = mapOf("x-max-length-bytes" to JsonPrimitive("1000000000")))
        assertEquals(M508a.conflict("txt"), M508a.failedReason(exec(stream(length, name = "txt"))))
    }

    @Test
    fun `retention set by a policy is not an argument, so a declared one is a conflict and the policy is not touched`() {
        held("x-queue-leader-locator" to JsonPrimitive("balanced"))
        broker.addPolicy("/", "retain", "^audit$", mapOf("max-age" to JsonPrimitive("7D")), applyTo = "streams")
        val before = broker.snapshot()
        assertEquals(M508a.conflict("audit"), M508a.failedReason(exec(stream(age))))
        assertEquals(before, broker.snapshot())
        assertEquals(listOf(get), wire(), "the executor does not read the policy of the stream")
    }

    @Test
    fun `a name that is a quorum queue is a conflict for a stream with settings, not a success and not a create`() {
        broker.addQueue("/", "audit", type = "quorum")
        val manager = FakeStreamManager()
        assertEquals(M508a.conflict("audit"), M508a.failedReason(exec(stream(age), manager)))
        assertTrue(manager.createdWithSettings.isEmpty())
    }

    // ---- an unreadable pre-read ----

    private fun execOver(status: Int, manager: FakeStreamManager): ActionResult {
        val executor = ManagementTopologyMigrationExecutor(M508a.http(M508a.answering(status, "", seen)), M508a.requests(), manager) { }
        return runBlocking { executor.execute(stream(age)) }
    }

    @Test
    fun `an unreadable answer is the existing fixed reason and nothing is created`() {
        for ((status, reason) in listOf(401 to ExecutorReasons.NOT_ALLOWED, 403 to ExecutorReasons.NOT_ALLOWED, 500 to ExecutorReasons.NO_ANSWER, 503 to ExecutorReasons.NO_ANSWER, 418 to ExecutorReasons.REFUSED)) {
            val manager = FakeStreamManager()
            assertEquals(reason, M508a.failedReason(execOver(status, manager)), "status $status")
            assertTrue(manager.createdWithSettings.isEmpty(), "status $status created a stream")
        }
    }

    @Test
    fun `a broker that does not answer fails with the fixed text and the manager is not asked`() {
        broker.unreachable = true
        val manager = FakeStreamManager()
        assertEquals(ExecutorReasons.NO_ANSWER, M508a.failedReason(exec(stream(age), manager)))
        assertTrue(manager.createdWithSettings.isEmpty())
    }

    // ---- the race net: Refused after an absent read ----

    private fun refusing(beforeRefusing: () -> Unit) = object : StreamManager {
        override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult {
            beforeRefusing()
            return StreamManagementResult.Refused("Broker refused to create stream '$name': PRECONDITION_FAILED $sentinel")
        }
        override suspend fun deleteStream(vhost: String, name: String) = StreamManagementResult.Success
    }

    @Test
    fun `a refusal after an absent read and a stream that is still absent is REFUSED, whatever the broker's text says`() {
        val result = exec(stream(age), refusing { })
        assertEquals(ExecutorReasons.REFUSED, M508a.failedReason(result))
        assertFalse(M508a.failedReason(result).contains(sentinel))
        assertEquals(listOf(get, get), wire(), "one read before, one read to decide")
    }

    @Test
    fun `a refusal after an absent read and a stream that now exists with the same settings is Success, another process made it`() {
        val result = exec(stream(age), refusing { held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced")) })
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf(get, get), wire())
    }

    @Test
    fun `a refusal after an absent read and a stream that now exists with other settings is the conflict`() {
        val before = { held("x-max-age" to JsonPrimitive("1D"), "x-queue-leader-locator" to JsonPrimitive("balanced")) }
        val result = exec(stream(age), refusing(before))
        assertEquals(M508a.conflict("audit"), M508a.failedReason(result))
        assertTrue(broker.deletes.isEmpty() && broker.writes.isEmpty(), "nothing deleted or written")
    }

    @Test
    fun `the decision is by the second read, never by the code or the text of the refusal`() {
        for (text in listOf("STREAM_ALREADY_EXISTS", "PRECONDITION_FAILED 17", "something else entirely", "")) {
            val gone = object : StreamManager {
                override suspend fun createStream(vhost: String, name: String, settings: StreamSettings) = StreamManagementResult.Refused(text)
                override suspend fun deleteStream(vhost: String, name: String) = StreamManagementResult.Success
            }
            assertEquals(ExecutorReasons.REFUSED, M508a.failedReason(exec(stream(age), gone)), "text \"$text\" while the stream is absent")
        }
    }

    @Test
    fun `a Failed answer of the manager after an absent read is the existing no-answer reason`() {
        val manager = FakeStreamManager().apply { failCreateWith = "Stream management unavailable $sentinel" }
        val result = exec(stream(age), manager)
        assertEquals(ExecutorReasons.NO_ANSWER, M508a.failedReason(result))
        assertFalse(M508a.failedReason(result).contains(sentinel))
    }

    // ---- read-back compares the settings ----

    @Test
    fun `a manager that says success but made a stream without the settings does not pass the read-back`() {
        val ignoring = object : StreamManager {
            override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult {
                broker.addQueue(vhost, name, type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("balanced")))
                return StreamManagementResult.Success
            }
            override suspend fun deleteStream(vhost: String, name: String) = StreamManagementResult.Success
        }
        val result = exec(stream(age), ignoring)
        assertTrue(result is ActionResult.Failed, "a stream without the declared retention must not be reported as applied: $result")
    }

    @Test
    fun `a manager that says success but there is no stream fails the read-back as before`() {
        assertEquals("audit was not found after it was declared", M508a.failedReason(exec(stream(age), FakeStreamManager())))
    }

    // ---- a step without settings: today's path ----

    @Test
    fun `a step without settings sends exactly today's requests, no read before the create`() {
        val manager = FakeStreamManager()
        broker.addQueue("/", "audit", type = "stream") // what the real manager made, so the read-back finds it
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareStream("audit"), manager))
        assertEquals(listOf("/" to "audit"), manager.createdStreams)
        assertEquals(StreamSettings.NONE, manager.createdWithSettings.single().third)
        assertEquals(listOf(get), wire(), "only the read-back GET, as before M9-14c")
    }

    @Test
    fun `a step without settings on a stream that holds settings is refused by the broker, nothing is changed or deleted`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced"))
        val before = broker.snapshot()
        val result = exec(MigrationAction.DeclareStream("audit"))
        assertEquals(ExecutorReasons.REFUSED, M508a.failedReason(result), "S-14c-3 (c): a plain repeat on a stream that holds a retention is refused by the broker")
        assertEquals(before, broker.snapshot())
        assertTrue(broker.deletes.isEmpty() && broker.writes.isEmpty())
    }

    @Test
    fun `a step without settings on a stream without settings is still a no-op success`() {
        broker.addQueue("/", "audit", type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("balanced")))
        assertEquals(ActionResult.Success, exec(MigrationAction.DeclareStream("audit")))
    }

    // ---- hostile or impossible actions ----

    @Test
    fun `settings the rules refuse never reach the manager, for an action built without the parser`() {
        for (bad in listOf(
            stream("x-max-age" to ArgumentValue.Text("7d")), stream("x-max-length-bytes" to ArgumentValue.Whole(0)), stream("x-queue-type" to ArgumentValue.Text("quorum")),
            stream("x-queue-leader-locator" to ArgumentValue.Text("nearest")), stream(age, "x-foo" to ArgumentValue.Whole(1)),
        )) {
            val manager = FakeStreamManager()
            val result = exec(bad, manager)
            assertTrue(result is ActionResult.Failed, "got $result for ${bad.arguments}")
            assertTrue(manager.createdWithSettings.isEmpty() && manager.createdStreams.isEmpty(), "a refused setting reached the stream manager: ${bad.arguments}")
            assertTrue(broker.writes.isEmpty())
            assertFalse(M508a.failedReason(result).contains("7d"), "the value must not be echoed")
        }
    }

    @Test
    fun `a stream with settings never causes a PUT or a DELETE, whatever the outcome`() {
        exec(stream(age)); exec(stream(age)); exec(stream("x-max-age" to ArgumentValue.Text("1D")))
        assertTrue(seen.all { it.method == "GET" }, wire().toString())
    }

    @Test
    fun `a name that cannot stand in a path is refused before anything is sent, settings or not`() {
        val result = exec(MigrationAction.DeclareStream("", "/", mapOf(age)))
        assertEquals(ExecutorReasons.NOT_A_REQUEST, M508a.failedReason(result))
        assertTrue(seen.isEmpty(), wire().toString())
    }
}
