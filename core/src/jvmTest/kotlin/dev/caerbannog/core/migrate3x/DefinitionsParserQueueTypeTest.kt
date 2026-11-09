package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefinitionsParserQueueTypeTest {
    private fun kindOf(queueJson: String): QueueKind =
        parsedDocument("""{"queues":[{"name":"q","vhost":"/"$queueJson}]}""").queues.single().type

    @Test
    fun `no type and no argument is classic`() = assertEquals(QueueKind.Classic, kindOf(""))

    @Test
    fun `argument quorum`() = assertEquals(QueueKind.Quorum, kindOf(""","arguments":{"x-queue-type":"quorum"}"""))

    @Test
    fun `argument stream`() = assertEquals(QueueKind.Stream, kindOf(""","arguments":{"x-queue-type":"stream"}"""))

    @Test
    fun `argument classic`() = assertEquals(QueueKind.Classic, kindOf(""","arguments":{"x-queue-type":"classic"}"""))

    @Test
    fun `top level type wins over the argument`() =
        assertEquals(QueueKind.Stream, kindOf(""","type":"stream","arguments":{"x-queue-type":"quorum"}"""))

    @Test
    fun `top level type alone is used`() = assertEquals(QueueKind.Quorum, kindOf(""","type":"quorum""""))

    @Test
    fun `unknown type is other and not an error`() = assertEquals(QueueKind.Other("mystery"), kindOf(""","type":"mystery""""))

    @Test
    fun `unknown argument type is other`() = assertEquals(QueueKind.Other("mystery"), kindOf(""","arguments":{"x-queue-type":"mystery"}"""))

    @Test
    fun `a 4x export with type on every queue`() {
        val doc = parsedDocument("""{"rabbit_version":"4.0.0","queues":[{"name":"a","vhost":"/","type":"classic"},{"name":"b","vhost":"/","type":"quorum"}]}""")
        assertEquals(listOf(QueueKind.Classic, QueueKind.Quorum), doc.queues.map { it.type })
    }
}
