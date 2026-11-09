package dev.caerbannog.datarabbit

import com.rabbitmq.stream.StreamDoesNotExistException
import com.rabbitmq.stream.StreamException
import dev.caerbannog.core.streams.StreamManagementResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-08a scope 7c: the stream adapter maps the stream client's "does not exist" failure to the typed `NotFound`, in one place, by exception type
 * and response code (stream protocol code 2, STREAM_DOES_NOT_EXIST), never by message text; every other failure stays a refusal.
 */
class StreamDeleteFailureMappingTest {

    @Test
    fun `a stream exception with response code 2 is NotFound whatever its text says`() {
        assertEquals(StreamManagementResult.NotFound, mapStreamDeleteFailure("orders", StreamException("some text that mentions nothing", 2.toShort())))
        assertEquals(StreamManagementResult.NotFound, mapStreamDeleteFailure("orders", StreamException("Error while deleting stream orders (2)", 2.toShort())))
    }

    @Test
    fun `a StreamDoesNotExistException is NotFound`() {
        assertEquals(StreamManagementResult.NotFound, mapStreamDeleteFailure("orders", StreamDoesNotExistException("orders")))
    }

    @Test
    fun `other response codes are a refusal, even when the text says STREAM_DOES_NOT_EXIST`() {
        val misleading = mapStreamDeleteFailure("orders", StreamException("STREAM_DOES_NOT_EXIST", 16.toShort())) // 16 = access refused
        val refused = assertInstanceOf(StreamManagementResult.Refused::class.java, misleading)
        assertTrue(refused.reason.contains("orders"), refused.reason)
    }

    @Test
    fun `a plain exception is a refusal that names the stream`() {
        val refused = assertInstanceOf(StreamManagementResult.Refused::class.java, mapStreamDeleteFailure("orders", IllegalStateException("closed")))
        assertTrue(refused.reason.contains("orders"), refused.reason)
        assertFalse(refused.reason.isBlank())
    }
}
