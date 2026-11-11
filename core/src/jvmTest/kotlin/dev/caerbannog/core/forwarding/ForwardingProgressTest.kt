package dev.caerbannog.core.forwarding

import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-07: Started, Running, Finished, Failed. One absent poll right after Started is not Finished (decision 1); Finished and Failed are final. */
class ForwardingProgressTest {
    private val ref = Fwd.request().shovel
    private fun listed(state: ShovelRunState, reason: String? = null, forwarded: Long? = null, remaining: Long? = null) =
        StatusOutcome.Listed(ShovelStatus(ref, state, reason, forwarded, remaining))

    private fun next(previous: ForwardingState, polled: StatusOutcome) = ForwardingProgress.next(previous, polled)

    @Test
    fun `started, running with 40 forwarded, then absent is finished`() {
        var s: ForwardingState = ForwardingState.Started()
        s = next(s, listed(ShovelRunState.RUNNING, forwarded = 40, remaining = 80))
        assertEquals(ForwardingState.Running(40, 80), s)
        assertEquals(ForwardingState.Finished, next(s, StatusOutcome.Absent))
    }

    @Test
    fun `a listed shovel that is not terminated is running, missing numbers count as 0 and unlimited`() {
        assertEquals(ForwardingState.Running(0, null), next(ForwardingState.Started(), listed(ShovelRunState.STARTING)))
        assertEquals(ForwardingState.Running(7, null), next(ForwardingState.Started(), listed(ShovelRunState.OTHER, forwarded = 7)))
    }

    @Test
    fun `one absent poll right after started is not finished, the second in a row is`() {
        val first = next(ForwardingState.Started(), StatusOutcome.Absent)
        assertEquals(ForwardingState.Started(1), first)
        assertEquals(ForwardingState.Finished, next(first, StatusOutcome.Absent))
    }

    @Test
    fun `a listed shovel between two absent polls resets the grace`() {
        val s = next(ForwardingState.Started(1), listed(ShovelRunState.STARTING))
        assertEquals(ForwardingState.Running(0, null), s)
    }

    @Test
    fun `terminated gives failed with its reason, not yet cleaned up`() {
        assertEquals(
            ForwardingState.Failed("failed to connect to source", false),
            next(ForwardingState.Running(5, 10), listed(ShovelRunState.TERMINATED, reason = "failed to connect to source")),
        )
        assertEquals(ForwardingState.Failed("x", false), next(ForwardingState.Started(), listed(ShovelRunState.TERMINATED, reason = "x")))
    }

    @Test
    fun `terminated without a reason gives the fixed no-reason text`() {
        assertEquals(ForwardingState.Failed(ForwardingTexts.NO_REASON, false), next(ForwardingState.Running(1, 2), listed(ShovelRunState.TERMINATED, reason = null)))
    }

    @Test
    fun `a read that is not permitted, unreachable or failed keeps the previous state`() {
        val before = ForwardingState.Running(40, 80)
        for (read in listOf(StatusOutcome.NotPermitted, StatusOutcome.Unreachable, StatusOutcome.Failed("boom"))) {
            assertEquals(before, next(before, read), "read $read")
            assertEquals(ForwardingState.Started(1), next(ForwardingState.Started(1), read), "read $read after Started")
        }
    }

    @Test
    fun `finished and failed are final`() {
        val failed = ForwardingState.Failed("r", true)
        for (read in listOf(listed(ShovelRunState.RUNNING, forwarded = 1), StatusOutcome.Absent, listed(ShovelRunState.TERMINATED, reason = "again"))) {
            assertEquals(ForwardingState.Finished, next(ForwardingState.Finished, read))
            assertEquals(failed, next(failed, read))
        }
    }
}
