package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals

/** M9-08 slice 2: `KnownTopology` grows additively (`queueDetails`, `KnownQueue(type, policy)`); `queues`, `streams` and `None` stay as they were. */
class KnownTopologyDetailsTest {
    @Test
    fun `queueDetails is empty by default and None is still the empty topology`() {
        assertEquals(emptyMap(), KnownTopology().queueDetails)
        assertEquals(KnownTopology(), KnownTopology.None)
        assertEquals(emptyMap(), KnownTopology.None.queueDetails)
    }

    @Test
    fun `a queue detail carries the type and the name of the policy that applies, or none`() {
        val known = KnownTopology(
            queues = setOf("orders", "invoices"), queueDetails = mapOf("orders" to KnownQueue("quorum", "limits"), "invoices" to KnownQueue("classic", null)),
        )
        assertEquals("quorum", known.queueDetails.getValue("orders").type)
        assertEquals("limits", known.queueDetails.getValue("orders").policy)
        assertEquals(null, known.queueDetails.getValue("invoices").policy)
    }

    @Test
    fun `the older constructor shapes still compile and keep their meaning`() {
        val known = KnownTopology(exchanges = mapOf("events" to "direct"), queues = setOf("a"), streams = setOf("s"))
        assertEquals(setOf("a"), known.queues)
        assertEquals(setOf("s"), known.streams)
        assertEquals(emptyMap(), known.queueDetails)
    }
}
