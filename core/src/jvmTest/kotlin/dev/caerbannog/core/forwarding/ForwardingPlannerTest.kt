package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
import dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
import dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-07: the pure rules, one per safeguard. Blocks come in a fixed order; a missing destination gives the block and never the warning. */
class ForwardingPlannerTest {
    private val request = Fwd.request()
    private fun plan(
        facts: DryRunResult = Fwd.facts(),
        context: ForwardingContext = Fwd.context(),
        request: ForwardingRequest = this.request,
    ) = ForwardingPlanner.plan(context, request, facts)

    @Test
    fun `gate - Read-only, or no configure permission, is locked, Declare and edit and Full control are open`() {
        assertEquals(listOf(ForwardingBlock.TopologyLocked), ForwardingPlanner.gate(Fwd.context(READ_ONLY, true)))
        assertEquals(listOf(ForwardingBlock.TopologyLocked), ForwardingPlanner.gate(Fwd.context(DECLARE_AND_EDIT, false)))
        assertEquals(listOf(ForwardingBlock.TopologyLocked), ForwardingPlanner.gate(Fwd.context(FULL_CONTROL, false)))
        assertEquals(emptyList(), ForwardingPlanner.gate(Fwd.context(DECLARE_AND_EDIT, true)))
        assertEquals(emptyList(), ForwardingPlanner.gate(Fwd.context(FULL_CONTROL, true)))
    }

    @Test
    fun `a clean dry run can start with no block and no warning`() {
        val p = plan()
        assertEquals(ForwardingPlan(emptyList(), emptyList()), p)
        assertTrue(p.canStart)
    }

    @Test
    fun `each block alone`() {
        assertEquals(listOf(ForwardingBlock.TopologyLocked), plan(context = Fwd.context(READ_ONLY)).blocks)
        assertEquals(listOf(ForwardingBlock.NoPolicymakerTag), plan(Fwd.facts(tags = setOf("monitoring"))).blocks)
        assertEquals(listOf(ForwardingBlock.CannotReadStatus), plan(Fwd.facts(tags = setOf("policymaker"))).blocks)
        assertEquals(listOf(ForwardingBlock.ShovelPluginsOff), plan(Fwd.facts(plugins = false)).blocks)
        assertEquals(listOf(ForwardingBlock.DestinationMissing), plan(Fwd.facts(destination = DestinationQueue.Missing)).blocks)
        val existing = Fwd.status(request)
        assertEquals(listOf(ForwardingBlock.ShovelExists(existing)), plan(Fwd.facts(existing = existing)).blocks)
        assertFalse(plan(Fwd.facts(plugins = false)).canStart)
    }

    @Test
    fun `tag rules - administrator alone allows both create and read, a user with neither tag gets both blocks`() {
        assertEquals(emptyList(), plan(Fwd.facts(tags = setOf("administrator"))).blocks)
        assertEquals(emptyList(), plan(Fwd.facts(tags = setOf("policymaker", "monitoring"))).blocks)
        assertEquals(listOf(ForwardingBlock.NoPolicymakerTag, ForwardingBlock.CannotReadStatus), plan(Fwd.facts(tags = setOf("management"))).blocks)
        assertEquals(listOf(ForwardingBlock.NoPolicymakerTag, ForwardingBlock.CannotReadStatus), plan(Fwd.facts(tags = emptySet())).blocks)
    }

    @Test
    fun `all blocks together come in the fixed order`() {
        val existing = Fwd.status(request)
        val p = plan(Fwd.facts(destination = DestinationQueue.Missing, plugins = false, tags = emptySet(), existing = existing), Fwd.context(READ_ONLY))
        assertEquals(
            listOf(
                ForwardingBlock.TopologyLocked, ForwardingBlock.NoPolicymakerTag, ForwardingBlock.CannotReadStatus,
                ForwardingBlock.ShovelPluginsOff, ForwardingBlock.DestinationMissing, ForwardingBlock.ShovelExists(existing),
            ),
            p.blocks,
        )
    }

    @Test
    fun `a destination that is not a quorum queue or stream is warned about, quorum and stream are not`() {
        for (type in listOf(DestinationQueueType.CLASSIC, DestinationQueueType.OTHER)) {
            assertEquals(listOf(ForwardingWarning.DestinationNotQuorumOrStream(type)), plan(Fwd.facts(destination = DestinationQueue.Exists(type))).warnings)
        }
        for (type in listOf(DestinationQueueType.QUORUM, DestinationQueueType.STREAM)) {
            assertEquals(emptyList(), plan(Fwd.facts(destination = DestinationQueue.Exists(type))).warnings)
        }
        assertTrue(plan(Fwd.facts(destination = DestinationQueue.Exists(DestinationQueueType.CLASSIC))).canStart, "a warning does not block")
    }

    @Test
    fun `a missing destination gives the block and never the type warning`() {
        assertEquals(emptyList(), plan(Fwd.facts(destination = DestinationQueue.Missing)).warnings)
    }

    @Test
    fun `the keeps-shovel warning is only for never`() {
        assertEquals(listOf(ForwardingWarning.KeepsShovelAndCredentials), plan(request = Fwd.request(never = true)).warnings)
        assertEquals(emptyList(), plan(request = Fwd.request(never = false)).warnings)
    }

    @Test
    fun `a plain source address is not encrypted, a TLS one is not`() {
        assertEquals(listOf(ForwardingWarning.NotEncrypted("legacy-eu")), plan(request = Fwd.request(tls = false)).warnings)
        assertEquals(emptyList(), plan(request = Fwd.request(tls = true)).warnings)
    }

    @Test
    fun `warnings come in the order type, keeps, not encrypted`() {
        val p = plan(Fwd.facts(destination = DestinationQueue.Exists(DestinationQueueType.CLASSIC)), request = Fwd.request(tls = false, never = true))
        assertEquals(
            listOf(ForwardingWarning.DestinationNotQuorumOrStream(DestinationQueueType.CLASSIC), ForwardingWarning.KeepsShovelAndCredentials, ForwardingWarning.NotEncrypted("legacy-eu")),
            p.warnings,
        )
    }
}
