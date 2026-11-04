package dev.caerbannog.datarabbit

import com.rabbitmq.stream.AuthenticationFailureException
import com.rabbitmq.stream.Constants
import com.rabbitmq.stream.NoOffsetException
import com.rabbitmq.stream.StreamDoesNotExistException
import com.rabbitmq.stream.StreamException
import dev.caerbannog.core.streams.FirstOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * M2-03 part 2 (no Docker): the real `StreamOffsets` maps what the stream client throws to the three outcomes,
 * exactly as measured in M2-03a (spec "Stream client facts"). Contract, package `dev.caerbannog.datarabbit`:
 *   internal object FirstOffsetMapper { fun map(read: () -> Long): FirstOffset }   (the stats call and `firstOffset()` run inside `read`)
 *   fun interface FirstOffsetSource { fun read(vhost: String, stream: String): Long }   (the one place that touches the Environment)
 *   class RabbitMqStreamOffsets(source: FirstOffsetSource, dispatcher: CoroutineDispatcher = Dispatchers.IO) : StreamOffsets
 *   RabbitMqStreamOffsets.MAX_PARALLEL_CALLS: Int      (companion const, > 0)
 * The stream-client classes used here are public in 1.12.0 (this module has it in test scope until the dependency commit).
 */
class FirstOffsetMapperTest {

    private fun map(read: () -> Long) = FirstOffsetMapper.map(read)

    @Test
    fun `a first offset is a value, offset 0 included`() {
        assertEquals(FirstOffset.Value(880_000), map { 880_000 })
        assertEquals(FirstOffset.Value(0), map { 0 })
    }

    @Test
    fun `NoOffsetException is Empty, not Unavailable`() =
        assertEquals(FirstOffset.Empty, map { throw NoOffsetException("No first offset for stream s") })

    @Test
    fun `access refused is Unavailable`() =
        assertEquals(
            FirstOffset.Unavailable,
            map { throw StreamException("Error while querying stream stats: 16 (ACCESS_REFUSED).", Constants.RESPONSE_CODE_ACCESS_REFUSED) },
        )

    @Test
    fun `a stream that was deleted between the list and the call is Unavailable`() =
        assertEquals(FirstOffset.Unavailable, map { throw StreamDoesNotExistException("s") })

    @Test
    fun `an authentication failure is Unavailable here, the management probe fails Connect first`() =
        assertEquals(FirstOffset.Unavailable, map { throw AuthenticationFailureException("8 (AUTHENTICATION_FAILURE)") })

    @Test
    fun `timeouts and connect failures are Unavailable`() {
        class TimeoutStreamException(message: String) : StreamException(message)
        class ConnectionStreamException(message: String, cause: Throwable) : StreamException(message, cause)
        assertEquals(FirstOffset.Unavailable, map { throw TimeoutStreamException("after 5000 ms") })
        assertEquals(FirstOffset.Unavailable, map { throw ConnectionStreamException("refused", java.net.ConnectException("refused")) })
        assertEquals(FirstOffset.Unavailable, map { throw TimeoutException("rpc") })
        assertEquals(FirstOffset.Unavailable, map { throw java.net.ConnectException("refused") })
    }

    @Test
    fun `any other failure is Unavailable and never escapes, the list must not fail because of it`() {
        assertEquals(FirstOffset.Unavailable, map { throw IllegalStateException("boom") })
        assertEquals(FirstOffset.Unavailable, map { throw NullPointerException() })
    }

    @Test
    fun `the result carries no exception text, so no secret can leak through it`() {
        val result = map { throw StreamException("password=Hunter2 for amqp://guest:Hunter2@host") }
        assertFalse(result.toString().contains("Hunter2"), result.toString())
    }

    // --- the real StreamOffsets around the source ---------------------------------------------------

    @Test
    fun `the call runs off the callers thread`() = runBlocking<Unit> {
        val callerThread = Thread.currentThread()
        var sourceThread: Thread? = null
        val offsets = RabbitMqStreamOffsets({ _, _ -> sourceThread = Thread.currentThread(); 7L })

        assertEquals(FirstOffset.Value(7), offsets.firstOffset("/", "s"))
        assertNotEquals(callerThread, sourceThread, "the blocking stats call must not run on the caller's (UI) thread")
    }

    @Test
    fun `the vhost and the stream reach the source unchanged`() = runBlocking<Unit> {
        val seen = mutableListOf<Pair<String, String>>()
        val offsets = RabbitMqStreamOffsets({ vhost, stream -> synchronized(seen) { seen += vhost to stream }; 1L })

        offsets.firstOffset("other vhost", "a/b")

        assertEquals(listOf("other vhost" to "a/b"), seen)
    }

    @Test
    fun `source failures come back as results`() = runBlocking<Unit> {
        val offsets = RabbitMqStreamOffsets({ _, stream ->
            when (stream) {
                "empty" -> throw NoOffsetException("none")
                "denied" -> throw StreamException("16 (ACCESS_REFUSED)")
                else -> 3L
            }
        })
        assertEquals(FirstOffset.Empty, offsets.firstOffset("/", "empty"))
        assertEquals(FirstOffset.Unavailable, offsets.firstOffset("/", "denied"))
        assertEquals(FirstOffset.Value(3), offsets.firstOffset("/", "fine"))
    }

    @Test
    fun `parallel calls are limited`() = runBlocking<Unit> {
        val limit = RabbitMqStreamOffsets.MAX_PARALLEL_CALLS
        assertTrue(limit > 0, "limit $limit")
        val inFlight = AtomicInteger()
        val highest = AtomicInteger()
        val release = CountDownLatch(1)
        val offsets = RabbitMqStreamOffsets({ _, _ ->
            val now = inFlight.incrementAndGet()
            highest.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            check(release.await(10, TimeUnit.SECONDS)) { "test never released the calls" }
            inFlight.decrementAndGet()
            1L
        })

        val calls = List(limit * 4) { async(Dispatchers.Default) { offsets.firstOffset("/", "s$it") } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (inFlight.get() < limit) {
            check(System.nanoTime() < deadline) { "only ${inFlight.get()} of $limit parallel calls started" }
            Thread.yield()
        }
        // Give a missing limit the chance to show: with no limit the other calls start within milliseconds. Bounded
        // watch (not a sleep for its own sake): ends early the moment one call too many is in flight.
        val watchUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (inFlight.get() <= limit && System.nanoTime() < watchUntil) Thread.yield()
        val seenWhileHeld = inFlight.get()
        release.countDown()
        calls.forEach { assertEquals(FirstOffset.Value(1), it.await()) }
        assertTrue(seenWhileHeld <= limit, "at most $limit calls may be in flight while all are held, saw $seenWhileHeld")

        assertTrue(highest.get() <= limit, "at most $limit calls at once, saw ${highest.get()}")
    }
}
