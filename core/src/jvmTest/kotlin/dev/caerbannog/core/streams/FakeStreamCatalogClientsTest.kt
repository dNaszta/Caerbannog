package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.consumer
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.publisher
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M2-06a step 2: the fake additions for the tab: deleteStream, held calls for the per-stream reads, call failures, row helpers. Honest like the real API. */
class FakeStreamCatalogClientsTest {

    @Test
    fun `a deleted stream answers NotFound on detail, publishers and consumers, and adding it back restores it`() = runBlocking<Unit> {
        val c = FakeStreamCatalog().addStream(stream("a"))
        c.setConsumers("/", "a", listOf(consumer("x", 1, 5)))
        assertIs<CatalogResult.Success<*>>(c.streamDetail("/", "a"))

        c.deleteStream("a")
        assertEquals(CatalogResult.NotFound, c.streamDetail("/", "a"))
        assertEquals(CatalogResult.NotFound, c.publishers("/", "a"))
        assertEquals(CatalogResult.NotFound, c.consumers("/", "a"))
        assertEquals(0, (c.listStreams(StreamListRequest("/")) as CatalogResult.Success).value.rows.size)

        c.addStream(stream("a"))
        assertIs<CatalogResult.Success<*>>(c.streamDetail("/", "a"))
    }

    @Test
    fun `the per stream reads can be held and completed late`() = runBlocking<Unit> {
        val c = FakeStreamCatalog().addStream(stream("a"))
        for (call in listOf("streamDetail", "publishers", "consumers")) {
            val gate = c.holdNext(call)
            val answer = async {
                when (call) {
                    "streamDetail" -> c.streamDetail("/", "a")
                    "publishers" -> c.publishers("/", "a")
                    else -> c.consumers("/", "a")
                }
            }
            yield()
            assertTrue(answer.isActive, "$call must wait for the gate")
            gate.release()
            assertIs<CatalogResult.Success<*>>(answer.await())
        }
    }

    @Test
    fun `failCall makes one call answer a failure until cleared and the others work`() = runBlocking<Unit> {
        val c = FakeStreamCatalog().addStream(stream("a"))
        c.failCall("consumers")
        assertEquals(CatalogResult.Unexpected, c.consumers("/", "a"))
        assertIs<CatalogResult.Success<*>>(c.publishers("/", "a"))
        c.clearFailures()
        assertIs<CatalogResult.Success<*>>(c.consumers("/", "a"))
    }

    @Test
    fun `the consumer helper computes the lag from the last offset unless it is given`() {
        assertEquals(349, consumer("a", offset = 47950, lastOffset = 48299).offsetLag)
        assertEquals(3, consumer("a", offset = 48299, lastOffset = 48299, offsetLag = 3).offsetLag)
        assertNull(consumer(null, 1, 2).name)
        assertEquals("", consumer("a", 1, 2, connectionName = "").connectionName)
        assertEquals(false, consumer("a", 1, 2, active = false).active)
    }

    @Test
    fun `the publisher helper builds a row, an empty connection is a settling row`() {
        val p = publisher("ref", published = 5)
        assertEquals(Triple(5L, 5L, 0L), Triple(p.published, p.confirmed, p.errored))
        assertEquals("", publisher(null, connectionName = "").connectionName)
    }
}
