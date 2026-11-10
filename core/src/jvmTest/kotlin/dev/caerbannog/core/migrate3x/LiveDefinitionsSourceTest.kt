package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M8-02a: the orchestration of a live 3.x read, without HTTP: order of the failures, what never fails the fetch, which queues are scanned
 * and the bounded retry. The pause is a recording no-op: the tests never sleep (and prove it with virtual time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDefinitionsSourceTest {

    private val reads = FakeThreeXReads()
    private val pauses: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private fun source(version: String? = "3.13.7") = LiveDefinitionsSource("legacy-eu", version, reads) { pauses += it }

    private fun export(vararg queues: Pair<String, String?>, vhost: String = "/") = DefinitionsFiles.file(
        queues = queues.map { (name, type) -> DefinitionsFiles.queue(name, type = type, vhost = vhost) },
        vhosts = listOf(vhost),
    )

    private fun twoVhostExport(a: List<Pair<String, String?>>, b: List<Pair<String, String?>>) = DefinitionsFiles.file(
        queues = a.map { DefinitionsFiles.queue(it.first, it.second, vhost = "/") } + b.map { DefinitionsFiles.queue(it.first, it.second, vhost = "other") },
        vhosts = listOf("/", "other"),
    )

    private suspend fun fetched(): FetchResult.Fetched = assertIs<FetchResult.Fetched>(source().fetch())

    // ---- (a) the version ----------------------------------------------------------------------------------------------------------

    @Test
    fun `an unknown version is refused before anything is read`() = runTest {
        for (version in listOf(null, "", "x", "v3.13")) {
            val outcome = source(version).fetch()
            assertEquals(FetchResult.Failed(LiveSourceTexts.VERSION_UNREADABLE), outcome, "version [$version]")
        }
        assertEquals(emptyList(), reads.calls, "an unknown version must not be read from")
    }

    @Test
    fun `a 4x or later version is refused before anything is read`() = runTest {
        for (version in listOf("4.0.0", "4", "10.1")) {
            assertEquals(FetchResult.Failed(LiveSourceTexts.NOT_THREE_X), source(version).fetch(), "version [$version]")
        }
        assertEquals(emptyList(), reads.calls, "a 4.x broker must not be read from by the live source")
    }

    @Test
    fun `the texts are the ones of the brief`() {
        assertEquals("The broker version could not be read, so it cannot be analysed", LiveSourceTexts.VERSION_UNREADABLE)
        assertEquals("Only a RabbitMQ 3.x connection can be analysed live", LiveSourceTexts.NOT_THREE_X)
        assertEquals("This user cannot export definitions: the administrator tag is needed", LiveSourceTexts.CANNOT_EXPORT)
        assertEquals("The broker could not return its definitions", LiveSourceTexts.READ_FAILED)
        assertEquals(6, STORAGE_VERSION_READS)
        assertEquals(1_000L, STORAGE_VERSION_PAUSE_MILLIS)
    }

    // ---- (b) the definitions ------------------------------------------------------------------------------------------------------

    @Test
    fun `a user without the administrator tag gets NotPermitted and nothing else is read`() = runTest {
        reads.definitions = ReadOutcome.NotPermitted
        assertEquals(FetchResult.NotPermitted(LiveSourceTexts.CANNOT_EXPORT), source().fetch())
        assertEquals(listOf("definitions"), reads.calls)
    }

    @Test
    fun `an unreachable broker gives Unreachable`() = runTest {
        reads.definitions = ReadOutcome.Unreachable
        assertEquals(FetchResult.Unreachable, source().fetch())
        assertEquals(listOf("definitions"), reads.calls)
    }

    @Test
    fun `another failure of the definitions read gives Failed and nothing else is read`() = runTest {
        reads.definitions = ReadOutcome.Failed("SENTINEL broker text: <html>500</html>")
        val outcome = source().fetch()
        assertEquals(FetchResult.Failed(LiveSourceTexts.READ_FAILED), outcome)
        assertFalse("SENTINEL" in outcome.toString(), "the adapter's reason is never passed on")
        assertEquals(listOf("definitions"), reads.calls)
    }

    @Test
    fun `an export over the size limit is refused before it is parsed`() = runTest {
        reads.definitions = ReadOutcome.Ok("x".repeat((MAX_DEFINITIONS_FILE_BYTES + 1).toInt()))
        assertEquals(FetchResult.Failed(LiveSourceTexts.EXPORT_TOO_LARGE), source().fetch())
        assertEquals(listOf("definitions"), reads.calls)
    }

    @Test
    fun `an export that is not usable gives Failed with a fixed text that holds none of it`() = runTest {
        val texts = listOf(
            "SENTINEL-NOT-JSON <html>",
            """["SENTINEL-ARRAY"]""",
            "{}",
            DefinitionsFiles.file(vhosts = null, queues = listOf(DefinitionsFiles.queue("SENTINEL-Q", vhost = null))), // per-virtual-host export
        )
        for (text in texts) {
            reads.definitions = ReadOutcome.Ok(text)
            val outcome = source().fetch()
            assertEquals(FetchResult.Failed(LiveSourceTexts.EXPORT_UNUSABLE), outcome, "text: ${text.take(30)}")
            assertFalse("SENTINEL" in outcome.toString(), "the reason must not hold the input")
        }
    }

    // ---- (d) the deprecated features -----------------------------------------------------------------------------------------------

    @Test
    fun `the deprecated features in use go into the facts`() = runTest {
        reads.deprecatedFeatures = ReadOutcome.Ok(setOf("classic_queue_mirroring", "transient_nonexcl_queues"))
        val result = fetched()
        assertEquals(setOf("classic_queue_mirroring", "transient_nonexcl_queues"), result.facts.deprecatedFeaturesInUse)
        assertEquals("legacy-eu", result.facts.connectionName)
        assertEquals("3.13.7", result.facts.brokerVersion)
    }

    @Test
    fun `a deprecated feature list that fails never fails the fetch`() = runTest {
        val failures = listOf<ReadOutcome<Set<String>>>(ReadOutcome.NotPermitted, ReadOutcome.Unreachable, ReadOutcome.Failed("fixed"))
        for (failure in failures) {
            reads.deprecatedFeatures = failure
            val result = fetched()
            assertEquals(emptySet(), result.facts.deprecatedFeaturesInUse, "after $failure")
        }
    }

    // ---- (e) the storage versions ---------------------------------------------------------------------------------------------------

    @Test
    fun `a version that is there at once is read once, with no pause`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null, "events" to null))
        reads.storageVersionAppearsAtRead("/", "orders", 1, version = 1)
        reads.storageVersionAppearsAtRead("/", "events", 1, version = 2)
        val result = fetched()
        assertEquals(mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1, QueueRef("/", "events") to ClassicIndexVersion.V2), result.facts.classicIndexVersions)
        assertEquals(1, reads.storageReads("/"))
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun `a version that appears at the second read is read again after one pause`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null))
        reads.storageVersionAppearsAtRead("/", "orders", 2, version = 1)
        val result = fetched()
        assertEquals(ClassicIndexVersion.V1, result.facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(2, reads.storageReads("/"))
        assertEquals(listOf(STORAGE_VERSION_PAUSE_MILLIS), pauses)
    }

    @Test
    fun `the last allowed read still counts`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null))
        reads.storageVersionAppearsAtRead("/", "orders", STORAGE_VERSION_READS, version = 2)
        assertEquals(ClassicIndexVersion.V2, fetched().facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(STORAGE_VERSION_READS, reads.storageReads("/"))
    }

    @Test
    fun `a version that never appears stops after 6 reads with 5 pauses and is Unknown`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null))
        reads.storageVersionNeverAppears("/", "orders")
        val result = fetched()
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(6, reads.storageReads("/"), "at most 6 reads per virtual host")
        assertEquals(List(5) { 1_000L }, pauses, "exactly 5 pauses of 1000 ms")
        assertEquals(0L, testScheduler.currentTime, "the source itself must never wait (the pause is injected)")
    }

    @Test
    fun `a queue the list does not mention at all is read again and ends Unknown`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("ghost" to null))
        val result = fetched()
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "ghost")])
        assertEquals(6, reads.storageReads("/"))
    }

    @Test
    fun `one slow queue keeps the vhost reading but a settled queue keeps its value`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("fast" to null, "slow" to null))
        reads.storageVersionAppearsAtRead("/", "fast", 1, version = 2)
        reads.storageVersionNeverAppears("/", "slow")
        val result = fetched()
        assertEquals(ClassicIndexVersion.V2, result.facts.classicIndexVersions[QueueRef("/", "fast")])
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "slow")])
        assertEquals(6, reads.storageReads("/"))
    }

    @Test
    fun `the bound is per virtual host`() = runTest {
        reads.definitions = ReadOutcome.Ok(twoVhostExport(listOf("a" to null), listOf("b" to null)))
        reads.storageVersionNeverAppears("/", "a")
        reads.storageVersionNeverAppears("other", "b")
        val result = fetched()
        assertEquals(6, reads.storageReads("/"))
        assertEquals(6, reads.storageReads("other"))
        assertEquals(10, pauses.size, "5 pauses per virtual host")
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("other", "b")])
    }

    @Test
    fun `any number other than 1 or 2 is Unknown and is not read again`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("odd" to null))
        reads.storageVersionAppearsAtRead("/", "odd", 1, version = 7)
        val result = fetched()
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "odd")])
        assertEquals(1, reads.storageReads("/"), "a number that is there is an answer")
    }

    @Test
    fun `a failing read gives Unknown for that vhost and never fails the fetch`() = runTest {
        for (failure in listOf<ReadOutcome<Map<String, Int?>>>(ReadOutcome.NotPermitted, ReadOutcome.Unreachable, ReadOutcome.Failed("fixed"))) {
            val r = FakeThreeXReads()
            r.definitions = ReadOutcome.Ok(twoVhostExport(listOf("a" to null), listOf("b" to null)))
            r.storageReadsFail("/", failure)
            r.storageVersionAppearsAtRead("other", "b", 1, version = 2)
            val result = assertIs<FetchResult.Fetched>(LiveDefinitionsSource("legacy-eu", "3.13.7", r) { }.fetch(), "after $failure")
            assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "a")], "after $failure")
            assertEquals(ClassicIndexVersion.V2, result.facts.classicIndexVersions[QueueRef("other", "b")], "the other vhost is still read")
            assertEquals(1, r.storageReads("/"), "a failing read ends the scan of that vhost at once, after $failure")
            assertEquals(0, pauses.size)
        }
    }

    @Test
    fun `a read that fails later ends the scan there, keeps what settled and leaves the rest Unknown`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("fast" to null, "slow" to null))
        reads.storageVersionAppearsAtRead("/", "fast", 1, version = 1)
        reads.storageVersionNeverAppears("/", "slow")
        reads.storageReadsFail("/", ReadOutcome.Unreachable, fromRead = 3)
        val result = fetched()
        assertEquals(ClassicIndexVersion.V1, result.facts.classicIndexVersions[QueueRef("/", "fast")])
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "slow")])
        assertEquals(3, reads.storageReads("/"), "no read after the failing one")
        assertEquals(2, pauses.size)
    }

    @Test
    fun `only classic queues are scanned and a vhost with none is not asked`() = runTest {
        reads.definitions = ReadOutcome.Ok(
            twoVhostExport(
                listOf("orders" to null, "audit" to "quorum"),
                listOf("log" to "stream", "weird" to "lazy-thing", "q2" to "quorum"),
            ),
        )
        reads.storageVersionAppearsAtRead("/", "orders", 1, version = 1)
        reads.trapQueue("/", "audit")
        reads.trapQueue("other", "log")
        reads.trapQueue("other", "weird")
        reads.trapQueue("other", "q2")
        val result = fetched()
        assertEquals(setOf(QueueRef("/", "orders")), result.facts.classicIndexVersions.keys, "no entry for a quorum, stream or unknown queue")
        assertEquals(0, reads.storageReads("other"), "a vhost without a classic queue is not asked")
        assertEquals(1, reads.storageReads("/"))
    }

    @Test
    fun `a queue with an explicit classic type is scanned like one without a type`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("a" to "classic", "b" to null))
        reads.storageVersionAppearsAtRead("/", "a", 1)
        reads.storageVersionAppearsAtRead("/", "b", 1)
        assertEquals(setOf(QueueRef("/", "a"), QueueRef("/", "b")), fetched().facts.classicIndexVersions.keys)
    }

    @Test
    fun `the document is the parsed export and the reads are only the three known ones`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null))
        reads.storageVersionAppearsAtRead("/", "orders", 1)
        val result = fetched()
        assertEquals(listOf(QueueRef("/", "orders")), result.document.queues.map { QueueRef(it.vhost, it.name) })
        assertEquals("3.13.7", result.document.sourceVersion)
        assertTrue(reads.calls.all { it == "definitions" || it == "deprecated-features" || it.startsWith("storage-versions:") }, "calls: ${reads.calls}")
        assertEquals(1, reads.definitionsReads, "the export is read once")
    }

    // ---- secrets -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a password hash of the export never shows in the result text`() = runTest {
        val sentinel = "SENTINEL-HASH-live-7f3a"
        reads.definitions = ReadOutcome.Ok(
            """{"rabbit_version":"3.13.7","vhosts":[{"name":"/"}],"users":[{"name":"svc","password_hash":"$sentinel","hashing_algorithm":"rabbit_password_hashing_sha256","tags":"administrator"}],"queues":[],"exchanges":[],"bindings":[],"policies":[],"parameters":[]}""",
        )
        val result = fetched()
        assertFalse(sentinel in result.toString(), "FetchResult.toString() leaks the hash")
        assertFalse(sentinel in result.document.toString())
        assertFalse(sentinel in CompatibilityReportText.render(CompatibilityAnalyzer.analyze(result.document, result.facts)), "report text leaks the hash")
        reads.definitions = ReadOutcome.Ok("""{"x":"$sentinel""")
        assertFalse(sentinel in source().fetch().toString(), "a Failed reason must not hold the input")
    }

    // ---- M8-02b carry-over F5: the source does not trust its caller for the version -------------------------------------------------

    @Test
    fun `an odd version text is unreadable, also when the caller did not clean it`() = runTest {
        // "3 anything\nx" starts with a 3, so a plain major parse would call it a 3.x broker and read from it.
        for (version in listOf("3 anything\nx", "3.13.7\nx", "3.13 beta", "3." + "1".repeat(40))) {
            assertEquals(FetchResult.Failed(LiveSourceTexts.VERSION_UNREADABLE), source(version).fetch(), "version [$version]")
        }
        assertEquals(emptyList(), reads.calls, "an odd version must not be read from")
    }

    @Test
    fun `the version of the facts is the displayable text, trimmed`() = runTest {
        reads.definitions = ReadOutcome.Ok(export("orders" to null))
        val facts = assertIs<FetchResult.Fetched>(source(" 3.13.7 ").fetch()).facts
        assertEquals("3.13.7", facts.brokerVersion)
        assertEquals("3.13.7-rc.1", assertIs<FetchResult.Fetched>(source("3.13.7-rc.1").fetch()).facts.brokerVersion)
    }
}
