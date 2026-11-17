package dev.caerbannog.ui.migrations

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrations.ActionDryRun
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationEntry
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.planner.TestMessageOpening
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10b step 2: the button "Send a test message" on the action row of a route (tags `migrations:testMessageBtn:<i>` and `migrations:testMessageReason:<i>`,
 * `<i>` the action row index as `dryRunAction:<i>`). Offered only for an applied file; then disabled with the MQTT port text, then with the write text.
 * `MigrationsScreen` gets two optional parameters: `onSendTestMessage: (TestMessageOpening) -> Unit` and `correlationIds: CorrelationIdSource`.
 */
@OptIn(ExperimentalTestApi::class)
class MigrationsMqttTestMessageUiTest {
    private val streamActions = listOf(
        MigrationAction.DeclareStream("device-commands-dln"),                                    // 0: not a route
        MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.answer"),  // 1: route with a wildcard
        MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.d1.status"), // 2: route without a wildcard
        MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "sensors..temp"),     // 3: a binding that is not a route
        MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "sensors.#"),         // 4: route with a hash
    )

    private fun state(
        actions: List<MigrationAction> = streamActions,
        status: MigrationStatus? = MigrationStatus.Applied("abc"),
        hasMqtt: Boolean = true,
        publishing: Boolean = true,
        vhost: String = "/",
    ): MigrationsScreenState {
        val file = MigrationFile("0005", "Routes", actions, """{ "version": "0005" }""", "abc")
        return MigrationsScreenState(
            entries = status?.let { listOf(MigrationEntry(file, it)) }.orEmpty(),
            profileName = "uat-eu", selectedVersion = "0005", selectedFile = file, vhost = vhost,
            dryRun = MigrationDryRun("0005", actions.map { ActionDryRun(it, allowed = true) }, canApply = false),
            hasMqtt = hasMqtt, publishingEnabled = publishing,
        )
    }

    private class Sent { val openings = mutableListOf<TestMessageOpening>() }

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: MigrationsScreenState, sent: Sent = Sent(), ids: CorrelationIdSource = CorrelationIdSource { "id-1" }) = setContent {
        MigrationsScreen(
            state = state, onSelectVersion = {}, onApplyPending = {}, onApplySingle = {}, onImportJson = {}, onExportTopology = {},
            onUpdateFile = { _, _ -> }, onValidateAgain = {}, onDismissError = {},
            onSendTestMessage = { sent.openings += it }, correlationIds = ids,
        )
    }

    private fun SemanticsNode.allText(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.allText() }).joinToString(" ")

    private fun androidx.compose.ui.test.ComposeUiTest.textOf(tag: String) =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().allText()

    private fun androidx.compose.ui.test.ComposeUiTest.drawn(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // ---- the button and where it is ----

    @Test
    fun `an applied file with an MQTT port and write permission offers an enabled button on each route row and on no other row`() = runDesktopComposeUiTest(1280, 1100) {
        show(state())
        for (i in listOf(1, 2, 4)) {
            onNodeWithTag("migrations:testMessageBtn:$i").assertIsEnabled()
            assertTrue(textOf("migrations:testMessageBtn:$i").contains("Send a test message"), textOf("migrations:testMessageBtn:$i"))
            assertTrue(!drawn("migrations:testMessageReason:$i"), "an available button has no reason")
        }
        for (i in listOf(0, 3)) {
            assertTrue(!drawn("migrations:testMessageBtn:$i"), "row $i is not a route")
            assertTrue(!drawn("migrations:testMessageReason:$i"), "row $i is not a route")
        }
    }

    @Test
    fun `a file with an answers route and a requests route offers each its own button and opens each with its own topic`() = runDesktopComposeUiTest(1280, 1000) {
        val sent = Sent()
        show(
            state(
                listOf(
                    MigrationAction.DeclareStream("device-commands-dln"),
                    MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.answer"),
                    MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.request"),
                ),
            ),
            sent,
        )
        onNodeWithTag("migrations:testMessageBtn:2").performClick()
        onNodeWithTag("testMessageValue:0").performTextReplacement("d7")
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        onNodeWithTag("testMessageValue:0").performTextReplacement("d8")
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        assertEquals(listOf("devices/d7/request", "devices/d8/answer"), sent.openings.map { it.topic })
    }

    // ---- the gates ----

    private fun noPort(publishing: Boolean) = runDesktopComposeUiTest(1280, 1100) {
        show(state(hasMqtt = false, publishing = publishing))
        onNodeWithTag("migrations:testMessageBtn:1").assertIsNotEnabled()
        assertEquals("This connection has no MQTT port", textOf("migrations:testMessageReason:1"))
    }

    @Test
    fun `without an MQTT port the button is disabled and says so`() = noPort(publishing = true)

    @Test
    fun `without an MQTT port and without write permission the port text comes first`() = noPort(publishing = false)

    @Test
    fun `without write permission the button is disabled with the write text naming the virtual host`() = runDesktopComposeUiTest(1280, 1100) {
        show(state(publishing = false, vhost = "prod"))
        onNodeWithTag("migrations:testMessageBtn:1").assertIsNotEnabled()
        assertEquals("Test publishing is disabled: no write permission on virtual host prod.", textOf("migrations:testMessageReason:1"))
    }

    @Test
    fun `a disabled button opens nothing`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(hasMqtt = false), sent)
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        waitForIdle()
        assertTrue(!drawn("testMessageDialog"))
        assertTrue(sent.openings.isEmpty())
    }

    private fun notApplied(status: MigrationStatus) = runDesktopComposeUiTest(1280, 1100) {
        show(state(status = status))
        for (i in 0..4) {
            assertTrue(!drawn("migrations:testMessageBtn:$i"), "$status row $i")
            assertTrue(!drawn("migrations:testMessageReason:$i"), "$status row $i")
        }
    }

    @Test
    fun `a pending file offers no button and no reason`() = notApplied(MigrationStatus.Pending)

    @Test
    fun `a modified file offers no button and no reason`() = notApplied(MigrationStatus.Modified("a", "b"))

    @Test
    fun `a file without an entry in the list offers no button`() = runDesktopComposeUiTest(1280, 1100) {
        show(state(status = null))
        assertTrue(!drawn("migrations:testMessageBtn:1"))
    }

    // ---- the flow ----

    @Test
    fun `a route with a wildcard asks for a value first and opens with the topic, the header and the stream notice`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(), sent)
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        waitForIdle()
        assertTrue(drawn("testMessageDialog"))
        assertTrue(sent.openings.isEmpty(), "nothing opens before the value is confirmed")
        onNodeWithTag("testMessageValue:0").performTextReplacement("d42")
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        val opening = sent.openings.single()
        assertEquals("devices/d42/answer", opening.topic)
        assertEquals(listOf(MqttHeader("correlation-id", "id-1")), opening.headers)
        assertTrue(opening.notice!!.text.startsWith("In the stream message this arrives as the application property correlation-id"), opening.notice.toString())
        assertTrue(!drawn("testMessageDialog"), "the sample dialog closes when the Publish dialog is to open")
    }

    @Test
    fun `a route without wildcards opens at once`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(), sent)
        onNodeWithTag("migrations:testMessageBtn:2").performClick()
        waitForIdle()
        assertTrue(!drawn("testMessageDialog"))
        assertEquals("devices/d1/status", sent.openings.single().topic)
    }

    @Test
    fun `a hash route opens with the rest of the topic`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(), sent)
        onNodeWithTag("migrations:testMessageBtn:4").performClick()
        onNodeWithTag("testMessageValue:0").performTextReplacement("room1/temp")
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        assertEquals("sensors/room1/temp", sent.openings.single().topic)
    }

    @Test
    fun `Cancel in the sample dialog opens nothing and the screen stays usable`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(), sent)
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        onNodeWithTag("testMessageCancelBtn").performClick()
        waitForIdle()
        assertTrue(!drawn("testMessageDialog"))
        assertTrue(sent.openings.isEmpty())
        onNodeWithTag("migrations:testMessageBtn:1").assertIsEnabled()
    }

    @Test
    fun `each opening takes a new value from the injected source`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        var n = 0
        show(state(), sent, CorrelationIdSource { "gen-${++n}" })
        repeat(2) {
            onNodeWithTag("migrations:testMessageBtn:2").performClick()
            waitForIdle()
        }
        assertEquals(listOf("gen-1", "gen-2"), sent.openings.map { it.headers.single().value })
    }

    // ---- the kind of the destination comes from the file's own declare steps ----

    @Test
    fun `a destination declared as a queue gets the queue notice`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(
            state(listOf(MigrationAction.DeclareQueue("temperatures", "quorum"), MigrationAction.DeclareBinding("amq.topic", "temperatures", "sensors.*.temp"))),
            sent,
        )
        onNodeWithTag("migrations:testMessageBtn:1").performClick()
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        assertEquals(
            "In the queue message this arrives as an entry of the headers table named correlation-id, not as the AMQP correlation_id property. Your service must read and write that header.",
            sent.openings.single().notice?.text,
        )
    }

    @Test
    fun `a destination the file does not declare gets no notice and no guess`() = runDesktopComposeUiTest(1280, 1100) {
        val sent = Sent()
        show(state(listOf(MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.answer"))), sent)
        onNodeWithTag("migrations:testMessageBtn:0").performClick()
        onNodeWithTag("testMessageOpenBtn").performClick()
        waitForIdle()
        val opening = sent.openings.single()
        assertNull(opening.notice)
        assertEquals("correlation-id", opening.headers.single().key, "the header is still there")
    }
}
