package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M8-06c round 1 (R1): the message-loss block of a LIVE report says that the broker reported the index version; the file text ("A definitions file cannot
 * tell the index version, so this list is every classic queue") is false for a live report. Real `analyseLive` over `FakeThreeXReads`.
 */
class LiveLossBlockTest {
    private suspend fun live(): ReportView {
        val reads = FakeThreeXReads().apply {
            definitions = ReadOutcome.Ok(
                DefinitionsFiles.file(queues = listOf("v1", "v2", "unreadable", "quorum-q").map { DefinitionsFiles.queue(it, type = if (it == "quorum-q") "quorum" else null) }),
            )
            storageVersionAppearsAtRead("/", "v1", 1, version = 1)
            storageVersionAppearsAtRead("/", "v2", 1, version = 2)
            storageVersionNeverAppears("/", "unreadable")
        }
        val held = assertIs<LiveAnalysisOutcome.Analysed>(analyseLive(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { })).held
        return ReportViewBuilder.build(held, liveConnectionName = "legacy-eu")
    }

    @Test
    fun `a live report lists the version 1 queue and the unreadable one, not the version 2 queue, with the live text`() = runTest {
        val loss = assertNotNull(live().lossBlock, "the premise: a message-loss block")
        assertEquals(MigrateFromThreeTexts.LOSS_DETECTION_LIVE, loss.detection)
        assertFalse(loss.detection.contains("A definitions file cannot tell"), "the file wording is false for a live report: ${loss.detection}")
        assertEquals(listOf("unreadable", "v1"), (0 until loss.queueCount).map { loss.queueAt(it) }.sorted(), "exactly the V1 and the unreadable queue")
        assertEquals("unreadable\nv1", loss.copyText.lines().sorted().joinToString("\n"))
        assertTrue(loss.detection.contains("comes from the broker"))
    }

    @Test
    fun `a file analysis keeps the file text unchanged`() {
        val loss = assertNotNull(ReportViewBuilder.build(ScreenFiles.held()).lossBlock)
        assertEquals(MigrateFromThreeTexts.LOSS_DETECTION, loss.detection)
        assertTrue(loss.detection.contains("A definitions file cannot tell the index version"))
    }
}
