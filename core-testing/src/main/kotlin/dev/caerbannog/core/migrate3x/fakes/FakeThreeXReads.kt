package dev.caerbannog.core.migrate3x.fakes

import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.ThreeXReads
import java.util.Collections

/**
 * M8-02a: scriptable stand-in for the GET-only reads of a live RabbitMQ 3.x broker. [ThreeXReads] has no write method, so there is nothing
 * to log but reads: [calls] holds one entry per read, in order ("definitions", "deprecated-features", "storage-versions:<vhost>").
 *
 * Honest like the management API: a classic queue that has just started has NO storage version yet (the value shows up a few seconds
 * later, spike S-N), so a scripted queue is in the map with the value null until its read number is reached; a read that is scripted to
 * fail fails as a whole for that virtual host; the list of a virtual host also holds queues that are not classic, with no usable value
 * (the app must not ask for them: see [trapQueue]).
 */
class FakeThreeXReads : ThreeXReads {
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Every read, in call order. */
    val calls: List<String> get() = synchronized(log) { log.toList() }

    /** What `GET /api/definitions` answers. Default: an empty-ish export of RabbitMQ 3.13.7 (see [DefinitionsFiles.file]). */
    var definitions: ReadOutcome<String> = ReadOutcome.Ok(DefinitionsFiles.file(queues = emptyList(), version = THREE_13))

    /** What `GET /api/deprecated-features/used` answers: names only. */
    var deprecatedFeatures: ReadOutcome<Set<String>> = ReadOutcome.Ok(emptySet())

    private class Scripted(val value: Int?, val availableFromRead: Int?)

    private val scripts = HashMap<String, MutableMap<String, Scripted>>()
    private val storageFailures = HashMap<String, ReadOutcome<Map<String, Int?>>>()
    private val readsPerVhost = HashMap<String, Int>()

    /** The queue gets [version] from the [availableFromRead]-th read of its virtual host on (1 = at once); before that it is in the map with null. */
    fun storageVersionAppearsAtRead(vhost: String, queue: String, availableFromRead: Int, version: Int = 2) {
        scripts.getOrPut(vhost) { HashMap() }[queue] = Scripted(version, availableFromRead)
    }

    /** The queue never gets a storage version: always in the map with null. */
    fun storageVersionNeverAppears(vhost: String, queue: String) {
        scripts.getOrPut(vhost) { HashMap() }[queue] = Scripted(null, null)
    }

    /** A queue that is NOT classic but sits in the list with a value (1) anyway; a correct scan never reports it. */
    fun trapQueue(vhost: String, queue: String) {
        scripts.getOrPut(vhost) { HashMap() }[queue] = Scripted(1, 1)
    }

    /** Every storage version read of [vhost] ends with [outcome] (not Ok). */
    fun storageReadsFail(vhost: String, outcome: ReadOutcome<Map<String, Int?>>) {
        require(outcome !is ReadOutcome.Ok) { "use the scripts for Ok answers" }
        storageFailures[vhost] = outcome
    }

    fun storageReads(vhost: String): Int = calls.count { it == "storage-versions:$vhost" }

    val definitionsReads: Int get() = calls.count { it == "definitions" }

    override suspend fun definitions(): ReadOutcome<String> {
        log += "definitions"
        return definitions
    }

    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> {
        log += "deprecated-features"
        return deprecatedFeatures
    }

    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> {
        log += "storage-versions:$vhost"
        val n = synchronized(readsPerVhost) { (readsPerVhost[vhost] ?: 0) + 1 }.also { synchronized(readsPerVhost) { readsPerVhost[vhost] = it } }
        storageFailures[vhost]?.let { return it }
        val queues = scripts[vhost].orEmpty()
        return ReadOutcome.Ok(queues.mapValues { (_, s) -> if (s.availableFromRead != null && n >= s.availableFromRead) s.value else null })
    }

    companion object {
        const val THREE_13 = "3.13.7"
    }
}
