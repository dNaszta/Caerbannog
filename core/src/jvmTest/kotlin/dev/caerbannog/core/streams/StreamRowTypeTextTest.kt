package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/** M2-07 step 2: the Type cell text (mockup "Super stream · 3 partitions"); the screen draws it as it is and it is what gets copied (UI1). */
class StreamRowTypeTextTest {

    private fun row(type: String, kind: StreamRowKind, partitionCount: Int = 0) = StreamRow(
        name = "n", type = type, messages = "1", firstOffset = "0", lastOffset = "0", publishers = "0", consumers = "0", retention = "",
        kind = kind, partitionCount = partitionCount,
    )

    @Test
    fun `a super stream reads Super stream, a middle dot and its partition count`() {
        assertEquals("Super stream · 3 partitions", row("Super stream", StreamRowKind.SuperStream, 3).typeText)
        assertEquals("Super stream · 12 partitions", row("Super stream", StreamRowKind.SuperStream, 12).typeText)
    }

    @Test
    fun `one partition is singular and none is plural`() {
        assertEquals("Super stream · 1 partition", row("Super stream", StreamRowKind.SuperStream, 1).typeText)
        assertEquals("Super stream · 0 partitions", row("Super stream", StreamRowKind.SuperStream, 0).typeText)
    }

    @Test
    fun `a stream and a partition read their type as it is`() {
        assertEquals("stream", row("stream", StreamRowKind.Stream).typeText)
        assertEquals("partition", row("partition", StreamRowKind.Partition).typeText)
    }
}
