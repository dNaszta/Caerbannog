package dev.caerbannog.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * M2-TESTHYGIENE-04: the shared shape of "a test thread walks a fake's `calls` while the code under test records on another thread".
 * One thread runs [count] (a walk over the whole list: `count`, `filter`, a loop) again and again, another thread runs [record], which
 * must record [appends] calls through the fake's own public methods. Bounded: the writer starts only after the reader finished its first
 * pass (start latch), the reader stops when the writer is done, and every join has a timeout, so a hung writer fails with a message and
 * does not hang the build. No sleep. Fails when the reader threw anything (a `ConcurrentModificationException` is the case this guards),
 * when the writer threw or did not finish, and when the last count is not [appends] (nothing was lost).
 * [count] must return how many of the recorded calls are the ones [record] makes.
 */
fun assertReadableWhileRecording(what: String, appends: Int = 30_000, record: (appends: Int) -> Unit, count: () -> Int) {
    val readerRunning = CountDownLatch(1)
    val writerDone = AtomicBoolean(false)
    val readerFailure = AtomicReference<Throwable?>(null)
    val writerFailure = AtomicReference<Throwable?>(null)
    val reads = AtomicInteger()

    val reader = thread(name = "calls-reader", isDaemon = true) {
        try {
            do {
                count()
                reads.incrementAndGet()
                readerRunning.countDown()
            } while (!writerDone.get())
        } catch (t: Throwable) {
            readerFailure.set(t)
        } finally {
            readerRunning.countDown() // a dead reader must not leave the writer waiting
        }
    }
    val writer = thread(name = "calls-writer", isDaemon = true) {
        try {
            check(readerRunning.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "the reader thread did not finish its first pass in $JOIN_TIMEOUT_MS ms" }
            record(appends)
        } catch (t: Throwable) {
            writerFailure.set(t)
        } finally {
            writerDone.set(true)
        }
    }

    writer.join(JOIN_TIMEOUT_MS)
    if (writer.isAlive) throw AssertionError("expected the recording thread to make $appends calls to $what within $JOIN_TIMEOUT_MS ms, but it was still running (hung?)")
    reader.join(JOIN_TIMEOUT_MS)
    if (reader.isAlive) throw AssertionError("expected the reading thread to stop once the recording thread was done ($what), but it was still running after $JOIN_TIMEOUT_MS ms")

    writerFailure.get()?.let { throw AssertionError("expected the recording thread to make $appends calls to $what, but it threw $it", it) }
    readerFailure.get()?.let {
        throw AssertionError("expected $what to be readable while another thread records, but counting it threw $it after ${reads.get()} reads", it)
    }
    val recorded = count()
    if (recorded != appends) throw AssertionError("expected $appends recorded calls in $what, but it holds $recorded")
}

private const val JOIN_TIMEOUT_MS = 60_000L
