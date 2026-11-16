package dev.caerbannog.core.planner

import kotlinx.coroutines.CompletableDeferred
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

/** M9-04 step 4, round 2 (test developer): cancellation is not a failed read, and a failed read does not poison the next vhost. */
class PlannerViewModelReaderFailureRound2Test {
    private val leaked = Collections.synchronizedList(mutableListOf<Throwable>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> leaked += e })

    private fun vm(rig: PlannerRig, reader: KnownTopologyReader) =
        PlannerViewModel(rig.store, rig.folders.ports, rig.settings, PlannerContext(), scope, descriptionDebounceMillis = 0, reader = reader)

    @Test
    fun `a read cancelled with its scope is not a failed read, no notice is invented and nothing leaks`() {
        val rig = PlannerRig()
        val gate = CompletableDeferred<KnownTopology?>()
        val v = vm(rig) { gate.await() }
        assertTrue(v.state.value.knownPending)
        scope.cancel()
        assertTrue(v.state.value.knownPending, "a cancelled read must not turn into BrokerNotRead")
        assertNull(v.state.value.graph.notice)
        assertTrue(leaked.isEmpty(), "$leaked")
        rig.close()
    }

    @Test
    fun `a throwing read for one virtual host does not stop the next virtual host from being read and answered`() {
        val rig = PlannerRig()
        val good = KnownTopology(queues = setOf("q"))
        val v = vm(rig) { vhost -> if (vhost == "/") error("boom") else good }
        assertFalse(v.state.value.knownPending)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), v.state.value.graph.notice)
        v.setVirtualHost("billing")
        assertEquals(good, v.state.value.known)
        assertNull(v.state.value.graph.notice)
        assertTrue(leaked.isEmpty(), "$leaked")
        scope.cancel(); rig.close()
    }
}
