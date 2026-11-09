package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamSummary
import java.util.Collections

/**
 * In-memory test double for [StreamManager].
 * Tracks created and deleted stream calls, and allows simulating broker refusals/errors.
 */
class FakeStreamManager(
    private val catalog: FakeStreamCatalog? = null,
) : StreamManager {

    val createdStreams = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    val deletedStreams = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    @Volatile
    var refuseCreateWith: String? = null

    @Volatile
    var refuseDeleteWith: String? = null

    /** M5-08a: the next deletes answer [StreamManagementResult.NotFound] (the stream is not there). */
    @Volatile
    var notFoundOnDelete: Boolean = false

    @Volatile
    var failCreateWith: String? = null

    @Volatile
    var failDeleteWith: String? = null

    override suspend fun createStream(vhost: String, name: String): StreamManagementResult {
        failCreateWith?.let { return StreamManagementResult.Failed(it) }
        refuseCreateWith?.let { return StreamManagementResult.Refused(it) }

        createdStreams += vhost to name
        catalog?.addStream(
            StreamSummary(
                name = name,
                vhost = vhost,
                messages = 0L,
                consumers = 0,
                segments = 1,
                firstTimestamp = null,
                leader = null,
                node = null,
                members = emptyList(),
                online = emptyList(),
            ),
        )
        return StreamManagementResult.Success
    }

    override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult {
        if (notFoundOnDelete) return StreamManagementResult.NotFound
        failDeleteWith?.let { return StreamManagementResult.Failed(it) }
        refuseDeleteWith?.let { return StreamManagementResult.Refused(it) }

        deletedStreams += vhost to name
        catalog?.deleteStream(name, vhost)
        return StreamManagementResult.Success
    }

    fun clear() {
        createdStreams.clear()
        deletedStreams.clear()
        refuseCreateWith = null
        refuseDeleteWith = null
        failCreateWith = null
        failDeleteWith = null
        notFoundOnDelete = false
    }
}
