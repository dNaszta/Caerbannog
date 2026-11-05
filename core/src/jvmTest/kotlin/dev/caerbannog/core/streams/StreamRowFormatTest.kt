package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-05c step 2, brief item 12 and `docs/ux/Streams.dc.html` "States and rules": offsets are PLAIN digits (no separator),
 * message counts, publishers and consumers are grouped with a comma (`1,204,000`), fixed and not locale dependent, so that copy
 * gives exactly what is shown. Cases: 0, 999, 1000, 2083999, `n/a`, `unavailable`, `loading...`.
 */
class StreamRowFormatTest {

    private fun summary(messages: Long?, consumers: Int = 0) = StreamSummary(
        name = "s", vhost = "/", messages = messages, consumers = consumers, segments = 1, firstTimestamp = null,
        leader = null, node = null, members = emptyList(), online = emptyList(),
    )

    @Test
    fun `message counts are grouped, offsets are plain`() {
        for ((messages, grouped) in listOf(0L to "0", 999L to "999", 1000L to "1,000", 1_204_000L to "1,204,000", 2_083_999L to "2,083,999")) {
            val row = streamRow(summary(messages), FirstOffset.Value(880_000), publishers = 1)
            assertEquals(grouped, row.messages, "messages $messages")
            assertEquals("880000", row.firstOffset, "first offset has no separator")
            if (messages > 0) assertEquals((880_000 + messages - 1).toString(), row.lastOffset, "last offset has no separator")
        }
    }

    @Test
    fun `a last offset in the millions has no separator`() {
        val row = streamRow(summary(1_204_000), FirstOffset.Value(880_000), publishers = 1)
        assertEquals("2083999", row.lastOffset)
    }

    @Test
    fun `publishers and consumers are grouped like the message count`() {
        for ((n, grouped) in listOf(0 to "0", 999 to "999", 1000 to "1,000", 12_345 to "12,345")) {
            val row = streamRow(summary(10, consumers = n), FirstOffset.Value(0), publishers = n)
            assertEquals(grouped, row.publishers, "publishers $n")
            assertEquals(grouped, row.consumers, "consumers $n")
        }
    }

    @Test
    fun `words stay words, never grouped or turned into numbers`() {
        assertEquals("n/a", streamRow(summary(null), FirstOffset.Value(0), publishers = 1).messages)
        val unknown = streamRow(summary(10), FirstOffset.Unavailable, publishers = null)
        assertEquals("unavailable", unknown.firstOffset)
        assertEquals("unavailable", unknown.lastOffset)
        assertEquals("unavailable", unknown.publishers)
        val loading = streamRow(summary(10), firstOffset = null, publishers = 1)
        assertEquals("loading...", loading.firstOffset)
        assertEquals("loading...", loading.lastOffset)
        assertEquals("10", loading.messages, "the counts stay while the first offset is read")
    }
}
