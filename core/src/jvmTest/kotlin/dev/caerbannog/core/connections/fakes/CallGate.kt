package dev.caerbannog.core.connections.fakes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * M2-04 test double helper: makes ONE call of a fake wait until [release]. Used to complete a call "late" (after a
 * Disconnect or a switch) without any sleep. With [ignoreCancellation] the waiting call does not notice that its caller
 * was cancelled, so a test can prove the view model drops the late result by itself and not only through cancellation.
 */
class CallGate internal constructor(private val ignoreCancellation: Boolean) {
    private val released = CompletableDeferred<Unit>()

    fun release() {
        released.complete(Unit)
    }

    internal suspend fun await() {
        if (ignoreCancellation) withContext(NonCancellable) { released.await() } else released.await()
    }
}
