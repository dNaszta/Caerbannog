package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.StreamOffsets
import java.util.Collections

/**
 * M2-03 part 2 test double for [StreamOffsets] (same sharing as [FakeStreamCatalog]). Three outcomes, as the
 * real client (spec "Stream client facts"): a first offset, [FirstOffset.Empty] for a stream with no first
 * offset yet, [FirstOffset.Unavailable] for a denied or missing stream or a stream port that does not answer.
 * An unknown stream is Unavailable (the real call fails with StreamDoesNotExistException), never Empty.
 */
class FakeStreamOffsets : StreamOffsets {
    private val values = Collections.synchronizedMap(HashMap<Pair<String, String>, FirstOffset>())

    /** True: the stream port does not answer, every call is Unavailable (the connection itself stays usable). */
    @Volatile var silent: Boolean = false

    /** "vhost/stream" of every call, in order. */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun setFirst(vhost: String, stream: String, offset: Long) {
        values[vhost to stream] = FirstOffset.Value(offset)
    }

    fun setEmpty(vhost: String, stream: String) {
        values[vhost to stream] = FirstOffset.Empty
    }

    fun deny(vhost: String, stream: String) {
        values[vhost to stream] = FirstOffset.Unavailable
    }

    override suspend fun firstOffset(vhost: String, stream: String): FirstOffset {
        calls += "$vhost/$stream"
        if (silent) return FirstOffset.Unavailable
        return values[vhost to stream] ?: FirstOffset.Unavailable
    }
}
