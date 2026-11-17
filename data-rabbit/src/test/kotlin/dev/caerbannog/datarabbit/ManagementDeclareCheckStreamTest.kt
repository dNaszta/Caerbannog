package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M9-14c step 2b: `ManagementDeclareCheck` for a stream with settings. `DeclareStream` moves out of the shared arm of the policies into its own:
 * absent = `Absent`, present and `DeclaredResourceMatcher.matches` = `Same`, present and not = `Different`, over the SAME single GET of the
 * queue (no new request). `HonouredArgumentRule` rules 3 and 4 are NOT applied to a stream (the executor decides a stream by its own pre-read, never
 * by a repeated create): a broker `balanced` the step did not declare is ignored. Retention set by a POLICY is not in `arguments`: a step that
 * declares `x-max-age` against such a stream is `Different` (the check does not read `effective_policy_definition`, apply and check agree).
 */
class ManagementDeclareCheckStreamTest {

    private val broker = FakeManagementBroker()
    private val seen = mutableListOf<M508a.Seen>()
    private fun find(action: MigrationAction): BrokerFinding =
        runBlocking { ManagementDeclareCheck(ResourceReader(M508a.http(M508a.engineOver(broker, seen)), M508a.requests())).find(action) }
    private fun wire() = seen.map { it.method + " " + it.path }

    private fun stream(vararg arguments: Pair<String, ArgumentValue>) = MigrationAction.DeclareStream("audit", "/", arguments.toMap())
    private fun held(vararg arguments: Pair<String, JsonPrimitive>) { broker.addQueue("/", "audit", type = "stream", arguments = arguments.toMap()) }

    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val locator = "x-queue-leader-locator" to ArgumentValue.Text("balanced")

    @Test
    fun `a stream that is not on the broker is absent, with one GET of the queue and nothing else`() {
        assertEquals(BrokerFinding.Absent, find(stream(age)))
        assertEquals(listOf("GET /api/queues/%2F/audit"), wire())
    }

    @Test
    fun `a stream with the declared settings is the same, with one GET`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced"))
        assertEquals(BrokerFinding.Same, find(stream(age, locator)))
        assertEquals(listOf("GET /api/queues/%2F/audit"), wire())
    }

    @Test
    fun `a stream with another retention, a missing retention or another locator is different`() {
        held("x-max-age" to JsonPrimitive("1D"))
        assertEquals(BrokerFinding.Different, find(stream(age)))
        broker.addQueue("/", "bare", type = "stream")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareStream("bare", "/", mapOf(age))))
        broker.addQueue("/", "local", type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("client-local")))
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareStream("local", "/", mapOf(locator))))
    }

    @Test
    fun `a type difference is different, a number against the text`() {
        broker.addQueue("/", "audit", type = "stream", arguments = mapOf("x-max-length-bytes" to JsonPrimitive("1000")))
        assertEquals(BrokerFinding.Different, find(stream("x-max-length-bytes" to ArgumentValue.Whole(1000))))
    }

    @Test
    fun `a stream with no declared settings is the same whatever the broker holds, and a non-stream is different`() {
        held("x-max-age" to JsonPrimitive("1D"), "x-queue-leader-locator" to JsonPrimitive("client-local"))
        assertEquals(BrokerFinding.Same, find(stream()))
        broker.addQueue("/", "q", type = "quorum")
        assertEquals(BrokerFinding.Different, find(MigrationAction.DeclareStream("q")))
    }

    @Test
    fun `a balanced the step did not declare is ignored, rules 3 and 4 of the queue check are not applied`() {
        held("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced"), "x-max-length-bytes" to JsonPrimitive(5))
        assertEquals(BrokerFinding.Same, find(stream(age)), "extra settings on the broker are not a difference for a stream")
    }

    @Test
    fun `retention set by a policy is different from a declared argument, the policy is not read`() {
        held("x-queue-leader-locator" to JsonPrimitive("balanced"))
        broker.addPolicy("/", "retain", "^audit$", mapOf("max-age" to JsonPrimitive("7D")), applyTo = "streams")
        assertEquals(BrokerFinding.Different, find(stream(age)))
        assertEquals(listOf("GET /api/queues/%2F/audit"), wire(), "no policy request, no effective_policy_definition read")
    }

    @Test
    fun `the check of a stream never writes and never reads a path other than the queue`() {
        held("x-max-age" to JsonPrimitive("7D"))
        find(stream(age)); find(stream()); find(MigrationAction.DeclareStream("ghost", "/", mapOf(age)))
        assertEquals(true, seen.all { it.method == "GET" && it.path.startsWith("/api/queues/%2F/") }, wire().toString())
    }
}
