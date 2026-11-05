package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val MILLIS = 1000L

/**
 * The one refresh driver of the screens that poll the broker while shown ([StreamListViewModel], [StreamClientsViewModel]); it exists
 * once so the rules cannot drift apart (M2-06 brief item 3). It owns: the timer (one wait of the current interval after the LAST load
 * ended, so a slow load makes ticks skip and never overlap), the load token (one load at a time), the alive check after every
 * suspension (the result of a replaced [StreamSource] or a cancelled driver is dropped) and the D2 mapping of an unreachable broker to
 * a lost connection ([ask]).
 *
 * Not thread-safe on purpose: every method runs on the owner's [scope] thread, as the owners' fields do.
 *
 * @param isCurrent true while the owner is open and [StreamSource] is still the one it follows (compared by reference).
 * @param interval read before every wait, so a changed setting is picked up by the next load at the latest.
 * @param loadOnce one load; it uses [ask] and [alive] for every broker call.
 */
internal class RefreshLoop(
    private val scope: CoroutineScope,
    private val active: ActiveConnection,
    private val isCurrent: (StreamSource) -> Boolean,
    private val interval: () -> RefreshInterval,
    private val loadOnce: suspend (StreamSource) -> Unit,
) {
    private var driver: Job? = null
    private var loadingToken: Any? = null

    /** True while a load runs; a manual refresh must then do nothing. */
    val loading: Boolean get() = loadingToken != null

    /** Loads now, then waits one interval after each load. Replaces a running driver. */
    fun loadNow(src: StreamSource) = start(src) { token -> load(src, token) }

    /** Waits one interval, then loads, and so on. Replaces a running driver. */
    fun timerOnly(src: StreamSource) = start(src) { }

    /** Runs [step] (not counted as a load), then the timer. Replaces a running driver. */
    fun stepThenTimer(src: StreamSource, step: suspend () -> Unit) = start(src) { step() }

    /** Cancels the driver and any load; safe to call when nothing runs. */
    fun cancel() {
        driver?.cancel()
        driver = null
        loadingToken = null
    }

    private fun start(src: StreamSource, first: suspend (Any) -> Unit) {
        cancel()
        val token = Any()
        driver = scope.launch {
            first(token)
            while (true) {
                val wait = interval()
                if (wait == RefreshInterval.Off) break
                delay(wait.seconds * MILLIS)
                if (!alive(src)) break
                load(src, token)
            }
        }
    }

    /** False when the connection changed, the owner closed or this coroutine was cancelled: the caller drops its result. */
    suspend fun alive(src: StreamSource): Boolean {
        currentCoroutineContext().ensureActive()
        return isCurrent(src)
    }

    private suspend fun load(src: StreamSource, token: Any) {
        loadingToken = token
        try {
            loadOnce(src)
        } finally {
            if (loadingToken === token) loadingToken = null
        }
    }

    /** One call to the broker. Null: the result must be dropped (superseded) or the connection was reported lost (D2). */
    suspend fun <T> ask(src: StreamSource, call: suspend () -> CatalogResult<T>): CatalogResult<T>? {
        val result = call()
        if (!alive(src)) return null
        if (result == CatalogResult.Unreachable) {
            // D2 (decided 2026-11-04): the one place that turns an unreachable broker into a lost connection.
            // Called from this coroutine, never from an observer of the link.
            active.connectionLost(src)
            return null
        }
        return result
    }
}
