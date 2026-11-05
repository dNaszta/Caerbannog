package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M2-14d test double: a [CaCertificateValidator] whose `validate` blocks until [release], so a test can finish a CA check
 * "late" (after a rename, a delete or a panel switch) without any sleep. `validate` is not a suspend function (the real one
 * does file IO), so it waits on a latch; call it from a worker thread, as `validateCaCertificateOffThread` does.
 *
 * [awaitStarted] returns once a `validate` call is waiting, so the test changes the state at a known point. A gate that is
 * never released fails the waiting call after [timeoutSeconds] instead of hanging the build.
 */
class GatedCaCertificateValidator(
    private val answer: CaCertificateValidationResult = CaCertificateValidationResult.Valid,
    private val timeoutSeconds: Long = 10,
) : CaCertificateValidator {
    private val started = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val seen = CopyOnWriteArrayList<String>()

    /** Every path `validate` was called with, in call order. */
    val validated: List<String> get() = seen.toList()

    /** Blocks the test thread until a `validate` call is waiting at the gate. */
    fun awaitStarted() {
        check(started.await(timeoutSeconds, TimeUnit.SECONDS)) { "the CA check never started within $timeoutSeconds s" }
    }

    /** Lets every waiting (and later) `validate` call return its answer. */
    fun release() = released.countDown()

    override fun validate(filePath: String): CaCertificateValidationResult {
        seen += filePath
        started.countDown()
        check(released.await(timeoutSeconds, TimeUnit.SECONDS)) { "the CA check was never released within $timeoutSeconds s" }
        return answer
    }
}
