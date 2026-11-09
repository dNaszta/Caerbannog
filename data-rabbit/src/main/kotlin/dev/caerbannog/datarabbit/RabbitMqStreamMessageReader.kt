package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import com.rabbitmq.stream.NoOffsetException
import com.rabbitmq.stream.OffsetSpecification
import com.rabbitmq.stream.StreamDoesNotExistException
import com.rabbitmq.stream.StreamException
import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.browser.StreamMessageReader
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M3-01: Real implementation of [StreamMessageReader] over RabbitMQ stream protocol.
 *
 * SAFETY RULE: Browsing consumers are strictly unnamed and configured with `noTrackingStrategy()`,
 * ensuring that browsing never alters consumer stored offsets, writes tracking entries, or alters
 * message counts on the broker.
 *
 * Ephemeral consumer lifecycle: collects up to [pageSize] messages, drops messages delivered before
 * requested start offset (chunk boundary), and closes the consumer immediately after collecting
 * or upon idle timeout.
 */
class RabbitMqStreamMessageReader internal constructor(
    private val environments: StreamEnvironments,
    private val catalog: StreamCatalog,
) : StreamMessageReader {

    override suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult =
        withContext(Dispatchers.IO) {
            val env = try {
                environments.environmentFor(vhost)
            } catch (e: Exception) {
                return@withContext BrowseResult.Failed(e.message ?: "Failed to connect to stream")
            }

            // 1. Query stream bounds
            val streamStats = try {
                env.queryStreamStats(stream)
            } catch (e: NoOffsetException) {
                return@withContext BrowseResult.Empty(0L, 0L)
            } catch (e: StreamDoesNotExistException) {
                return@withContext BrowseResult.NotFound
            } catch (e: StreamException) {
                if (e.message?.contains("ACCESS_REFUSED", ignoreCase = true) == true) {
                    return@withContext BrowseResult.AccessDenied
                }
                return@withContext BrowseResult.Failed(e.message ?: "Stream error")
            } catch (e: IOException) {
                return@withContext BrowseResult.Failed(e.message ?: "IO error")
            }

            val firstOffset = try {
                streamStats.firstOffset()
            } catch (e: NoOffsetException) {
                return@withContext BrowseResult.Empty(0L, 0L)
            }

            val directCommitted = runCatching { streamStats.committedOffset() }.getOrNull()
            val streamDetail = catalog.streamDetail(vhost, stream)
            val catalogMessages = (streamDetail as? CatalogResult.Success)?.value?.messages
            val catalogLastOffset = if (catalogMessages != null && catalogMessages > 0L) firstOffset + catalogMessages - 1L else null
            val chunkId = runCatching { streamStats.committedChunkId() }.getOrNull()

            val initialLastOffset = directCommitted ?: catalogLastOffset ?: maxOf(firstOffset, chunkId ?: firstOffset)

            // 2. Resolve start offset
            val requestedOffset = when (start) {
                BrowseStart.First -> firstOffset
                is BrowseStart.LastPage -> maxOf(firstOffset, initialLastOffset - pageSize + 1L)
                is BrowseStart.Offset -> {
                    if (start.offset < firstOffset) {
                        return@withContext BrowseResult.OutsideRange(start.offset, firstOffset..initialLastOffset)
                    }
                    if (catalogLastOffset != null && start.offset > catalogLastOffset) {
                        return@withContext BrowseResult.OutsideRange(start.offset, firstOffset..catalogLastOffset)
                    }
                    start.offset
                }
                is BrowseStart.Timestamp -> null
            }

            // 3. Ephemeral read with unnamed consumer and noTrackingStrategy
            val collected = Collections.synchronizedList(ArrayList<StreamMessage>())
            val doneLatch = CountDownLatch(pageSize)

            val consumerBuilder = env.consumerBuilder()
                .stream(stream)
                .noTrackingStrategy()

            if (start is BrowseStart.Timestamp) {
                consumerBuilder.offset(OffsetSpecification.timestamp(start.epochMs))
            } else {
                consumerBuilder.offset(OffsetSpecification.offset(requestedOffset!!))
            }

            val minOffset = requestedOffset ?: firstOffset

            consumerBuilder.messageHandler { context, message ->
                val offset = context.offset()
                // Drop messages delivered from chunk start that precede requested offset
                if (offset >= minOffset && collected.size < pageSize) {
                    collected.add(toStreamMessage(offset, context.timestamp(), message))
                    doneLatch.countDown()
                }
            }

            val consumer = try {
                consumerBuilder.build()
            } catch (e: Exception) {
                return@withContext BrowseResult.Failed(e.message ?: "Failed to open consumer")
            }

            try {
                // Wait for page to fill or idle timeout (up to 2000ms)
                doneLatch.await(2000, TimeUnit.MILLISECONDS)
            } finally {
                runCatching { consumer.close() }
            }

            val page = collected.toList().sortedBy { it.offset }

            if (page.isEmpty()) {
                if (start is BrowseStart.Timestamp) {
                    return@withContext BrowseResult.NoMessagesAtOrAfterTime(start.epochMs, firstOffset, initialLastOffset)
                }
                if (start is BrowseStart.Offset && start.offset > initialLastOffset) {
                    return@withContext BrowseResult.OutsideRange(start.offset, firstOffset..initialLastOffset)
                }
                return@withContext BrowseResult.Empty(firstOffset, initialLastOffset)
            }

            val streamLastOffset = maxOf(initialLastOffset, page.last().offset)
            BrowseResult.Success(
                messages = page,
                streamFirstOffset = firstOffset,
                streamLastOffset = streamLastOffset,
            )
        }
}
