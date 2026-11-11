package dev.caerbannog.core.forwarding.fakes

import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunOutcome
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.ForwardingRequest
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.RemoveOutcome
import dev.caerbannog.core.forwarding.ShovelGateway
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.StartOutcome
import dev.caerbannog.core.forwarding.StatusOutcome
import java.util.Collections

/** What the gateway was asked, in order. The only way a test sees what happened. There is no other kind of call: the interface has four operations. */
sealed interface ShovelCall {
    data object DryRun : ShovelCall
    data class Start(val request: ForwardingRequest) : ShovelCall
    data class Status(val ref: ShovelRef) : ShovelCall
    data class Remove(val ref: ShovelRef) : ShovelCall
}

/**
 * M8-07: scripted stand-in for the shovel part of the management API of the held 4.x broker. Honest about spike S-F: a start on a name that already
 * exists REPLACES that shovel (it is listed in [replaced]), so a test shows that the service never lets that happen. A dry run is one GET round; a
 * shovel scripted with [appearAfterDryRun] exists from the second dry run on.
 */
class FakeShovelGateway : ShovelGateway {
    private val log: MutableList<ShovelCall> = Collections.synchronizedList(ArrayList())
    val calls: List<ShovelCall> get() = synchronized(log) { log.toList() }

    /** Shovels that exist on the fake broker now, by (vhost, name). */
    val shovels: MutableMap<ShovelRef, ShovelStatus> = LinkedHashMap()

    /** Names a start replaced (the broker does that without a word). A correct service leaves this empty. */
    val replaced: MutableList<ShovelRef> = ArrayList()

    private val sources = HashMap<Triple<String, String, String>, QueueCounts>()
    private val destinations = HashMap<Pair<String, String>, DestinationQueueType>()
    private var pluginsOn = true
    private var tags: Set<String> = emptySet()
    private var lateShovel: ShovelStatus? = null
    private var dryRuns = 0
    private var statusScript: ArrayDeque<StatusOutcome> = ArrayDeque()
    private var startOutcome: StartOutcome = StartOutcome.Submitted
    private var removeOutcome: RemoveOutcome = RemoveOutcome.Removed
    private var dryRunFailure: DryRunOutcome? = null

    fun sourceQueue(connection: String, vhost: String, queue: String, ready: Long, unacked: Long = 0) {
        sources[Triple(connection, vhost, queue)] = QueueCounts(ready, unacked)
    }

    fun destinationQueue(vhost: String, queue: String, type: DestinationQueueType) { destinations[vhost to queue] = type }
    fun noDestinationQueue(vhost: String, queue: String) { destinations.remove(vhost to queue) }
    fun plugins(on: Boolean) { pluginsOn = on }
    fun userTags(vararg tags: String) { this.tags = tags.toSet() }
    fun existingShovel(status: ShovelStatus) { shovels[status.ref] = status }
    fun appearAfterDryRun(status: ShovelStatus) { lateShovel = status }
    fun statusScript(vararg outcomes: StatusOutcome) { statusScript = ArrayDeque(outcomes.toList()) }
    fun startOutcome(outcome: StartOutcome) { startOutcome = outcome }
    fun removeOutcome(outcome: RemoveOutcome) { removeOutcome = outcome }
    fun failDryRun(outcome: DryRunOutcome) { dryRunFailure = outcome }

    private fun record(call: ShovelCall) { synchronized(log) { log += call } }

    override suspend fun dryRun(request: ForwardingRequest): DryRunOutcome {
        record(ShovelCall.DryRun)
        dryRuns++
        dryRunFailure?.let { return it }
        val s = request.source
        val counts = sources[Triple(s.connectionName, s.vhost, s.queue)] ?: return DryRunOutcome.SourceUnreachable(s.connectionName)
        if (dryRuns >= 2) lateShovel?.let { shovels[it.ref] = it }
        val type = destinations[request.destinationVhost to request.destinationQueue]
        return DryRunOutcome.Done(
            DryRunResult(counts, if (type == null) DestinationQueue.Missing else DestinationQueue.Exists(type), pluginsOn, tags, shovels[request.shovel]),
        )
    }

    override suspend fun start(request: ForwardingRequest): StartOutcome {
        record(ShovelCall.Start(request))
        if (startOutcome == StartOutcome.Submitted) {
            if (request.shovel in shovels) replaced += request.shovel
            shovels[request.shovel] = ShovelStatus(request.shovel, ShovelRunState.STARTING, null, null, null)
        }
        return startOutcome
    }

    override suspend fun status(shovel: ShovelRef): StatusOutcome {
        record(ShovelCall.Status(shovel))
        statusScript.removeFirstOrNull()?.let { return it }
        return shovels[shovel]?.let { StatusOutcome.Listed(it) } ?: StatusOutcome.Absent
    }

    override suspend fun remove(shovel: ShovelRef): RemoveOutcome {
        record(ShovelCall.Remove(shovel))
        if (removeOutcome == RemoveOutcome.Removed) shovels.remove(shovel)
        return removeOutcome
    }
}
