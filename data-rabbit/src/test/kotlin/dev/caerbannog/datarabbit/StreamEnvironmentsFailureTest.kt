package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Environment
import dev.caerbannog.core.connections.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.TrustManagerFactory

/**
 * M2-04 step 2, F1 item "unwrap CompletionException" (no Docker, no network). When two offset calls for the same vhost arrive
 * while the first one is still building the `Environment`, the second one waits on a future; if the build fails, the waiter used
 * to throw a `CompletionException` that wraps the real cause. The real cause class must surface (the mapper and the logs look at
 * the class, never at the message, and a wrapper class tells nothing).
 * Contract: `StreamEnvironments(profile, password, trust, environmentFactory: (vhost: String) -> Environment = <the real builder>)`,
 * a new last constructor parameter with a default, so the production call site does not change. The factory is the "failing builder".
 */
class StreamEnvironmentsFailureTest {

    private class BoomException : RuntimeException("boom: a message that must never be shown")

    private val profile = ConnectionProfile(name = "uat-eu", host = "rabbit.example.com", virtualHost = "/", username = "svc")
    private val trust: TrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }

    private fun environments(factory: (String) -> Environment) =
        StreamEnvironments(profile, "not-a-real-secret", trust, factory)

    @Test
    fun `a failing build surfaces its own class to the caller that built`() {
        val e = environments { throw BoomException() }

        val thrown = runCatching { e.read("/", "s") }.exceptionOrNull()

        assertEquals(BoomException::class.java, thrown?.javaClass, "got $thrown")
    }

    @Test
    fun `a caller that waited for the failing build gets the real cause class, not CompletionException`() {
        val building = CountDownLatch(1)
        val letItFail = CountDownLatch(1)
        val calls = AtomicInteger()
        val e = environments {
            calls.incrementAndGet()
            building.countDown()
            check(letItFail.await(10, TimeUnit.SECONDS)) { "test never released the build" }
            throw BoomException()
        }
        val first = AtomicReference<Throwable?>()
        val second = AtomicReference<Throwable?>()
        val a = Thread { first.set(runCatching { e.read("/", "s") }.exceptionOrNull()) }.apply { start() }
        assertTrue(building.await(10, TimeUnit.SECONDS), "the first build never started")
        val b = Thread { second.set(runCatching { e.read("/", "t") }.exceptionOrNull()) }.apply { start() }
        // The second caller is waiting on the first build's future when it is parked; no sleep, bounded polling on the state.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (b.state != Thread.State.WAITING && b.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(b.state == Thread.State.WAITING || b.state == Thread.State.TIMED_WAITING, "the second caller did not wait: ${b.state}")

        letItFail.countDown()
        a.join(10_000)
        b.join(10_000)

        assertEquals(BoomException::class.java, first.get()?.javaClass, "first: ${first.get()}")
        assertEquals(BoomException::class.java, second.get()?.javaClass, "second (the waiter): ${second.get()}")
        assertFalse(second.get() is java.util.concurrent.CompletionException)
        assertEquals(1, calls.get(), "one build for the vhost, the waiter did not build its own")
    }

    @Test
    fun `a failed build is not cached, the next call builds again`() {
        val calls = AtomicInteger()
        val e = environments {
            calls.incrementAndGet()
            throw BoomException()
        }

        runCatching { e.read("/", "s") }
        runCatching { e.read("/", "s") }

        assertEquals(2, calls.get())
    }
}
