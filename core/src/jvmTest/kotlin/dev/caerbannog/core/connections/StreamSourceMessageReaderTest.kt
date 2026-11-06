package dev.caerbannog.core.connections

import dev.caerbannog.core.browser.BrowseResult
import dev.caerbannog.core.browser.BrowseStart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * M3-01: [StreamSource.messages] reader reaches the message reader of the held connection while current,
 * and answers [BrowseResult.Failed] once closed/superseded without touching the connection.
 */
class StreamSourceMessageReaderTest {

    @Test
    fun `while current the source reaches the message reader of its connection`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.messagesOf("uat-eu").addMessages(stream = "events", count = 10)
        h.active.connect(h.uat)
        val source = h.active.source
        assertNotNull(source)

        val page = source!!.messages.readPage("/", "events", BrowseStart.First, pageSize = 5)
        val success = assertInstanceOf(BrowseResult.Success::class.java, page)
        assertEquals(5, success.messages.size)
        assertEquals(listOf("//events:First:5"), h.gateway.messagesOf("uat-eu").calls)
    }

    @Test
    fun `a message reader of a closed or superseded connection answers Failed and touches nothing`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.messagesOf("uat-eu").addMessages(stream = "events", count = 10)
        h.active.connect(h.uat)
        val oldSource = h.active.source!!

        h.active.disconnect()
        val result = oldSource.messages.readPage("/", "events", BrowseStart.First, pageSize = 5)
        assertInstanceOf(BrowseResult.Failed::class.java, result)
        assertEquals(emptyList<String>(), h.gateway.messagesOf("uat-eu").calls)
    }
}
