package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.browser.StreamMessageReader
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory test double for [StreamMessageReader].
 *
 * Simulates RabbitMQ stream message reads, bounds checking, chunk prefix drop,
 * timestamp seeking, and retention truncation.
 */
class FakeStreamMessageReader : StreamMessageReader {
    private val messages = ConcurrentHashMap<Pair<String, String>, MutableList<StreamMessage>>()
    private val denied = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val failed = ConcurrentHashMap<Pair<String, String>, String>()
    private val chunkStartOffsets = ConcurrentHashMap<Pair<String, String>, Long>()

    /** Recorded calls in format "vhost/stream:start:pageSize". */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun addMessage(
        vhost: String = "/",
        stream: String,
        offset: Long,
        timestamp: Long = 0L,
        payload: ByteArray = ByteArray(0),
        properties: Map<String, Any?> = emptyMap(),
        applicationProperties: Map<String, Any?> = emptyMap(),
    ) {
        val list = messages.computeIfAbsent(vhost to stream) { Collections.synchronizedList(ArrayList()) }
        list.add(StreamMessage(offset, timestamp, payload, properties, applicationProperties))
        list.sortBy { it.offset }
    }

    fun seed(vhost: String = "/", stream: String, msgs: List<StreamMessage>) {
        val list = messages.computeIfAbsent(vhost to stream) { Collections.synchronizedList(ArrayList()) }
        list.clear()
        list.addAll(msgs)
        list.sortBy { it.offset }
    }

    fun addMessages(
        vhost: String = "/",
        stream: String,
        count: Int,
        startOffset: Long = 0L,
        startTimestamp: Long = 0L,
        timestampStepMs: Long = 1000L,
    ) {
        repeat(count) { i ->
            val offset = startOffset + i
            val timestamp = if (startTimestamp > 0L) startTimestamp + i * timestampStepMs else 0L
            addMessage(
                vhost = vhost,
                stream = stream,
                offset = offset,
                timestamp = timestamp,
                payload = "message-$offset".encodeToByteArray(),
            )
        }
    }

    fun createEmptyStream(vhost: String = "/", stream: String) {
        messages.computeIfAbsent(vhost to stream) { Collections.synchronizedList(ArrayList()) }
    }

    fun removeBefore(vhost: String = "/", stream: String, offset: Long) {
        val list = messages[vhost to stream] ?: return
        list.removeIf { it.offset < offset }
    }

    fun setAccessDenied(vhost: String = "/", stream: String) {
        denied.add(vhost to stream)
    }

    fun setFailed(vhost: String = "/", stream: String, reason: String) {
        failed[vhost to stream] = reason
    }

    fun simulateChunkDeliveryStart(vhost: String = "/", stream: String, chunkStartOffset: Long) {
        chunkStartOffsets[vhost to stream] = chunkStartOffset
    }

    override suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult {
        calls += "$vhost/$stream:$start:$pageSize"
        val key = vhost to stream

        if (key in denied) return BrowseResult.AccessDenied
        failed[key]?.let { return BrowseResult.Failed(it) }

        val list = messages[key] ?: return BrowseResult.NotFound
        if (list.isEmpty()) {
            return BrowseResult.Empty(streamFirstOffset = 0L, streamLastOffset = 0L)
        }

        val firstOffset = list.first().offset
        val lastOffset = list.last().offset

        val startOffset = when (start) {
            BrowseStart.First -> firstOffset
            is BrowseStart.LastPage -> maxOf(firstOffset, lastOffset - pageSize + 1)
            is BrowseStart.Offset -> {
                if (start.offset < firstOffset || start.offset > lastOffset) {
                    return BrowseResult.OutsideRange(start.offset, firstOffset..lastOffset)
                }
                start.offset
            }
            is BrowseStart.Timestamp -> {
                val match = list.firstOrNull { it.timestamp >= start.epochMs }
                    ?: return BrowseResult.NoMessagesAtOrAfterTime(start.epochMs, firstOffset, lastOffset)
                match.offset
            }
        }

        // Simulate chunk boundary delivery: if chunk delivered from an earlier offset,
        // reader must drop messages below startOffset.
        val chunkStart = chunkStartOffsets[key]
        val rawDelivered = if (chunkStart != null && chunkStart < startOffset) {
            list.filter { it.offset >= chunkStart }
        } else {
            list.filter { it.offset >= startOffset }
        }

        // Filter out chunk prefix messages below requested startOffset
        val filtered = rawDelivered.filter { it.offset >= startOffset }
        val pageMessages = filtered.take(pageSize)

        return BrowseResult.Success(
            messages = pageMessages,
            streamFirstOffset = firstOffset,
            streamLastOffset = lastOffset,
        )
    }
}
