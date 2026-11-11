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

@OptIn(ExperimentalCoroutinesApi::class)
class ForwardingViewModelTest {
    // ---- open and pick ----

    @Test
    fun `open lists the queues of a live analysis, a file analysis or none shows nothing`() = runTest {
        val live = FwdRig(this); live.open()
        val pick = assertIs<ForwardingScreenState.Pick>(live.state)
        assertEquals(setOf(QueueRow("/", "orders"), QueueRow("/", "jobs")), pick.rows.toSet())
        val file = FwdRig(this, kind = SourceKind.File); file.open()
        assertEquals(ForwardingScreenState.Closed, file.state)
        assertEquals(emptyList(), file.gateway.calls)
    }

    @Test
    fun `the find field filters the rows by name, ignoring case`() = runTest {
        val r = FwdRig(this); r.open(); r.vm.setQuery("ORD"); r.settle()
        val pick = assertIs<ForwardingScreenState.Pick>(r.state)
        assertEquals("ORD", pick.query)
        assertEquals(listOf(QueueRow("/", "orders")), pick.rows)
    }

    @Test
    fun `the buttons are disabled with a reason when there is no 4x connection or the connection is locked, and nothing is sent`() = runTest {
        val none = FwdRig(this, destination = null); none.open()
        assertEquals(ForwardingTexts.CONNECT_FIRST, assertIs<ForwardingScreenState.Pick>(none.state).forwardDisabledReason)
        none.vm.pick(none.orders()); none.settle()
        assertIs<ForwardingScreenState.Pick>(none.state)
        val locked = FwdRig(this, level = TopologyControlLevel.READ_ONLY); locked.open()
        assertEquals(ForwardingTexts.block(ForwardingBlock.TopologyLocked, "new-eu", ""), assertIs<ForwardingScreenState.Pick>(locked.state).forwardDisabledReason)
        locked.vm.pick(locked.orders()); locked.settle()
        assertEquals(emptyList(), locked.gateway.calls + none.gateway.calls, "a disabled forward sent a request")
        assertNull(assertIs<ForwardingScreenState.Pick>(FwdRig(this).also { it.open() }.state).forwardDisabledReason)
    }

    @Test
    fun `picking a queue sends only the dry run and shows the confirmation of that queue`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        assertEquals(listOf<ShovelCall>(ShovelCall.DryRun), r.gateway.calls)
        val c = r.confirmForm()
        assertEquals("orders", c.queue)
        assertEquals("new-eu", c.destination)
        assertTrue(c.lines.any { it.contains("legacy-eu") && it.contains("orders") })
        assertEquals(120L, c.facts.source.ready)
        assertFalse(c.canConfirm)
    }

    @Test
    fun `the form is prefilled with the source host and the default port of its TLS switch`() = runTest {
        val tls = FwdRig(this); tls.pickOrders()
        assertEquals("legacy.example" to 5671, tls.confirmForm().form.amqpHost to tls.confirmForm().form.amqpPort)
        val plain = FwdRig(this, sourceTls = false); plain.pickOrders()
        assertEquals("legacy.example" to 5672, plain.confirmForm().form.amqpHost to plain.confirmForm().form.amqpPort)
        assertTrue(ForwardingWarning.NotEncrypted("legacy-eu") in plain.confirmForm().warnings)
    }

    // ---- blocks and failed probes ----

    @Test
    fun `a blocked plan shows its blocks and warnings and no confirm form`() = runTest {
        val r = FwdRig(this); r.gateway.noDestinationQueue("/", "orders"); r.pickOrders()
        val b = assertIs<ForwardingScreenState.Blocked>(r.state)
        assertEquals(listOf(ForwardingBlock.DestinationMissing), b.blocks)
        assertEquals("new-eu", b.destination)
        assertNull(b.remove)
    }

    @Test
    fun `an existing shovel shows its state, and Remove is enabled only at Full control`() = runTest {
        for ((level, enabled) in listOf(TopologyControlLevel.DECLARE_AND_EDIT to false, TopologyControlLevel.FULL_CONTROL to true)) {
            val r = FwdRig(this, level = level)
            r.gateway.existingShovel(ShovelStatus(Fwd.request().shovel, ShovelRunState.RUNNING, null, 40, 80)); r.pickOrders()
            val b = assertIs<ForwardingScreenState.Blocked>(r.state)
            assertTrue(b.blocks.single() is ForwardingBlock.ShovelExists)
            val remove = b.remove ?: fail("no Remove button at $level")
            assertEquals(enabled, remove.enabled)
            assertEquals(if (enabled) null else ForwardingTexts.NEEDS_FULL_CONTROL, remove.reason)
        }
    }

    @Test
    fun `a failed dry run is one fixed text and never a confirm form`() = runTest {
        val r = FwdRig(this); r.gateway.failDryRun(DryRunOutcome.DestinationUnreachable); r.pickOrders()
        assertEquals(ForwardingScreenState.Problem("orders", ForwardingTexts.probeFailed(DryRunOutcome.DestinationUnreachable)), r.state)
    }

    // ---- confirmation ----

    @Test
    fun `the confirm button is enabled only while the typed text equals the queue name exactly`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        for ((typed, ok) in listOf("order" to false, "orders " to false, "ORDERS" to false, "" to false, "orders" to true)) {
            r.vm.setTyped(typed); r.settle()
            assertEquals(ok, r.confirmForm().canConfirm, "typed \"$typed\"")
        }
    }

    @Test
    fun `confirming with a wrong typed name sends nothing`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.typeAndConfirm("order")
        assertTrue(r.starts.isEmpty())
        assertIs<ForwardingScreenState.Confirm>(r.state)
    }

    @Test
    fun `the keep-running switch adds the warning line and the request is never`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        r.vm.setKeepRunning(true); r.settle()
        val c = r.confirmForm()
        assertTrue(ForwardingTexts.KEEPS_RUNNING in c.lines)
        assertTrue(ForwardingWarning.KeepsShovelAndCredentials in c.warnings)
        r.typeAndConfirm()
        assertEquals(SrcDeleteAfter.NEVER, r.onlyStart().request.srcDeleteAfter)
    }

    @Test
    fun `confirming sends one start with on-confirm, the form address and the same queue name, then shows Started`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        r.vm.setAmqpHost("amqp.legacy"); r.vm.setAmqpPort(6000); r.settle()
        r.typeAndConfirm()
        val request = r.onlyStart().request
        assertEquals(AckMode.ON_CONFIRM, request.ackMode)
        assertEquals(SrcDeleteAfter.QUEUE_LENGTH, request.srcDeleteAfter)
        assertEquals("amqps://svc-reader@amqp.legacy:6000/%2F", request.source.address.toString())
        assertEquals(listOf("orders", "orders", "/"), listOf(request.source.queue, request.destinationQueue, request.destinationVhost))
        assertEquals(ForwardingState.Started(), assertIs<ForwardingScreenState.Progress>(r.state).state)
    }

    @Test
    fun `another user sends that user's credentials, the state never holds the password`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        r.vm.setAnotherUser(true, "mover", "s3cret-SENTINEL"); r.settle()
        val shown = mutableListOf(r.state.toString())
        r.typeAndConfirm()
        shown += r.state.toString()
        val credentials = r.onlyStart().request.source.credentials
        assertEquals("mover", credentials?.username)
        assertEquals("s3cret-SENTINEL", credentials?.passwordForRequest())
        shown += r.gateway.calls.toString()
        shown.forEach { assertFalse(it.contains("s3cret-SENTINEL"), "the password is in: $it") }
    }

    @Test
    fun `cancel returns to the list, sends no start and wipes the form`() = runTest {
        val r = FwdRig(this); r.pickOrders()
        r.vm.setAnotherUser(true, "mover", "s3cret-SENTINEL"); r.vm.setTyped("orders"); r.settle()
        r.vm.cancel(); r.settle()
        assertIs<ForwardingScreenState.Pick>(r.state)
        assertTrue(r.starts.isEmpty(), "cancel sent a start")
        r.vm.pick(r.orders()); r.settle()
        val form = r.confirmForm().form
        assertEquals(listOf(false, "", null, ""), listOf(form.anotherUser, form.user, form.password, form.typed), "the form was not wiped")
    }

    // ---- progress and end ----

    private fun running(forwarded: Long, remaining: Long? = 80) = StatusOutcome.Listed(ShovelStatus(Fwd.request().shovel, ShovelRunState.RUNNING, null, forwarded, remaining))

    @Test
    fun `while the view is open the shovel is polled every 2 seconds and the state follows`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.typeAndConfirm()
        r.gateway.statusScript(running(40), running(80))
        val before = r.gateway.calls.size
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS - 1); r.test.testScheduler.runCurrent()
        assertEquals(before, r.gateway.calls.size, "polled before the interval")
        r.test.testScheduler.advanceTimeBy(1); r.test.testScheduler.runCurrent()
        val p = assertIs<ForwardingScreenState.Progress>(r.state)
        assertEquals(ForwardingState.Running(40, 80), p.state)
        assertEquals(listOf(ForwardingTexts.moved(40, 120)), p.texts)
    }

    @Test
    fun `with keep running the progress says the shovel keeps running`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.vm.setKeepRunning(true); r.settle(); r.typeAndConfirm()
        r.gateway.statusScript(running(40, null))
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS); r.test.testScheduler.runCurrent()
        assertEquals(listOf(ForwardingTexts.movedKeepRunning(40)), assertIs<ForwardingScreenState.Progress>(r.state).texts)
    }

    private fun finish(r: FwdRig, stayed: Long?) {
        r.gateway.statusScript(running(120), StatusOutcome.Absent)
        stayed?.let { r.gateway.sourceQueue("legacy-eu", "/", "orders", it) }
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS * 3); r.test.testScheduler.runCurrent(); r.settle()
    }

    @Test
    fun `finished reads the source again and reports what stayed, and then polling stops`() = runTest {
        for ((stayed, text) in listOf(20L to ForwardingTexts.finishedStayed(20), 0L to ForwardingTexts.FINISHED_NONE_STAYED)) {
            val r = FwdRig(this); r.pickOrders(); r.typeAndConfirm()
            finish(r, stayed)
            val p = assertIs<ForwardingScreenState.Progress>(r.state)
            assertEquals(ForwardingState.Finished, p.state)
            assertEquals(listOf(text), p.texts)
            assertNull(p.remove, "no Remove after Finished (D4)")
            assertEquals(ShovelCall.DryRun, r.gateway.calls.last(), "the last call is the read-only dry run")
            val polls = r.gateway.calls.count { it is ShovelCall.Status }
            r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS * 5); r.test.testScheduler.runCurrent()
            assertEquals(polls, r.gateway.calls.count { it is ShovelCall.Status }, "polling went on after Finished")
        }
    }

    @Test
    fun `finished with no facts from the second dry run says Finished alone`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.typeAndConfirm()
        r.gateway.failDryRun(DryRunOutcome.SourceUnreachable("legacy-eu"))
        r.gateway.statusScript(running(120), StatusOutcome.Absent)
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS * 3); r.test.testScheduler.runCurrent(); r.settle()
        assertEquals(listOf(ForwardingTexts.FINISHED), assertIs<ForwardingScreenState.Progress>(r.state).texts)
    }

    @Test
    fun `a plain source says Not encrypted in the progress view too`() = runTest {
        val r = FwdRig(this, sourceTls = false); r.pickOrders(); r.typeAndConfirm()
        r.gateway.statusScript(running(40))
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS); r.test.testScheduler.runCurrent()
        assertTrue(ForwardingTexts.NOT_ENCRYPTED in assertIs<ForwardingScreenState.Progress>(r.state).texts)
        val tls = FwdRig(this); tls.pickOrders(); tls.typeAndConfirm()
        assertFalse(ForwardingTexts.NOT_ENCRYPTED in assertIs<ForwardingScreenState.Progress>(tls.state).texts)
    }

    private fun terminated(reason: String) = StatusOutcome.Listed(ShovelStatus(Fwd.request().shovel, ShovelRunState.TERMINATED, reason, null, null))

    @Test
    fun `a failed shovel is reported with its reason and the cleanup, no Remove when it was removed`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.typeAndConfirm()
        r.gateway.statusScript(terminated("failed to connect to source"))
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS); r.test.testScheduler.runCurrent(); r.settle()
        val p = assertIs<ForwardingScreenState.Progress>(r.state)
        assertEquals(ForwardingState.Failed("failed to connect to source", true), p.state)
        assertEquals(listOf(ForwardingTexts.failed("failed to connect to source"), ForwardingTexts.CLEANED_UP), p.texts)
        assertNull(p.remove)
    }

    @Test
    fun `a failed shovel that could not be removed says so and offers Remove, enabled only at Full control`() = runTest {
        for ((level, enabled) in listOf(TopologyControlLevel.DECLARE_AND_EDIT to false, TopologyControlLevel.FULL_CONTROL to true)) {
            val r = FwdRig(this, level = level); r.pickOrders(); r.typeAndConfirm()
            r.gateway.statusScript(terminated("boom")); r.gateway.removeOutcome(RemoveOutcome.Failed("x"))
            r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS); r.test.testScheduler.runCurrent(); r.settle()
            val p = assertIs<ForwardingScreenState.Progress>(r.state)
            assertEquals(listOf(ForwardingTexts.failed("boom"), ForwardingTexts.notCleanedUp("new-eu")), p.texts)
            assertEquals(enabled, p.remove?.enabled, "level $level")
        }
    }

    @Test
    fun `Remove below Full control sends no request and says why, at Full control it removes the shovel`() = runTest {
        val low = FwdRig(this); low.gateway.existingShovel(ShovelStatus(Fwd.request().shovel, ShovelRunState.RUNNING, null, 1, 2)); low.pickOrders()
        val calls = low.gateway.calls.size
        low.vm.remove(); low.settle()
        assertEquals(calls, low.gateway.calls.size, "a remove request was sent below Full control")
        val full = FwdRig(this, level = TopologyControlLevel.FULL_CONTROL); full.gateway.existingShovel(ShovelStatus(Fwd.request().shovel, ShovelRunState.RUNNING, null, 1, 2)); full.pickOrders()
        full.vm.remove(); full.settle()
        assertEquals(listOf<ShovelCall>(ShovelCall.Remove(Fwd.request().shovel)), full.gateway.calls.filterIsInstance<ShovelCall.Remove>())
    }

    @Test
    fun `leaving the screen stops the polling but not the shovel, and wipes the form`() = runTest {
        val r = FwdRig(this); r.pickOrders(); r.vm.setAnotherUser(true, "mover", "s3cret-SENTINEL"); r.settle(); r.typeAndConfirm()
        r.vm.close(); r.settle()
        assertEquals(ForwardingScreenState.Closed, r.state)
        val polls = r.gateway.calls.count { it is ShovelCall.Status }
        r.test.testScheduler.advanceTimeBy(FORWARDING_POLL_MILLIS * 5); r.test.testScheduler.runCurrent()
        assertEquals(polls, r.gateway.calls.count { it is ShovelCall.Status }, "polled after close")
        assertTrue(r.gateway.calls.none { it is ShovelCall.Remove }, "close must not remove the shovel")
        assertFalse(r.state.toString().contains("s3cret-SENTINEL"))
    }

    @Test
    fun `there is no event that forwards every queue`() {
        val names = ForwardingViewModel::class.java.declaredMethods.map { it.name.lowercase() }.filterNot { '$' in it }
        assertTrue(names.none { "all" in it && it != "getstate" && it !in setOf("notifyall") }, "an event for all queues: $names")
        assertEquals(1, names.count { it == "pick" })
    }
}
