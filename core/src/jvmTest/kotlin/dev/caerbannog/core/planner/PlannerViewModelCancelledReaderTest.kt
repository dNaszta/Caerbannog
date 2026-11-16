package dev.caerbannog.core.planner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-05, F5 (from the M9-04 review): a reader that throws `CancellationException` while the view model's scope is still alive (a timeout inside
 * the adapter, a cancelled HTTP call of its own) is an unread broker, not a read that is still running. Before the fix the exception was
 * rethrown, the coroutine ended silently and `knownPending` stayed true for ever: the graph said "reading" and never stopped.
 * A cancellation of the scope itself still ends the read without a notice (covered in PlannerViewModelReaderFailureRound2Test).
 */
class PlannerViewModelCancelledReaderTest {
    private val leaked = Collections.synchronizedList(mutableListOf<Throwable>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> leaked += e })

    private fun vm(rig: PlannerRig, reader: KnownTopologyReader) =
        PlannerViewModel(rig.store, rig.folders.ports, rig.settings, PlannerContext(), scope, descriptionDebounceMillis = 0, reader = reader)

    @Test
    fun `a reader that throws CancellationException while the scope lives is an unread broker, the read is no longer pending`() {
        val rig = PlannerRig()
        val v = vm(rig) { throw CancellationException("the adapter's own call was cancelled") }
        assertFalse(v.state.value.knownPending, "the read ended, so it must not stay pending")
        assertNull(v.state.value.known)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), v.state.value.graph.notice)
        assertTrue(leaked.isEmpty(), "$leaked")
        scope.cancel(); rig.close()
    }

    @Test
    fun `after such a read the plan is usable and the next virtual host is read and answered`() {
        val rig = PlannerRig()
        val good = KnownTopology(queues = setOf("q"))
        val v = vm(rig) { vhost -> if (vhost == "/") throw CancellationException("not mine") else good }
        assertFalse(v.state.value.knownPending)
        v.setVirtualHost("billing")
        assertEquals(good, v.state.value.known)
        assertFalse(v.state.value.knownPending)
        assertNull(v.state.value.graph.notice)
        assertTrue(leaked.isEmpty(), "$leaked")
        scope.cancel(); rig.close()
    }
}
