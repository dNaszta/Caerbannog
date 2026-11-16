package dev.caerbannog.core.planner

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-04 round 2 (F3), written first (red): a reader that throws is an unread broker, not an uncaught exception. The scope has its own
 * exception handler, so a leak fails THIS test and cannot reach other tests of the JVM (the leak seen in MetricSparklineTest).
 */
class PlannerViewModelReaderFailureTest {
    @Test
    fun `a reader that throws is treated as an unread broker and nothing leaks into the scope`() {
        val leaked = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> leaked += e })
        val rig = PlannerRig()
        try {
            val vm = PlannerViewModel(rig.store, rig.folders.ports, rig.settings, PlannerContext(), scope, descriptionDebounceMillis = 0, reader = { error("boom") })
            vm.openWizard(); vm.chooseKind("Queue"); vm.updateForm(QueueForm("orders")); vm.next(); vm.addToPlan()
            val state = vm.state.value
            assertEquals(1, state.plan.steps.size, "the plan must stay usable")
            assertNull(state.known)
            assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), state.graph.notice)
            assertTrue(leaked.isEmpty(), "the reader's exception escaped into the scope: $leaked")
        } finally {
            scope.cancel(); rig.close()
        }
    }
}
