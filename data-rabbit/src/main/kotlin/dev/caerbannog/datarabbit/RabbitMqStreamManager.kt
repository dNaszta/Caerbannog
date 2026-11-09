package dev.caerbannog.datarabbit

import com.rabbitmq.stream.Constants
import com.rabbitmq.stream.StreamDoesNotExistException
import com.rabbitmq.stream.StreamException
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
            mapStreamDeleteFailure(name, e)
        }
    }
}

/**
 * The one place that knows the stream client's "does not exist" failure: response code 2 (`STREAM_DOES_NOT_EXIST`) or a
 * `StreamDoesNotExistException` is [StreamManagementResult.NotFound]. It looks at the type and the code, never at the message text.
 * Every other failure is a refusal that names the stream.
 */
internal fun mapStreamDeleteFailure(name: String, failure: Exception): StreamManagementResult = when {
    failure is StreamDoesNotExistException -> StreamManagementResult.NotFound
    failure is StreamException && failure.code == Constants.RESPONSE_CODE_STREAM_DOES_NOT_EXIST -> StreamManagementResult.NotFound
    else -> StreamManagementResult.Refused("Broker refused to delete stream '$name': ${failure.message ?: failure.javaClass.simpleName}")
}
