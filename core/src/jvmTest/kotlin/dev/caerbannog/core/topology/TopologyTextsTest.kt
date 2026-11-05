package dev.caerbannog.core.topology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** M2-08b step 2: the strings of the scenarios and the mockup, exact (they are asserted by Cucumber and by the Compose tests of 08c). */
class TopologyTextsTest {

    @Test
    fun `the management tag message`() {
        assertEquals("This user cannot read topology. Ask for the management tag.", TopologyTexts.NO_MANAGEMENT_TAG)
    }

    @Test
    fun `the unavailable vhost message names the vhost, also a vhost with a slash or a space`() {
        assertEquals(
            "Virtual host billing is not available to this user. It may have been deleted, or the user may have no permission on it.",
            TopologyTexts.vhostUnavailable("billing"),
        )
        assertEquals(
            "Virtual host my vhost/x is not available to this user. It may have been deleted, or the user may have no permission on it.",
            TopologyTexts.vhostUnavailable("my vhost/x"),
        )
    }

    @Test
    fun `the empty texts say there are no queues, exchanges or bindings yet`() {
        assertEquals("There are no queues in this virtual host yet.", TopologyTexts.NO_QUEUES)
        assertTrue(TopologyTexts.NO_EXCHANGES.contains("no exchanges"))
        assertTrue(TopologyTexts.NO_BINDINGS.contains("no bindings"))
    }

    @Test
    fun `the default exchange has one display name and the dash and n slash a differ`() {
        assertEquals("(default exchange)", TopologyTexts.DEFAULT_EXCHANGE)
        assertNotEquals(TopologyTexts.NOT_APPLICABLE, TopologyTexts.NOT_AVAILABLE)
        assertEquals("n/a", TopologyTexts.NOT_AVAILABLE)
    }

    @Test
    fun `the approximate note and the retry action`() {
        assertTrue(TopologyTexts.APPROXIMATE.contains("approximate"))
        assertEquals("Retry", TopologyTexts.RETRY)
    }

    @Test
    fun `the no configure wording of the mockup and the scenario in 02`() {
        assertEquals(
            "Topology is locked on this connection. The permission check found no configure permission for svc-explorer on virtual host /, " +
                "so queues, exchanges and bindings are read-only.",
            TopologyTexts.noConfigure("svc-explorer", "/"),
        )
    }
}
