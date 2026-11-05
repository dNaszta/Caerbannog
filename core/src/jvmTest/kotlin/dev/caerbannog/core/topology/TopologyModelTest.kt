package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.classic
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.exchange
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog.Companion.quorum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M2-08a step 2: the three derived values of the topology models ("2 of 3 online" is the broker's `online` list, never names). */
class TopologyModelTest {

    private val three = listOf("rabbit@a", "rabbit@b", "rabbit@c")

    @Test
    fun `all members online is not flagged`() {
        val row = quorum("q", members = three)
        assertEquals(3, row.membersOnline)
        assertEquals(3, row.membersTotal)
        assertFalse(row.hasOfflineMember)
    }

    @Test
    fun `fewer online than members is flagged, 2 of 3`() {
        val row = quorum("q", members = three, online = three.take(2))
        assertEquals(2, row.membersOnline)
        assertEquals(3, row.membersTotal)
        assertTrue(row.hasOfflineMember)
    }

    @Test
    fun `the count comes from the online list, not from the names`() {
        // Same names in both lists but one online entry fewer: only the sizes tell.
        assertTrue(quorum("q", members = listOf("rabbit@a", "rabbit@b"), online = listOf("rabbit@a")).hasOfflineMember)
        assertFalse(quorum("q", members = listOf("rabbit@a", "rabbit@b"), online = listOf("rabbit@b", "rabbit@a")).hasOfflineMember)
    }

    @Test
    fun `a classic queue has no members, so nothing is flagged and nothing is online`() {
        val row = classic("c")
        assertEquals(0, row.membersTotal)
        assertEquals(0, row.membersOnline)
        assertFalse(row.hasOfflineMember)
        assertFalse(row.isQuorum)
        assertTrue(quorum("q").isQuorum)
    }

    @Test
    fun `only the exchange with the empty name is the default exchange`() {
        assertTrue(exchange("").isDefault)
        assertFalse(exchange("amq.direct").isDefault)
        assertFalse(exchange(" ").isDefault)
    }
}
