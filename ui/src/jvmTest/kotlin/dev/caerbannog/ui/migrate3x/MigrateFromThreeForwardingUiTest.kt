package dev.caerbannog.ui.migrate3x

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.forwarding.DestinationConnection
import dev.caerbannog.core.forwarding.DestinationQueue
import dev.caerbannog.core.forwarding.DestinationQueueType
import dev.caerbannog.core.forwarding.DryRunResult
import dev.caerbannog.core.forwarding.FORWARDING_POLL_MILLIS
import dev.caerbannog.core.forwarding.ForwardingBlock
import dev.caerbannog.core.forwarding.ForwardingContext
import dev.caerbannog.core.forwarding.ForwardingForm
import dev.caerbannog.core.forwarding.ForwardingScreenState
import dev.caerbannog.core.forwarding.ForwardingService
import dev.caerbannog.core.forwarding.ForwardingState
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ForwardingViewModel
import dev.caerbannog.core.forwarding.ForwardingWarning
import dev.caerbannog.core.forwarding.QueueCounts
import dev.caerbannog.core.forwarding.QueueRow
import dev.caerbannog.core.forwarding.RemoveButton
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.HeldAnalysis
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.MigrateFromThreeState
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.ReportViewBuilder
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SourceKind
import dev.caerbannog.core.migrate3x.analyseLive
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-09: the forwarding view and the entry button, with the tags of the brief. State in, events out; the typed-name test runs over the real view model. */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeForwardingUiTest {
    private val ref = ShovelRef("/", "caerbannog-migrate-orders")
    private val facts = DryRunResult(QueueCounts(120, 0), DestinationQueue.Exists(DestinationQueueType.QUORUM), true, setOf("policymaker", "monitoring"), null)
    private val form = ForwardingForm("legacy.example", 5671)

    private class Recorder : ForwardingEvents {
        val log = mutableListOf<String>()
        override fun setQuery(query: String) { log += "query:$query" }
        override fun pick(row: QueueRow) { log += "pick:${row.name}" }
        override fun setAmqpHost(host: String) { log += "host:$host" }
        override fun setAmqpPort(port: Int) { log += "port:$port" }
        override fun setAnotherUser(on: Boolean, user: String, password: String) { log += "user:$on" }
        override fun setKeepRunning(on: Boolean) { log += "keep:$on" }
        override fun setTyped(text: String) { log += "typed:$text" }
        override fun confirm() { log += "confirm" }
        override fun cancel() { log += "cancel" }
        override fun remove() { log += "remove" }
        override fun close() { log += "close" }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.showForwarding(state: ForwardingScreenState, events: ForwardingEvents = Recorder()) =
        setContent { ForwardingView(state, events) }

    /** The real view model over the fake gateway, on an immediate dispatcher; the screen follows its state. */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.showReal(level: TopologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT): ForwardingViewModel {
        val gateway = FakeShovelGateway().apply {
            sourceQueue("legacy-eu", "/", "orders", 120); destinationQueue("/", "orders", DestinationQueueType.QUORUM); userTags("policymaker", "monitoring")
        }
        val text = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders")))
        val doc = (DefinitionsParser.parse(text) as DefinitionsParseResult.Parsed).document
        val holder = AnalysisHolder().also { it.hold(HeldAnalysis("legacy-eu", doc, CompatibilityAnalyzer.analyze(doc), SourceKind.Live)) }
        val source = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", tls = true)
        val destination = DestinationConnection(ConnectionProfile("new-eu", "new.example", "/", "svc", tls = true))
        val vm = ForwardingViewModel(
            ForwardingService(gateway) { ForwardingContext(level, true) }, holder, { destination }, { if (it == "legacy-eu") source else null },
            { ForwardingContext(level, true) }, CoroutineScope(Dispatchers.Unconfined), FORWARDING_POLL_MILLIS,
        )
        vm.open(); vm.pick(QueueRow("/", "orders"))
        setContent { val state by vm.state.collectAsState(); ForwardingView(state, vm.events()) }
        return vm
    }

    private fun confirmState(lines: List<String> = listOf("From: legacy-eu, virtual host /, queue orders (amqps://svc-reader@legacy.example:5671/%2F)", ForwardingTexts.MOVED)) =
        ForwardingScreenState.Confirm("orders", "new-eu", lines, emptyList(), facts, form, canConfirm = false)

    // ---- entry ----

    private fun liveReport(): MigrateFromThreeState.Report {
        val reads = FakeThreeXReads().apply {
            definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
            storageVersionAppearsAtRead("/", "orders", 1, version = 1)
        }
        val held = (runBlocking { analyseLive(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { }) } as LiveAnalysisOutcome.Analysed).held
        return MigrateFromThreeState.Report(ReportViewBuilder.build(held, liveConnectionName = "legacy-eu"), null, ReviewState.NONE)
    }

    @Test
    fun `a live report has the Forward messages button in its app bar and clicking it is the forward event`() = runDesktopComposeUiTest(1280, 800) {
        val calls = MigrateCalls()
        val report = liveReport(); showWith({ report }, calls, forward = true)
        assertDrawn("migrate3x-forward")
        onNodeWithTag("migrate3x-forward").performClick()
        assertEquals(listOf("forward"), calls.events)
    }

    @Test
    fun `a file report has no Forward messages button`() = runDesktopComposeUiTest(1280, 800) {
        val report = MigrateScreenFiles.report(); showWith({ report }, forward = true)
        assertNotDrawn("migrate3x-forward")
    }

    // ---- pick ----

    @Test
    fun `the list has a find field and one button per queue, a disabled list shows its reason`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        showForwarding(ForwardingScreenState.Pick("", listOf(QueueRow("/", "orders"), QueueRow("/", "jobs")), null), events)
        onNodeWithTag("forward-find").performTextInput("ord")
        onNodeWithTag("forward-pick-1").performClick()
        assertEquals(listOf("query:ord", "pick:jobs"), events.log)
    }

    @Test
    fun `a disabled list draws the reason and its buttons are disabled`() = runDesktopComposeUiTest(1280, 800) {
        showForwarding(ForwardingScreenState.Pick("", listOf(QueueRow("/", "orders")), ForwardingTexts.CONNECT_FIRST))
        assertTrue(wholeScreenText().contains(ForwardingTexts.CONNECT_FIRST), wholeScreenText())
        onNodeWithTag("forward-pick-0").assertIsNotEnabled()
    }

    // ---- plan ----

    @Test
    fun `each block and each warning is drawn with its approved text`() = runDesktopComposeUiTest(1280, 800) {
        val status = ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80)
        val blocks = listOf(
            ForwardingBlock.TopologyLocked, ForwardingBlock.NoPolicymakerTag, ForwardingBlock.CannotReadStatus, ForwardingBlock.ShovelPluginsOff,
            ForwardingBlock.DestinationMissing, ForwardingBlock.ShovelExists(status),
        )
        val warnings = listOf(
            ForwardingWarning.DestinationNotQuorumOrStream(DestinationQueueType.CLASSIC), ForwardingWarning.KeepsShovelAndCredentials, ForwardingWarning.NotEncrypted("legacy-eu"),
        )
        showForwarding(ForwardingScreenState.Blocked("orders", "new-eu", blocks, warnings, null, null))
        assertDrawn("forward-plan")
        blocks.forEachIndexed { i, b -> assertTextHas("forward-block-$i", ForwardingTexts.block(b, "new-eu", "orders")) }
        warnings.forEachIndexed { i, w -> assertTextHas("forward-warning-$i", ForwardingTexts.warning(w)) }
        assertNotDrawn("forward-confirm")
        assertNotDrawn("forward-typed-name")
    }

    @Test
    fun `the confirmation draws its lines, the prefilled address, the labels and Cancel`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        showForwarding(confirmState(), events)
        assertTextHas("forward-plan", "From: legacy-eu", ForwardingTexts.MOVED)
        assertTextHas("forward-amqp-host", "legacy.example")
        assertTextHas("forward-amqp-port", "5671")
        assertTextHas("forward-typed-name", ForwardingTexts.LABEL_TYPED)
        assertTextHas("forward-keep-running", ForwardingTexts.LABEL_KEEP_RUNNING)
        assertTextHas("forward-other-user", ForwardingTexts.LABEL_ANOTHER_USER)
        onNodeWithTag("forward-cancel").performClick()
        assertEquals(listOf("cancel"), events.log)
    }

    // ---- typed name, password ----

    @Test
    fun `the confirm button is enabled only while the typed name matches exactly`() = runDesktopComposeUiTest(1280, 800) {
        showReal()
        onNodeWithTag("forward-confirm").assertIsNotEnabled()
        for (typed in listOf("order", "orders ", "ORDERS")) {
            onNodeWithTag("forward-typed-name").performTextReplacement(typed)
            onNodeWithTag("forward-confirm").assertIsNotEnabled()
        }
        onNodeWithTag("forward-typed-name").performTextReplacement("orders")
        onNodeWithTag("forward-confirm").assertIsEnabled()
    }

    @Test
    fun `the password field is masked`() = runDesktopComposeUiTest(1280, 800) {
        showReal()
        onNodeWithTag("forward-other-user").performClick()
        onNodeWithTag("forward-password").performTextInput("s3cret")
        assertTrue(onNodeWithTag("forward-password").fetchSemanticsNode().config.contains(SemanticsProperties.Password), "the password field is not masked")
    }

    @Test
    fun `the typed password is gone after Cancel`() = runDesktopComposeUiTest(1280, 800) {
        val vm = showReal()
        onNodeWithTag("forward-other-user").performClick()
        onNodeWithTag("forward-user").performTextInput("mover")
        onNodeWithTag("forward-password").performTextInput("s3cret")
        onNodeWithTag("forward-cancel").performClick()
        vm.pick(QueueRow("/", "orders")); waitForIdle()
        assertNotDrawn("forward-password")
        onNodeWithTag("forward-other-user").performClick()
        assertEquals("", onNodeWithTag("forward-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text, "the password survived Cancel")
    }

    // ---- progress, end, remove ----

    @Test
    fun `a running forwarding draws its text in the progress block, an ended one in the ended block, Remove is absent after Finished`() = runDesktopComposeUiTest(1280, 800) {
        showForwarding(ForwardingScreenState.Progress("orders", ForwardingState.Running(40, 80), listOf(ForwardingTexts.moved(40, 120)), RemoveButton(true, null)))
        assertTextHas("forward-progress", ForwardingTexts.moved(40, 120))
        assertNotDrawn("forward-ended")
        assertDrawn("forward-remove")
    }

    @Test
    fun `a finished forwarding is in the ended block with no Remove`() = runDesktopComposeUiTest(1280, 800) {
        showForwarding(ForwardingScreenState.Progress("orders", ForwardingState.Finished, listOf(ForwardingTexts.finishedStayed(20)), null))
        assertTextHas("forward-ended", ForwardingTexts.finishedStayed(20))
        assertNotDrawn("forward-remove")
    }

    @Test
    fun `Remove is disabled below Full control with its reason, enabled at Full control and then sends the event`() = runDesktopComposeUiTest(1280, 800) {
        showForwarding(ForwardingScreenState.Progress("orders", ForwardingState.Failed("r", false), listOf(ForwardingTexts.failed("r")), RemoveButton(false, ForwardingTexts.NEEDS_FULL_CONTROL)))
        onNodeWithTag("forward-remove").assertIsNotEnabled()
        assertTextHas("forward-remove-reason", ForwardingTexts.NEEDS_FULL_CONTROL)
    }

    @Test
    fun `an enabled Remove sends the remove event`() = runDesktopComposeUiTest(1280, 800) {
        val events = Recorder()
        showForwarding(ForwardingScreenState.Progress("orders", ForwardingState.Running(1, 2), listOf("x"), RemoveButton(true, null)), events)
        onNodeWithTag("forward-remove").performClick()
        assertEquals(listOf("remove"), events.log)
        assertNotDrawn("forward-remove-reason")
    }
}
