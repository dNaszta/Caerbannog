package dev.caerbannog.datarabbit

import com.rabbitmq.stream.NoOffsetException
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamOffsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the first offset is read: the one place that touches the stream client's `Environment`. Blocking. */
fun interface FirstOffsetSource {
    fun read(vhost: String, stream: String): Long
}

/** Turns what the stream client does into the three outcomes of [FirstOffset] (spec "Stream client facts"). */
internal object FirstOffsetMapper {
    // The list must never fail because of an offset: every failure other than "no first offset yet" is
    // Unavailable. Only the type is looked at, never the message (it could carry a host or a secret).
    @Suppress("TooGenericExceptionCaught")
    fun map(read: () -> Long): FirstOffset = try {
        FirstOffset.Value(read())
    } catch (e: NoOffsetException) {
        FirstOffset.Empty
    } catch (e: Exception) {
        FirstOffset.Unavailable
    }
}

/**
 * [StreamOffsets] over a [FirstOffsetSource]. The stats call blocks, so it runs on [dispatcher] limited to
 * [MAX_PARALLEL_CALLS] threads: a long list asks for many offsets and must not open a flood of requests or
 * block the UI thread.
 */
class RabbitMqStreamOffsets(
    private val source: FirstOffsetSource,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StreamOffsets {
    private val limited = dispatcher.limitedParallelism(MAX_PARALLEL_CALLS)

    override suspend fun firstOffset(vhost: String, stream: String): FirstOffset =
        withContext(limited) { FirstOffsetMapper.map { source.read(vhost, stream) } }

    companion object {
        const val MAX_PARALLEL_CALLS: Int = 4
    }
}
