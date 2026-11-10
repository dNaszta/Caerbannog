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

    // ---- step 4 exploratory: 0, 1 and many queues at risk; hostile names; a quorum queue is never listed ----

    private suspend fun blockOf(classic: Map<String, Int?>, quorum: List<String> = emptyList()): LossBlockView? {
        val reads = FakeThreeXReads().apply {
            definitions = ReadOutcome.Ok(
                DefinitionsFiles.file(queues = classic.keys.map { DefinitionsFiles.queue(it) } + quorum.map { DefinitionsFiles.queue(it, type = "quorum") }),
            )
            classic.forEach { (name, version) -> if (version == null) storageVersionNeverAppears("/", name) else storageVersionAppearsAtRead("/", name, 1, version) }
            quorum.forEach { trapQueue("/", it) }
        }
        val held = assertIs<LiveAnalysisOutcome.Analysed>(analyseLive(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { })).held
        return ReportViewBuilder.build(held, liveConnectionName = "legacy-eu").lossBlock
    }

    @Test
    fun `no queue at risk gives no block, also when quorum queues exist`() = runTest {
        assertEquals(null, blockOf(mapOf("a" to 2, "b" to 2), quorum = listOf("q")))
        assertEquals(null, blockOf(emptyMap()))
    }

    @Test
    fun `one queue at risk gives a block of one, and a quorum queue is never listed whatever the broker says about it`() = runTest {
        val loss = assertNotNull(blockOf(mapOf("a" to 1, "b" to 2), quorum = listOf("q-trap")))
        assertEquals(listOf("a"), (0 until loss.queueCount).map { loss.queueAt(it) })
        assertEquals(MigrateFromThreeTexts.LOSS_DETECTION_LIVE, loss.detection)
    }

    @Test
    fun `many queues at risk are all counted, the preview is capped and the copy text has every name`() = runTest {
        val names = (1..60).map { "q%02d".format(it) }
        val loss = assertNotNull(blockOf(names.associateWith { if (it.endsWith("5")) null else 1 }))
        assertEquals(60, loss.queueCount)
        assertEquals(10, loss.preview.size)
        assertEquals(names.sorted(), loss.copyText.lines().sorted())
        assertEquals(60, (0 until loss.queueCount).map { loss.queueAt(it) }.toSet().size)
    }

    @Test
    fun `hostile queue names are escaped in the list and the copy text`() = runTest {
        val hostile = listOf("line\nbreak", "tab\there", "rtl\u202Eevil", "nul\u0000byte")
        val loss = assertNotNull(blockOf(hostile.associateWith { 1 }))
        val all = (0 until loss.queueCount).map { loss.queueAt(it) } + loss.preview + loss.copyText.lines()
        all.forEach { name -> assertTrue(name.none { it == '\u202E' || it == '\u0000' || it == '\t' }, "a raw control or bidi character in the list: ${name.map { it.code }}") }
        assertEquals(4, loss.copyText.lines().size, "a raw newline in a name would split the copied list")
    }
}
