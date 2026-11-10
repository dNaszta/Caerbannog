package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.FetchResult
import dev.caerbannog.core.migrate3x.LiveSourceProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/** M8-06c: the live source of a screen test. [offer] and [withdraw] play the connection that comes and goes (the picker must follow). Read only, like the port. */
class FakeLiveSourceProvider(initial: DefinitionsSource? = null) : LiveSourceProvider {
    private val flow = MutableStateFlow(initial)
    override val current: StateFlow<DefinitionsSource?> get() = flow

    fun offer(source: DefinitionsSource) {
        flow.value = source
    }

    fun withdraw() {
        flow.value = null
    }
}

/**
 * M8-06c: a [DefinitionsSource] whose answer the test gives when it wants ([finish]); until then [fetch] waits, so `LoadingLive`, Cancel and a late result can be
 * observed. Every fetch is counted. With [answer] set at construction the fetch answers at once.
 */
class ScriptedDefinitionsSource(override val connectionName: String, private val answer: FetchResult? = null) : DefinitionsSource {
    private val waiting: MutableList<CompletableDeferred<FetchResult>> = Collections.synchronizedList(ArrayList())

    @Volatile
    var fetches: Int = 0
        private set

    override suspend fun fetch(): FetchResult {
        fetches++
        if (answer != null) return answer
        val gate = CompletableDeferred<FetchResult>()
        waiting += gate
        return gate.await()
    }

    /** Answers every fetch that is waiting. */
    fun finish(result: FetchResult) {
        synchronized(waiting) { waiting.toList().also { waiting.clear() } }.forEach { it.complete(result) }
    }
}
