package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M9-09 step 2, written first: `KnownQueue.ownDeadLetterExchange: String? = null` (the value of the queue's own `x-dead-letter-exchange` argument on the broker). The default keeps
 * every existing construction valid (two arguments, `KnownQueue("quorum", "limits")`). The wiring that fills it is `CatalogKnownTopologyOwnDeadLetterTest` (desktopApp); the note
 * it feeds is in `DeadLetterFormTest`. RED until the field exists.
 */
class KnownQueueOwnDeadLetterTest {
    @Test
    fun `the default is none, so an existing construction is unchanged`() {
        assertNull(KnownQueue("quorum", "limits").ownDeadLetterExchange)
        assertEquals(KnownQueue("quorum", "limits"), KnownQueue("quorum", "limits", ownDeadLetterExchange = null))
        assertEquals("quorum", KnownQueue("quorum", null).type)
    }

    @Test
    fun `it is part of the value`() {
        val own = KnownQueue("quorum", null, ownDeadLetterExchange = "legacy-dlx")
        assertEquals("legacy-dlx", own.ownDeadLetterExchange)
        assert(own != KnownQueue("quorum", null)) { "a queue with its own exchange differs from one without" }
        assertEquals(own, own.copy())
    }

    @Test
    fun `the rest of the known topology is built as before`() {
        val known = KnownTopology(queues = setOf("a"), queueDetails = mapOf("a" to KnownQueue("classic", null)))
        assertEquals(KnownQueue("classic", null), known.queueDetails["a"])
    }
}
