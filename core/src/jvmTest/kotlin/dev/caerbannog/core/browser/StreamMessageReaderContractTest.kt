package dev.caerbannog.core.browser

import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M3-01: Unit test for [StreamMessageReader] contracts against [FakeStreamMessageReader].
 */
class StreamMessageReaderContractTest {

    private val reader = FakeStreamMessageReader()

    @Test
    fun `an empty stream returns BrowseResult Empty`() = runBlocking {
        reader.createEmptyStream(stream = "empty-stream")
        val result = reader.readPage("/", "empty-stream", BrowseStart.First, pageSize = 50)
        assertInstanceOf(BrowseResult.Empty::class.java, result)
        assertEquals(listOf("//empty-stream:First:50"), reader.calls)
    }

    @Test
    fun `a missing stream returns BrowseResult NotFound`() = runBlocking {
        val result = reader.readPage("/", "non-existent", BrowseStart.First, pageSize = 50)
        assertEquals(BrowseResult.NotFound, result)
    }

    @Test
    fun `a stream with denied permissions returns BrowseResult AccessDenied`() = runBlocking {
        reader.setAccessDenied("/", "denied-stream")
        val result = reader.readPage("/", "denied-stream", BrowseStart.First, pageSize = 50)
        assertEquals(BrowseResult.AccessDenied, result)
    }

    @Test
    fun `browse starting from First returns first page in offset order`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        val result = reader.readPage("/", "events", BrowseStart.First, pageSize = 100)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(100, success.messages.size)
        assertEquals(0L, success.messages.first().offset)
        assertEquals(99L, success.messages.last().offset)
        assertEquals(0L, success.streamFirstOffset)
        assertEquals(249L, success.streamLastOffset)
    }

    @Test
    fun `browse starting from LastPage returns latest messages clamped to page size`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        val result = reader.readPage("/", "events", BrowseStart.LastPage(pageSize = 100), pageSize = 100)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(100, success.messages.size)
        assertEquals(150L, success.messages.first().offset)
        assertEquals(249L, success.messages.last().offset)
    }

    @Test
    fun `browse starting from a specific numeric offset`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        val result = reader.readPage("/", "events", BrowseStart.Offset(120L), pageSize = 100)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(100, success.messages.size)
        assertEquals(120L, success.messages.first().offset)
        assertEquals(219L, success.messages.last().offset)
    }

    @Test
    fun `browse starting from an offset outside stream range returns OutsideRange`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        val result = reader.readPage("/", "events", BrowseStart.Offset(250L), pageSize = 100)
        val outside = assertInstanceOf(BrowseResult.OutsideRange::class.java, result)
        assertEquals(250L, outside.offset)
        assertEquals(0L..249L, outside.validRange)
    }

    @Test
    fun `browse starting from timestamp locates first message at or after time`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L, startTimestamp = 1000L, timestampStepMs = 1000L)
        // offset 100 has timestamp 101_000L
        val result = reader.readPage("/", "events", BrowseStart.Timestamp(101_000L), pageSize = 50)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(100L, success.messages.first().offset)
        assertEquals(101_000L, success.messages.first().timestamp)
    }

    @Test
    fun `browse starting from timestamp after last message returns NoMessagesAtOrAfterTime`() = runBlocking {
        reader.addMessages(stream = "events", count = 10, startOffset = 0L, startTimestamp = 1000L, timestampStepMs = 1000L)
        val result = reader.readPage("/", "events", BrowseStart.Timestamp(999_999L), pageSize = 50)
        val empty = assertInstanceOf(BrowseResult.NoMessagesAtOrAfterTime::class.java, result)
        assertEquals(999_999L, empty.epochMs)
        assertEquals(0L, empty.streamFirstOffset)
        assertEquals(9L, empty.streamLastOffset)
    }

    @Test
    fun `messages delivered before requested start offset are discarded (chunk boundary drop)`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        // simulate chunk delivery starting from offset 90 when offset 100 was asked
        reader.simulateChunkDeliveryStart(stream = "events", chunkStartOffset = 90L)

        val result = reader.readPage("/", "events", BrowseStart.Offset(100L), pageSize = 50)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(100L, success.messages.first().offset)
        assertTrue(success.messages.none { it.offset < 100L }, "offsets < 100 must be dropped")
    }

    @Test
    fun `retention removing messages updates stream first offset and clamps`() = runBlocking {
        reader.addMessages(stream = "events", count = 250, startOffset = 0L)
        reader.removeBefore(stream = "events", offset = 150L)

        val result = reader.readPage("/", "events", BrowseStart.First, pageSize = 50)
        val success = assertInstanceOf(BrowseResult.Success::class.java, result)
        assertEquals(150L, success.streamFirstOffset)
        assertEquals(150L, success.messages.first().offset)
    }
}
