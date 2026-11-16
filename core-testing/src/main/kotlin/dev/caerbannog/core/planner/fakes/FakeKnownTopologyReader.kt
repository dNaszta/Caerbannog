package dev.caerbannog.core.planner.fakes

import dev.caerbannog.core.connections.fakes.CallGate
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.KnownTopologyReader
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * M9-04: the planner's one read of the broker's names, in memory. Honest in the ways that matter to the view model:
 *  - every call is recorded in [calls] (the virtual host asked for, in order), so "it read once", "it read again for the other virtual host",
 *    "it never polls" and "it read nothing" are facts a test can state;
 *  - an answer is per virtual host ([answerFor]); a virtual host nobody set answers [answer] (default: nothing known, not null);
 *  - null is "the broker could not be read" ([unreadable]); an unknown virtual host on a real broker also answers null, so a test sets it;
 *  - [holdNext] completes ONE call late, even when its caller was cancelled ([ignoreCancellation]), to prove that a late answer for an old
 *    virtual host is dropped by the view model itself.
 */
class FakeKnownTopologyReader(var answer: KnownTopology? = KnownTopology.None) : KnownTopologyReader {
    private val perVhost = ConcurrentHashMap<String, KnownTopology>()
    private var hold: CallGate? = null

    /** The virtual hosts asked for, one entry per call, in order. */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun answerFor(vhost: String, known: KnownTopology?) {
        if (known == null) {
            perVhost.remove(vhost)
            unreadable += vhost
        } else {
            perVhost[vhost] = known
            unreadable -= vhost
        }
    }

    private val unreadable: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** The next call records itself, computes its answer, then waits until the gate is released. One-shot. */
    fun holdNext(ignoreCancellation: Boolean = true): CallGate = CallGate(ignoreCancellation).also { hold = it }

    override suspend fun read(vhost: String): KnownTopology? {
        calls += vhost
        val result = if (vhost in unreadable) null else perVhost[vhost] ?: answer
        val gate = hold
        if (gate != null) {
            hold = null
            gate.await()
        }
        return result
    }
}
