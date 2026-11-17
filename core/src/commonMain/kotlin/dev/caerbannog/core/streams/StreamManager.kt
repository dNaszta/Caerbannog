package dev.caerbannog.core.streams

/**
 * Result of a stream management operation (create or delete).
 */
sealed interface StreamManagementResult {
    data object Success : StreamManagementResult
    data class Refused(val reason: String) : StreamManagementResult

    /** M5-08a: the stream does not exist (a delete of what is already gone). Typed so nobody matches message text. */
    data object NotFound : StreamManagementResult
    data class Failed(val message: String) : StreamManagementResult
}

/**
 * Capability to create and delete streams on a broker.
 */
interface StreamManager {
    suspend fun createStream(vhost: String, name: String, settings: StreamSettings = StreamSettings.NONE): StreamManagementResult
    suspend fun deleteStream(vhost: String, name: String): StreamManagementResult
}

/**
 * Default fallback when stream management is unavailable or disconnected.
 */
object UnavailableStreamManager : StreamManager {
    override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult =
        StreamManagementResult.Failed("Stream management unavailable")

    override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult =
        StreamManagementResult.Failed("Stream management unavailable")
}
