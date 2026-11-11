package dev.caerbannog.core.forwarding
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.forwarding.fakes.ShovelCall
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.ScreenFiles
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** M8-09: the forwarding view model over the real `ForwardingService` and `FakeShovelGateway` (virtual time, state in, events out). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FwdRig(
    test: TestScope,
    kind: SourceKind = SourceKind.Live,
    var level: TopologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT,
    var unlocked: Boolean = true,
    destination: ConnectionProfile? = ConnectionProfile("new-eu", "new.example", "/", "svc", tls = true),
    sourceTls: Boolean = true,
) {
    val gateway: FakeShovelGateway = Fwd.gateway()
    val source = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", tls = sourceTls)
    val holder = AnalysisHolder().also {
        val h = ScreenFiles.held(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("jobs"))), "legacy-eu")
        it.hold(HeldAnalysis("legacy-eu", h.document, h.report, kind))
    }
    var destinationConnection: DestinationConnection? = destination?.let { DestinationConnection(it) }
    val vm = ForwardingViewModel(
        ForwardingService(gateway) { ForwardingContext(level, unlocked) }, holder, { destinationConnection },
        { if (it == "legacy-eu") source else null }, { ForwardingContext(level, unlocked) }, test.backgroundScope,
    )
    val test = test
    val state get() = vm.state.value

    /** The polling loop never ends while the shovel is STARTING, so the view model runs on the background scope and the test runs only what is due. */
    fun settle() = test.testScheduler.runCurrent()
    fun open() { vm.open(); settle() }
    fun orders() = QueueRow("/", "orders")
    fun pickOrders() { open(); vm.pick(orders()); settle() }
    fun confirmForm() = assertIs<ForwardingScreenState.Confirm>(state, "state is $state")
    fun typeAndConfirm(typed: String = "orders") { vm.setTyped(typed); settle(); vm.confirm(); settle() }
    fun onlyStart(): ShovelCall.Start { assertEquals(1, starts.size, "expected one start, calls: ${gateway.calls}"); return starts.single() }
    val starts get() = gateway.calls.filterIsInstance<ShovelCall.Start>()
}
