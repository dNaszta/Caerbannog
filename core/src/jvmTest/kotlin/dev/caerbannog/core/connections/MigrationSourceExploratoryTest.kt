package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.FetchResult
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.STORAGE_VERSION_READS
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-02a step 4 (test developer): exploratory checks through the real ActiveConnection, session and LiveDefinitionsSource over the fakes. */
class MigrationSourceExploratoryTest {

    private val legacy = ConnectionProfile(name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc", topologyControlLevel = TopologyControlLevel.FULL_CONTROL)
    private val pauses: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private class Rig(pause: suspend (Long) -> Unit) {
        val gateway = FakeConnectionGateway()
        val checker = LoggingPermissionChecker(gateway.log)
        val session = ConnectionSession(checker, MapSecrets(mapOf("legacy-eu" to PASSWORD, "uat-eu" to PASSWORD)))
        val active = ActiveConnection(session, gateway, pause)
    }

    private fun rig() = Rig { pauses += it }

    @Test
    fun `the real retry bound through the connection gives 6 reads and 5 pauses of one second`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        r.gateway.threeXReadsOf("legacy-eu").run {
            definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
            storageVersionNeverAppears("/", "orders")
        }
        r.active.connect(legacy)
        val result = assertIs<FetchResult.Fetched>(r.active.source!!.definitionsSource.fetch())
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(STORAGE_VERSION_READS, r.gateway.threeXReadsOf("legacy-eu").storageReads("/"))
        assertEquals(List(5) { 1_000L }, pauses.toList())
    }

    @Test
    fun `a connect to another profile in the middle of the scan stops the reads and the fetch still ends`() = runBlocking<Unit> {
        lateinit var rig: Rig
        var superseded = false
        rig = Rig { if (!superseded) { superseded = true; rig.active.connect(ConnectionProfile(name = "uat-eu", host = "u", virtualHost = "/", username = "svc")) } }
        rig.gateway.brokerRuns("legacy-eu", "3.13.7")
        rig.gateway.threeXReadsOf("legacy-eu").run {
            definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
            storageVersionNeverAppears("/", "orders")
        }
        rig.active.connect(legacy)
        val source = rig.active.source!!.definitionsSource
        val result = assertIs<FetchResult.Fetched>(source.fetch())
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(1, rig.gateway.threeXReadsOf("legacy-eu").storageReads("/"), "no read reaches the broker after the connection was superseded")
        assertEquals(LinkState.Connected("uat-eu"), rig.active.state.value)
    }

    @Test
    fun `unparsable versions are not a source and are refused on every path`() = runBlocking<Unit> {
        for (version in listOf("", "x", "v3.13", "3.13 x", "-3", "NaN", ".3")) {
            val r = rig()
            r.gateway.brokerRuns("legacy-eu", version)
            r.active.connect(legacy)
            assertFalse(r.session.permissions("legacy-eu") is PermissionCheckResult.MigrationSource, "version [$version] marked as a source")
            // brokerRuns with a version that does not parse as 3.x must also not look like a 3.x read connection
            assertEquals(emptyList(), r.gateway.threeXReadsOf("legacy-eu").calls)
        }
    }

    @Test
    fun `an error text from a hostile broker never reaches a fetch result`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        val hostile = "SENTINEL-HOSTILE <script>alert(1)</script> password=$PASSWORD"
        for (outcome in listOf<ReadOutcome<String>>(ReadOutcome.Failed(hostile))) {
            r.gateway.threeXReadsOf("legacy-eu").definitions = outcome
            r.active.connect(legacy)
            val text = r.active.source!!.definitionsSource.fetch().toString() + r.session.state.value + r.active.state.value + r.gateway.log
            assertFalse("SENTINEL" in text, text)
            assertFalse(PASSWORD in text)
        }
    }

    // F1 (round 1): an odd version text is "unknown" everywhere, so it never reaches a banner, a pill or a report.
    @Test
    fun `a multi-line or oversized version is not a source and never reaches the banner`() = runBlocking<Unit> {
        for (version in listOf("3 anything\nsecond line", "3.13.7\nINJECTED LINE" + "x".repeat(5000), "3." + "1".repeat(31))) {
            val r = rig()
            r.gateway.brokerRuns("legacy-eu", version)
            r.active.connect(legacy)
            val status = r.session.state.value.statuses["legacy-eu"]
            assertFalse((status as PermissionCheckStatus.Done).result is PermissionCheckResult.MigrationSource, "marked: [${version.take(20)}]")
            val banner = permissionBanner(legacy, status)
            assertFalse('\n' in banner.headline + banner.detail.orEmpty(), "line break in the banner")
            assertTrue(banner.headline.length < 200)
            assertTrue(permissionPill(status).text != "RabbitMQ 3.x: migration source only", "an ordinary pill shows")
            assertNull(r.active.source!!.brokerVersion, "the source carries no odd version text")
            assertEquals(FetchResult.Failed(dev.caerbannog.core.migrate3x.LiveSourceTexts.VERSION_UNREADABLE), r.active.source!!.definitionsSource.fetch())
            assertEquals(emptyList(), r.gateway.threeXReadsOf("legacy-eu").calls)
        }
    }

    // F2 (round 1): the session says "migration source" at the very first Connected value, never a moment later.
    @Test
    fun `at the moment the link becomes Connected the connection is already locked`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        val seenAtConnected = Collections.synchronizedList(ArrayList<PermissionCheckResult?>())
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            // Unconfined: runs inside the write of the state, so it sees the session exactly as it is at the first Connected value.
            r.active.state.collect { if (it is LinkState.Connected && seenAtConnected.isEmpty()) seenAtConnected += r.session.permissions("legacy-eu") }
        }
        r.active.connect(legacy)
        job.cancel()
        assertEquals(1, seenAtConnected.size, "Connected was never observed")
        assertTrue(seenAtConnected.single() is PermissionCheckResult.MigrationSource, "a gate reading at the first Connected saw: $seenAtConnected")
    }

    // F3 (round 1): a connect superseded while its open is still waiting never marks, even when the newer connect of the same name is a 4.x broker.
    @Test
    fun `a superseded connect does not mark and the newer 4x check result stays`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        val gate = r.gateway.holdOpens("legacy-eu")
        val first = async(start = CoroutineStart.UNDISPATCHED) { r.active.connect(legacy) }
        r.gateway.awaitOpenStarted()
        r.gateway.brokerRuns("legacy-eu", "4.1.0") // the broker behind the name is now a 4.x one; the first open already asked its overview
        val second = async(start = CoroutineStart.UNDISPATCHED) { r.active.connect(legacy) }
        r.gateway.awaitOpenStarted()
        gate.release()
        first.await()
        second.await()
        assertEquals(LinkState.Connected("legacy-eu"), r.active.state.value)
        assertEquals(PermissionCheckResult.Checked(true, true), r.session.permissions("legacy-eu"), "the 4.x check result is the final status")
        assertEquals("4.1.0", r.active.source!!.brokerVersion)
    }

    @Test
    fun `no password in any observable text of a 3x connection`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        r.active.connect(legacy)
        r.active.source!!.definitionsSource.fetch()
        val text = listOf(r.session.state.value, r.active.state.value, r.gateway.log.toList(), r.gateway.everOpened, r.active.source, r.gateway.openedProfiles).joinToString()
        assertFalse(PASSWORD in text)
        assertNull(r.checker.calls.firstOrNull { it.second != PASSWORD }, "the checker got the stored password")
        assertNotNull(r.session.permissions("legacy-eu"))
    }
}
