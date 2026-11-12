package dev.caerbannog.core.browser

import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamScannerTest {

    private val reader = FakeStreamMessageReader()
    private val scanner = StreamScanner(reader)

    @Test
    fun `filter by text finds matching messages within bounded scan`() = runTest {
        val msgs = (0 until 500).map { i ->
            val offset = 1000L + i
            val body = if (i == 10 || i == 50 || i == 200) "event with A-1202 id" else "event-$offset"
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = body.encodeToByteArray(),
            )
        }
        reader.seed("/", "orders", msgs)

        val updates = scanner.scan(
            vhost = "/",
            stream = "orders",
            startOffset = 1000L,
            criteria = MessageFilterCriteria.Text("A-1202"),
            scanLimit = 10_000,
        ).toList()

        val final = updates.last()
        assertEquals(500, final.scannedCount)
        assertEquals(3, final.matches.size)
        assertFalse(final.limitReached)
        assertTrue(final.matches.all { it.payload.decodeToString().contains("A-1202") })
    }

    @Test
    fun `filter by application property matches key and value`() = runTest {
        val msgs = (0 until 100).map { i ->
            val offset = 2000L + i
            val tenant = if (i % 2 == 0) "eu" else "us"
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = "body-$offset".encodeToByteArray(),
                applicationProperties = mapOf("tenant" to tenant),
            )
        }
        reader.seed("/", "orders", msgs)

        val updates = scanner.scan(
            vhost = "/",
            stream = "orders",
            startOffset = 2000L,
            criteria = MessageFilterCriteria.ApplicationProperty("tenant", "eu"),
        ).toList()

        val final = updates.last()
        assertEquals(100, final.scannedCount)
        assertEquals(50, final.matches.size)
        assertTrue(final.matches.all { it.applicationProperties["tenant"] == "eu" })
    }

    @Test
    fun `filter by standard property matches key and value`() = runTest {
        val msgs = (0 until 20).map { i ->
            val offset = 3000L + i
            val ct = if (i < 5) "application/json" else "text/plain"
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = "{}".encodeToByteArray(),
                properties = mapOf("content-type" to ct),
            )
        }
        reader.seed("/", "orders", msgs)

        val updates = scanner.scan(
            vhost = "/",
            stream = "orders",
            startOffset = 3000L,
            criteria = MessageFilterCriteria.Property("content-type", "application/json"),
        ).toList()

        val final = updates.last()
        assertEquals(5, final.matches.size)
    }

    @Test
    fun `scan stops when scan limit is reached`() = runTest {
        val msgs = (0 until 15_000).map { i ->
            val offset = i.toLong()
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = "noise".encodeToByteArray(),
            )
        }
        reader.seed("/", "orders", msgs)

        val updates = scanner.scan(
            vhost = "/",
            stream = "orders",
            startOffset = 0L,
            criteria = MessageFilterCriteria.Text("needle"),
            scanLimit = 10_000,
        ).toList()

        val final = updates.last()
        assertEquals(10_000, final.scannedCount)
        assertEquals(0, final.matches.size)
        assertTrue(final.limitReached)
        assertNotNull(final.notice)
        assertTrue(final.notice.contains("limit", ignoreCase = true))
    }

    @Test
    fun `scan handles empty stream gracefully`() = runTest {
        val updates = scanner.scan(
            vhost = "/",
            stream = "empty-stream",
            startOffset = 0L,
            criteria = MessageFilterCriteria.Text("test"),
        ).toList()

        assertEquals(1, updates.size)
        val final = updates.first()
        assertEquals(0, final.scannedCount)
        assertEquals(0, final.matches.size)
        assertFalse(final.limitReached)
    }
}
