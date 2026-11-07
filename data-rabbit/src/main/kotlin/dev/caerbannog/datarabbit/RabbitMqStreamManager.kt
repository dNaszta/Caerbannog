package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real RabbitMQ stream manager creating and deleting streams over the stream protocol.
 */
class RabbitMqStreamManager internal constructor(
    private val environments: StreamEnvironments,
) : StreamManager {

    override suspend fun createStream(vhost: String, name: String): StreamManagementResult = withContext(Dispatchers.IO) {
        try {
            val env = environments.environmentFor(vhost)
            env.streamCreator().stream(name).create()
            StreamManagementResult.Success
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            StreamManagementResult.Refused("Broker refused to create stream '$name': $msg")
        }
    }

    override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult = withContext(Dispatchers.IO) {
        try {
            val env = environments.environmentFor(vhost)
            env.deleteStream(name)
            StreamManagementResult.Success
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            StreamManagementResult.Refused("Broker refused to delete stream '$name': $msg")
        }
    }
}
