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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
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
        for (version in listOf("", "x", "v3.13", " 3.13", "-3", "NaN", ".3")) {
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

    // Finding F1: the broker version text is shown in the banner headline and kept in the session as the broker wrote it.
    @Disabled("finding F1 (minor): an unsanitised broker version text reaches the banner; enable when the rule is decided")
    @Test
    fun `a hostile version text does not reach the banner with control characters or unbounded length`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7\nINJECTED LINE" + "x".repeat(5000))
        r.active.connect(legacy)
        val banner = permissionBanner(legacy, r.session.state.value.statuses["legacy-eu"])
        assertFalse('\n' in banner.headline, "control character in the headline")
        assertTrue(banner.headline.length < 200, "headline length ${banner.headline.length}")
    }

    // Finding F2 (major): the link is Connected for a moment before the session says "migration source" (mark runs after the Connected write).
    @Disabled("finding F2: enable when ActiveConnection marks before it publishes Connected")
    @Test
    fun `at the moment the link becomes Connected the connection is already locked`() = runBlocking<Unit> {
        val r = rig()
        r.gateway.brokerRuns("legacy-eu", "3.13.7")
        val seenAtConnected = Collections.synchronizedList(ArrayList<PermissionCheckResult?>())
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            r.active.state.collect { if (it is LinkState.Connected) seenAtConnected += r.session.permissions("legacy-eu") }
        }
        r.active.connect(legacy)
        job.cancel()
        assertTrue(seenAtConnected.isNotEmpty())
        assertTrue(seenAtConnected.all { it is PermissionCheckResult.MigrationSource }, "a gate reading at Connected saw: $seenAtConnected")
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
