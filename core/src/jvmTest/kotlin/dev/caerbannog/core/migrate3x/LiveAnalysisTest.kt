package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-02a: [analyseLive] turns a fetch into the four outcomes the Migrate from 3.x screen needs. */
class LiveAnalysisTest {

    private class Scripted(override val connectionName: String, private val result: FetchResult) : DefinitionsSource {
        override suspend fun fetch(): FetchResult = result
    }

    private val document = (DefinitionsParser.parse(
        DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("audit", "quorum"))),
    ) as DefinitionsParseResult.Parsed).document

    @Test
    fun `a fetched export is analysed with the connection as the label and the live facts`() = runTest {
        val facts = LiveSourceFacts("legacy-eu", "3.13.7", mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1), setOf("classic_queue_mirroring"))
        val outcome = assertIs<LiveAnalysisOutcome.Analysed>(analyseLive(Scripted("legacy-eu", FetchResult.Fetched(document, facts))))
        assertEquals("legacy-eu", outcome.held.fileLabel)
        assertEquals(CompatibilityAnalyzer.analyze(document, facts), outcome.held.report, "the report is the analyzer's, on the live facts")
        assertEquals("legacy-eu", outcome.held.report.source.name)
        assertTrue(outcome.held.report.source.live)
        assertTrue(
            outcome.held.report.findings.any { it.rule == "mirroring-in-use" && it.evidence == EvidenceSource.LiveDeprecatedFeatures },
            "mirroring is flagged from the deprecated feature list",
        )
    }

    @Test
    fun `not permitted offers the file import with the cannot export text`() = runTest {
        val outcome = analyseLive(Scripted("legacy-eu", FetchResult.NotPermitted(LiveSourceTexts.CANNOT_EXPORT)))
        assertEquals(LiveAnalysisOutcome.OfferFileImport(LiveSourceTexts.CANNOT_EXPORT), outcome)
    }

    @Test
    fun `unreachable names the connection`() = runTest {
        assertEquals(LiveAnalysisOutcome.Unreachable("legacy-eu"), analyseLive(Scripted("legacy-eu", FetchResult.Unreachable)))
        assertEquals("legacy-eu could not be reached", LiveSourceTexts.unreachable("legacy-eu"))
    }

    @Test
    fun `a failure keeps its fixed text and holds no report`() = runTest {
        assertEquals(LiveAnalysisOutcome.Failed(LiveSourceTexts.NOT_THREE_X), analyseLive(Scripted("new-eu", FetchResult.Failed(LiveSourceTexts.NOT_THREE_X))))
    }

    @Test
    fun `end to end over the fake reads for each way a live read can end`() = runTest {
        suspend fun run(version: String?, configure: FakeThreeXReads.() -> Unit = {}): LiveAnalysisOutcome {
            val reads = FakeThreeXReads().apply(configure)
            return analyseLive(LiveDefinitionsSource("legacy-eu", version, reads) { })
        }
        assertIs<LiveAnalysisOutcome.Analysed>(run("3.13.7"))
        assertIs<LiveAnalysisOutcome.OfferFileImport>(run("3.13.7") { definitions = ReadOutcome.NotPermitted })
        assertEquals(LiveAnalysisOutcome.Unreachable("legacy-eu"), run("3.13.7") { definitions = ReadOutcome.Unreachable })
        assertEquals(LiveAnalysisOutcome.Failed(LiveSourceTexts.VERSION_UNREADABLE), run(null))
        assertEquals(LiveAnalysisOutcome.Failed(LiveSourceTexts.NOT_THREE_X), run("4.1.0"))
        assertFalse(run("4.1.0") is LiveAnalysisOutcome.Analysed)
    }

    // ---- M8-02b carry-over F7: the CPU work runs on the dispatcher the caller passes (like the file view model's analysisDispatcher) ----

    private class CountingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        val dispatches = java.util.concurrent.atomic.AtomicInteger()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            block.run()
        }
    }

    @Test
    fun `the analysis runs on the dispatcher given by the caller`() = runTest {
        val dispatcher = CountingDispatcher()
        val facts = LiveSourceFacts("legacy-eu", "3.13.7")
        val outcome = analyseLive(Scripted("legacy-eu", FetchResult.Fetched(document, facts)), dispatcher)
        assertIs<LiveAnalysisOutcome.Analysed>(outcome)
        assertTrue(dispatcher.dispatches.get() >= 1, "the report must be built on the dispatcher passed in, but it was never used")
    }

    @Test
    fun `a failure needs no dispatcher and the default stays usable`() = runTest {
        val dispatcher = CountingDispatcher()
        assertEquals(LiveAnalysisOutcome.Unreachable("x"), analyseLive(Scripted("x", FetchResult.Unreachable), dispatcher))
        assertEquals(0, dispatcher.dispatches.get(), "no CPU work, no dispatch")
        assertIs<LiveAnalysisOutcome.Analysed>(analyseLive(Scripted("legacy-eu", FetchResult.Fetched(document, LiveSourceFacts("legacy-eu", "3.13.7")))))
    }
}
