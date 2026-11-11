package dev.caerbannog.datarabbit

import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-10: the read-only status reader over `GET /api/shovels`: the same mapping as the gateway, never an address, one read request. */
class ShovelStatusReaderTest {
    private fun list(status: Int, body: String, fail: Boolean = false): Pair<ShovelListOutcome, List<String>> {
        val rig = AccessRig { m, p -> if (fail) -1 to "" else if (m == "GET" && p == "/api/shovels") status to body else null }
        val reader = ManagementShovelStatusReader(M508a.http(rig.engineForReader()), M508a.requests())
        val outcome = try { kotlinx.coroutines.runBlocking { reader.list() } } catch (e: Throwable) { throw AssertionError("the reader threw ${e::class.simpleName}") }
        return outcome to rig.calls
    }

    private fun entry(name: String, state: String, extra: String = "") = """{"name":"$name","vhost":"/","type":"dynamic","state":"$state"$extra}"""
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")

    @Test
    fun `a listed shovel maps its state and counts, unlimited remaining is null`() {
        val (outcome, calls) = list(200, "[" + entry("caerbannog-migrate-orders", "running", ""","forwarded":40,"remaining":80""") + "," + entry("b", "starting", ""","remaining":"unlimited"""") + "]")
        assertEquals(
            ShovelListOutcome.Listed(listOf(ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80), ShovelStatus(ShovelRef("/", "b"), ShovelRunState.STARTING, null, null, null))),
            outcome,
        )
        assertEquals(listOf("GET /api/shovels"), calls, "one read request")
    }

    @Test
    fun `a terminated reason loses its quotes and any uri with credentials`() {
        val body = "[" + entry("a", "terminated", ""","reason":"\"failed to connect to amqp://mover:SENTINEL-PASSWORD@old:5672\"" """.trim()) + "]"
        val listed = list(200, body).first as? ShovelListOutcome.Listed ?: throw AssertionError("expected Listed but got ${list(200, body).first}")
        val shovel = listed.shovels.single()
        val reason = shovel.reason.orEmpty()
        assertTrue(reason.contains("failed to connect"), reason)
        assertFalse(reason.contains("SENTINEL-PASSWORD") || reason.contains("amqp://") || reason.startsWith("\""), reason)
    }

    @Test
    fun `an address field of the broker answer never reaches the result`() {
        val body = "[" + entry("a", "running", ""","src_uri":"amqp://u:SENTINEL-PASSWORD@old","dest_uri":"amqp://" """.trim()) + "]"
        val text = list(200, body).first.toString()
        assertFalse(text.contains("SENTINEL-PASSWORD") || text.contains("amqp://") || text.contains("uri"), text)
    }

    @Test
    fun `404 is plugins off, 401 and 403 are not permitted, silence is unreachable, other answers are a fixed failure`() {
        assertEquals(ShovelListOutcome.PluginsOff, list(404, "").first)
        assertEquals(ShovelListOutcome.NotPermitted, list(401, "").first)
        assertEquals(ShovelListOutcome.NotPermitted, list(403, "").first)
        assertEquals(ShovelListOutcome.Unreachable, list(0, "", fail = true).first)
        val failed = list(500, "BODY-SECRET amqp://u:p@h").first as? ShovelListOutcome.Failed ?: throw AssertionError("expected Failed")
        assertFalse(failed.reason.contains("BODY-SECRET") || failed.reason.contains("amqp://"), failed.reason)
        assertTrue(list(200, "not json").first is ShovelListOutcome.Failed)
    }
}
