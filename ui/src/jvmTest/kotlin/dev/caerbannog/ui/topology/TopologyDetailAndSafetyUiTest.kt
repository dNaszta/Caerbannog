package dev.caerbannog.ui.topology

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.topology.BindingRowView
import dev.caerbannog.core.topology.InspectedQueueMessage
import dev.caerbannog.core.topology.QueueInspectionConstants
import dev.caerbannog.core.topology.QueueInspectionState
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08c step 2: the queue detail (mockup top block, "orders" sheet), the rule of T4 (NO write control anywhere, scenarios "The M2 topology screen
 * offers no action that changes topology" and "Queue messages cannot be read") and copying cells (UI1). Window 1280 x 800 dp. RED on the compile stub.
 * Tag contract, in addition to [TopologyScreenUiTest]:
 *  - `queueDetail` exists iff `state.selectedQueue != null` (on the Queues tab, under the table): title `detailTitle` = "<name>" and "<typeText>"
 *   (both strings in that node's text), the value nodes `detailValue:Leader`, `detailValue:Members`, `detailValue:MembersOnline`,
 *   `detailValue:ReadyUnacked`, `detailValue:Consumers`, `detailValue:Policy` show the view's strings unchanged, and the labels "Leader", "Members",
 *   "Ready / unacked", "Consumers", "Policy", "Binding" exist as text. One `detailBinding:<i>` per binding of the view (0-based), whose text holds the source,
 *   the destination and the routing key. `detailMemberOffline` (content description "Member offline") exists only when the view says `memberOffline`.
 *  - `detailClose`: a button, content description "Close details", calls `events.selectQueue(null)`; Escape with focus inside the detail does the same.
 *  - the row of the selected queue `queueRow:<name>` has semantics selected true, the others false.
 *  - T4: nothing on the screen can change topology or touch a queue's messages: no node tagged or labelled with the words in [forbidden] (edit, delete,
 *   bind, declare, purge, get, consume, browse, migration), no button with such text, no `createStream`, no row menu (`rowMenu:*`), in any state and
 *   with the detail open. The only buttons are navigation, refresh, retry, the tabs, the chips, the vhost switch, the page buttons, close details and the switch.
 */
@OptIn(ExperimentalTestApi::class)
class TopologyDetailAndSafetyUiTest {

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    // Whole words (lower case) a CONTROL (button, tab, switch, menu item) must not carry in its text, description or tag. The drawer item "Migrations" is navigation, a different word.
    private val forbidden = setOf("edit", "delete", "bind", "declare", "purge", "get", "consume", "browse", "migration", "remove", "add", "new")

    private fun wordsOf(n: androidx.compose.ui.semantics.SemanticsNode): Set<String> =
        (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.TestTag))).joinToString(" ").lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()

    // --- detail ---

    @Test
    fun `no detail until a queue is selected`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState())
        node("queueDetail").assertDoesNotExist()
    }

    @Test
    fun `the detail shows leader, members, counts, consumers, policy and bindings as the state gives them`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(selected = mockupDetail))
        node("queueDetail").assertExists()
        val title = node("detailTitle").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        assertTrue(title.contains("orders") && title.contains("Quorum queue"), title)
        node("detailValue:Leader").assertTextEquals("rabbit@node-1")
        node("detailValue:Members").assertTextEquals("node-1, node-2, node-3")
        node("detailValue:MembersOnline").assertTextEquals("3 of 3")
        node("detailValue:ReadyUnacked").assertTextEquals("1,240 / 12")
        node("detailValue:Consumers").assertTextEquals("3")
        node("detailValue:Policy").assertTextEquals("quorum-default")
        listOf("Leader", "Members", "Ready / unacked", "Consumers", "Policy", "Binding").forEach { assertTrue(onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "label $it") }
        val binding = node("detailBinding:0").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        assertTrue(binding.contains("events") && binding.contains("orders") && binding.contains("order.created"), binding)
        node("detailBinding:1").assertDoesNotExist()
        node("detailMemberOffline").assertDoesNotExist()
    }

    @Test
    fun `a detail with a member offline says so with a described mark`() = runDesktopComposeUiTest(1280, 800) {
        screen(queuesState(selected = mockupDetail.copy(membersOnline = "2 of 3", memberOffline = true)))
        assertEquals(listOf("Member offline"), node("detailMemberOffline").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `a queue with several bindings lists them all, one with none lists none`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState(selected = mockupDetail.copy(bindings = List(3) { BindingRowView("events", "orders", "queue", "k$it", "{}") })))
        node("detailBinding:2").assertExists()
        shown.state = queuesState(selected = mockupDetail.copy(bindings = emptyList()))
        waitForIdle()
        node("detailBinding:0").assertDoesNotExist()
    }

    @Test
    fun `the selected row is marked selected, the close button and Escape close the detail`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedTopologyEvents()
        screen(queuesState(selected = mockupDetail), events)
        assertEquals(true, node("queueRow:orders").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, node("queueRow:invoices").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(listOf("Close details"), node("detailClose").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        node("detailClose").performClick()
        node("detailClose").requestFocus()
        node("detailClose").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        waitForIdle()
        assertEquals(listOf("selectQueue:null", "selectQueue:null"), events.sent())
    }

    @Test
    fun `when the queue list has many rows the selected queue detail stays docked in the viewport without scrolling`() = runDesktopComposeUiTest(1280, 800) {
        val fiftyQueues = (1..50).map { queue("orders-$it") }
        screen(queuesState(rows = fiftyQueues, selected = mockupDetail.copy(name = "orders-1")))
        node("queueDetail").assertExists()
        val detailBounds = node("queueDetail").fetchSemanticsNode().boundsInRoot
        assertTrue(
            detailBounds.bottom <= 800f,
            "Expected docked queueDetail to remain inside the 800dp viewport even with 50 queue rows, got $detailBounds",
        )
    }

    // --- T4 and the safety rule ---

    // Matched in the MERGED tree: a Button's role is on the parent and its text on a child, only the merged node has both.
    private val writeControl = SemanticsMatcher("a control named like a write or message action") { n ->
        n.config.getOrNull(SemanticsProperties.Role) in setOf(Role.Button, Role.Tab, Role.Switch, Role.Checkbox, Role.RadioButton, Role.DropdownList) &&
            wordsOf(n).any { it in forbidden }
    }

    private fun DesktopComposeUiTest.assertNoWriteControl(what: String) {
        node("createStream").assertDoesNotExist()
        onAllNodes(SemanticsMatcher("a row menu") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith("rowMenu") }, useUnmergedTree = true).assertCountEquals(0)
        val offenders = onAllNodes(writeControl, useUnmergedTree = false).fetchSemanticsNodes()
        assertEquals(emptyList(), offenders.map { it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.toString() }, "$what: a write or message control")
    }

    @Test
    fun `no write control on the Queues tab, with and without a detail`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(queuesState())
        assertNoWriteControl("queues")
        shown.state = queuesState(selected = mockupDetail)
        waitForIdle()
        assertNoWriteControl("queue detail")
    }

    @Test
    fun `no write control on the Exchanges and Bindings tabs`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(exchangesState(listOf(exchange(""), exchange("orders", superStream = true))))
        assertNoWriteControl("exchanges")
        shown.state = bindingsState(listOf(binding("events", "orders", key = "order.created")))
        waitForIdle()
        assertNoWriteControl("bindings")
    }

    @Test
    fun `no write control in the states without a table`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(emptyQueues)
        assertNoWriteControl("empty")
        for (state in listOf(unavailable, noTag, failed, loading, notConnected)) {
            shown.state = state
            waitForIdle()
            assertNoWriteControl(state.screen.toString())
        }
    }

    @Test
    fun `the safety check itself sees a control named delete`() = runDesktopComposeUiTest(1280, 800) {
        // Sensitivity: the matcher used above must find a button that says Delete queue, or the tests above prove nothing.
        setContent {
            androidx.compose.material3.Button(onClick = {}) { androidx.compose.material3.Text("Delete queue") }
        }
        onAllNodes(writeControl, useUnmergedTree = false).assertCountEquals(1)
    }

    // --- copy (UI1) ---

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun copied(state: dev.caerbannog.core.topology.TopologyState, tag: String): String? {
        var result: String? = null
        runDesktopComposeUiTest(1280, 800) {
            val clipboard = FakeClipboard()
            screen(state, wrap = { CompositionLocalProvider(LocalClipboard provides clipboard) { it() } })
            node(tag).requestFocus()
            val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
            onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
            waitForIdle()
            result = clipboard.text
        }
        return result
    }

    @Test
    fun `a focused count cell copies it with its comma`() = assertEquals("1,240", copied(queuesState(), "cell:orders:Ready"))

    @Test
    fun `a focused queue name cell copies the whole name`() = assertEquals("legacy-orders", copied(queuesState(), "cell:legacy-orders:Name"))

    @Test
    fun `a focused routing key cell copies exactly the routing key`() =
        assertEquals("order.created.#", copied(bindingsState(listOf(binding("events", "orders", key = "order.created.#"))), "cell:0:RoutingKey"))

    @Test
    fun `a focused exchange name cell copies the name`() =
        assertEquals("dev.events", copied(exchangesState(listOf(exchange("dev.events", "topic"))), "cell:dev.events:Name"))

    @Test
    fun `non-destructive queue message inspection shows requeue mode notice and marks messages as not acknowledged`() = runDesktopComposeUiTest(1280, 900) {
        val recordedCalls = mutableListOf<String>()
        val inspectionEvents = object : QueueInspectionEvents {
            override fun inspectMessages() { recordedCalls += "inspectMessages" }
            override fun setCount(count: Int) { recordedCalls += "setCount:$count" }
            override fun selectMessage(index: Int) { recordedCalls += "selectMessage:$index" }
            override fun setPayloadViewKind(kind: PayloadViewKind?) { recordedCalls += "setKind:$kind" }
            override fun clear() { recordedCalls += "clear" }
        }
        val msg1 = InspectedQueueMessage(
            index = 1,
            payload = """{"orderId":"ORD-500"}""".encodeToByteArray(),
            payloadBytes = 21L,
            payloadEncoding = "string",
            redelivered = true,
            exchange = "events",
            routingKey = "order.created",
            properties = mapOf("content_type" to "application/json", "message_id" to "m-500"),
            headers = mapOf("region" to "eu"),
        )
        val msg2 = InspectedQueueMessage(
            index = 2,
            payload = "second-message-body".encodeToByteArray(),
            payloadBytes = 19L,
            payloadEncoding = "string",
            redelivered = false,
            exchange = "",
            routingKey = "orders",
        )
        val loadedInspection = QueueInspectionState.Loaded(
            vhost = "/",
            queue = "orders",
            count = 10,
            messages = listOf(msg1, msg2),
            selectedIndex = 1,
        )

        setContent {
            TopologyScreen(
                state = queuesState(selected = mockupDetail),
                nowMillis = 0,
                events = RecordedTopologyEvents(),
                control = dev.caerbannog.ui.streams.connected,
                controlEvents = dev.caerbannog.ui.streams.noControlEvents,
                queueInspectionState = loadedInspection,
                queueInspectionEvents = inspectionEvents,
            )
        }

        // Section header and requeue notice exist
        node("queueMessageInspector").assertExists()
        node("queueInspectorTitle").assertTextEquals("Non-destructive message inspection")
        node("queueInspectorAckModeBadge:label").assertTextEquals("Not acknowledged (requeued)")
        node("queueInspectorRequeueNotice").assertTextEquals(QueueInspectionConstants.REQUEUE_MODE_NOTICE)

        // Both message rows are listed and each is marked "Not acknowledged (requeued)"
        node("inspectedMessageRow:1").assertExists()
        node("inspectedMessageAckBadge:1:label").assertTextEquals("Not acknowledged (requeued)")
        node("inspectedMessageRow:2").assertExists()
        node("inspectedMessageAckBadge:2:label").assertTextEquals("Not acknowledged (requeued)")

        // Detail pane shows message #1, its Not acknowledged badge, and formatted JSON payload
        node("inspectedDetailTitle").assertTextEquals("Message #1 (21 B)")
        node("inspectedDetailAckBadge:label").assertTextEquals("Not acknowledged (requeued)")
        val payloadText = node("inspectedDetailPayload").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
        assertTrue(payloadText.contains("ORD-500"), "Expected payload to contain ORD-500, got: $payloadText")

        // Clicking count option, message row, inspect button, and clear button dispatches events
        node("inspectCountOption:5").performClick()
        node("inspectedMessageRow:2").performClick()
        node("inspectQueueMessagesButton").performClick()
        node("clearInspectedMessagesButton").performClick()
        waitForIdle()
        assertEquals(
            listOf("setCount:5", "selectMessage:2", "inspectMessages", "clear"),
            recordedCalls,
        )

        // Ensure no forbidden write/destructive control words exist even with the inspector open
        assertNoWriteControl("queue detail with non-destructive message inspector")
    }
}
