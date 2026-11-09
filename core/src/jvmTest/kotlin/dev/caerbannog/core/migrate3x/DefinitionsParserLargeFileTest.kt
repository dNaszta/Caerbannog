package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-01: a 20 MB export parses in the default jvmTest heap (no maxHeapSize bump). Generated here, never stored. No time assertion. */
class DefinitionsParserLargeFileTest {

    @Test
    fun `a text of at least 20 MB parses with exact counts`() {
        val queues = 60_000
        val bindings = 60_000
        val text = buildString(24 * 1024 * 1024) {
            append("""{"rabbit_version":"3.13.7","queues":[""")
            for (i in 0 until queues) {
                if (i > 0) append(',')
                append("""{"name":"large-queue-""").append(i).append("""","vhost":"/","durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum","x-max-length":""")
                    .append(1000 + i).append(""","x-dead-letter-exchange":"dlx","x-message-ttl":60000,"x-note":"""")
                    .append("n".repeat(100)).append(""""}}""")
            }
            append("""],"exchanges":[{"name":"dlx","vhost":"/","type":"direct"}],"bindings":[""")
            for (i in 0 until bindings) {
                if (i > 0) append(',')
                append("""{"source":"dlx","destination":"large-queue-""").append(i).append("""","destination_type":"queue","vhost":"/","routing_key":"rk-""")
                    .append(i).append("""","arguments":{}}""")
            }
            append("""],"policies":[{"name":"p","vhost":"/","pattern":".*","definition":{"max-length":1}}]}""")
        }
        assertTrue(text.length >= 20 * 1024 * 1024, "generated only ${text.length} characters")
        val doc = parsedDocument(text)
        assertEquals(queues, doc.queues.size)
        assertEquals(bindings, doc.bindings.size)
        assertEquals(1, doc.exchanges.size)
        assertEquals(1, doc.policies.size)
        assertEquals(QueueKind.Quorum, doc.queues.last().type)
        assertEquals(ArgumentValue.Whole(1000L + queues - 1), doc.queues.last().arguments["x-max-length"])
    }
}
