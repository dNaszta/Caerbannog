package dev.caerbannog.acceptance.viewer

import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.core.browser.MessageFilterCriteria
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadInspection
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.browser.ScanProgressUpdate
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PayloadViewerSteps {

    private val formatter = PayloadFormatter()
    private val messages = mutableMapOf<Long, StreamMessage>()
    private var selectedMessage: StreamMessage? = null
    private var currentViewKind: PayloadViewKind? = null
    private var showAll: Boolean = false
    private var clipboardContent: String? = null
    private var lastInspection: PayloadInspection? = null

    private val reader = FakeStreamMessageReader()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var viewModel: MessageBrowserViewModel? = null
    private var lastScanProgress: ScanProgressUpdate? = null
    private var listedMessages: List<StreamMessage> = emptyList()

    @Before
    fun setUp() {
        messages.clear()
        selectedMessage = null
        currentViewKind = null
        showAll = false
        clipboardContent = null
        lastInspection = null
        lastScanProgress = null
        listedMessages = emptyList()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        viewModel = null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun ensureViewModel(stream: String = "orders-events"): MessageBrowserViewModel = runBlocking {
        val existing = viewModel
        if (existing != null && existing.stream == stream) {
            return@runBlocking existing
        }
        MessageBrowserViewModel(
            reader = reader,
            vhost = "/",
            stream = stream,
            scope = scope,
        ).also { vm ->
            viewModel = vm
            vm.browseFirst()
            vm.state.first { it !is MessageBrowserState.Loading }
        }
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(profile: String) {
        // Connected context setup
    }

    @Given("I am browsing the stream {string}")
    fun iAmBrowsingTheStream(stream: String) {
        ensureViewModel(stream)
    }

    @Given("^the message at offset (\\d+) has the JSON body (.*)$")
    fun messageHasJsonBody(offset: Long, body: String) {
        val msg = StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = body.trim().encodeToByteArray(),
            properties = mapOf("content-type" to "application/json"),
        )
        messages[offset] = msg
    }

    @Given("^the message at offset (\\d+) has the text body \"(.*)\"$")
    fun messageHasTextBody(offset: Long, body: String) {
        val msg = StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = body.encodeToByteArray(),
            properties = mapOf("content-type" to "text/plain"),
        )
        messages[offset] = msg
    }

    @Given("^the message at offset (\\d+) has a binary body of (.*)$")
    fun messageHasBinaryBody(offset: Long, sizeDesc: String) {
        val size = 2150
        val binaryBytes = ByteArray(size) { idx -> (idx % 256).toByte() }
        binaryBytes[0] = 0x00
        binaryBytes[1] = 0x1F.toByte()
        binaryBytes[2] = 0x8B.toByte()
        val msg = StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = binaryBytes,
        )
        messages[offset] = msg
    }

    @Given("^the message at offset (\\d+) has content type \"(.*)\" and the body \"(.*)\"$")
    fun messageHasContentTypeAndBody(offset: Long, contentType: String, body: String) {
        val msg = StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = body.encodeToByteArray(),
            properties = mapOf("content-type" to contentType),
        )
        messages[offset] = msg
    }

    @Given("^the message at offset (\\d+) has a body larger than the display cap$")
    fun messageHasBodyLargerThanDisplayCap(offset: Long) {
        val largeText = "Large message body content. ".repeat(3000)
        val msg = StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = largeText.encodeToByteArray(),
        )
        messages[offset] = msg
    }

    @Given("^the message at offset (\\d+) has$")
    fun messageHasProperties(offset: Long, table: DataTable) {
        val props = table.asMap(String::class.java, String::class.java)
        val existing = messages[offset] ?: StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = """{"orderId":"A-1202"}""".encodeToByteArray(),
        )
        messages[offset] = existing.copy(properties = props.toMap())
    }

    @Given("^the application properties$")
    fun messageHasAppProperties(table: DataTable) {
        val appProps = table.asMap(String::class.java, String::class.java)
        val offset = selectedMessage?.offset ?: messages.keys.firstOrNull() ?: 1202L
        val existing = messages[offset] ?: StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = """{"orderId":"A-1202"}""".encodeToByteArray(),
        )
        messages[offset] = existing.copy(applicationProperties = appProps.toMap())
    }

    @Given("^the message at offset (\\d+) is selected$")
    fun messageAtOffsetIsSelected(offset: Long) {
        val msg = messages[offset] ?: StreamMessage(
            offset = offset,
            timestamp = 1700000000000L,
            payload = "heartbeat".encodeToByteArray(),
        ).also { messages[offset] = it }
        selectedMessage = msg
        updateInspection()
    }

    @When("^I select the message at offset (\\d+)$")
    fun iSelectMessageAtOffset(offset: Long) {
        val msg = checkNotNull(messages[offset]) { "Message at offset $offset not found" }
        selectedMessage = msg
        updateInspection()
    }

    @When("^I choose the \"(.*)\" view$")
    fun iChooseView(viewName: String) {
        currentViewKind = PayloadViewKind.valueOf(viewName.uppercase())
        updateInspection()
    }

    @When("^I choose \"(.*)\"$")
    fun iChooseAction(action: String) {
        when (action) {
            "Show all" -> {
                showAll = true
                updateInspection()
            }
            "Copy offset" -> {
                val msg = checkNotNull(selectedMessage) { "No message selected" }
                clipboardContent = formatter.copyOffset(msg)
            }
            "Copy body" -> {
                val msg = checkNotNull(selectedMessage) { "No message selected" }
                clipboardContent = formatter.copyBody(msg)
            }
            "Cancel scan" -> {
                viewModel?.cancelScan()
                lastScanProgress = viewModel?.scanProgress?.value
                val loaded = viewModel?.state?.value as? MessageBrowserState.Loaded
                if (loaded != null) {
                    listedMessages = loaded.messages
                }
            }
            else -> error("Unknown action: $action")
        }
    }

    private fun updateInspection() {
        val msg = selectedMessage ?: return
        lastInspection = formatter.inspect(
            message = msg,
            requestedKind = currentViewKind,
            showAll = showAll,
        )
    }

    @Then("^the body is shown indented, one field per line$")
    fun bodyShownIndentedOneFieldPerLine() {
        val inspection = checkNotNull(lastInspection)
        val lines = inspection.displayText.lines()
        assertTrue(lines.size > 1, "Expected indented multi-line JSON, got: ${inspection.displayText}")
        assertTrue(inspection.displayText.contains("  \"orderId\": \"A-1202\""))
        assertTrue(inspection.displayText.contains("  \"status\": \"created\""))
    }

    @Then("^in the \"(.*)\" view I see \"(.*)\"$")
    fun inViewISee(viewName: String, expected: String) {
        val kind = PayloadViewKind.valueOf(viewName.uppercase())
        val msg = checkNotNull(selectedMessage)
        val insp = formatter.inspect(msg, requestedKind = kind, showAll = showAll)
        assertEquals(expected, insp.displayText)
    }

    @Then("^in the \"(.*)\" view I see the bytes of \"(.*)\"$")
    fun inViewISeeBytesOf(viewName: String, text: String) {
        val kind = PayloadViewKind.valueOf(viewName.uppercase())
        val msg = checkNotNull(selectedMessage)
        val insp = formatter.inspect(msg, requestedKind = kind, showAll = showAll)
        val expectedHexBytes = text.encodeToByteArray().joinToString(" ") { "%02x".format(it) }
        assertTrue(insp.displayText.contains(expectedHexBytes))
    }

    @Then("^the body is shown in the \"(.*)\" view$")
    fun bodyShownInView(viewName: String) {
        val kind = PayloadViewKind.valueOf(viewName.uppercase())
        val inspection = checkNotNull(lastInspection)
        assertEquals(kind, inspection.currentKind)
    }

    @Then("^the \"Text\" view does not show garbled characters$")
    fun textViewDoesNotShowGarbledCharacters() {
        val msg = checkNotNull(selectedMessage)
        val text = formatter.formatText(msg.payload)
        assertNull(text, "formatText should be null for binary payloads")
    }

    @Then("^the \"JSON\" view tells me the body is not valid JSON$")
    fun jsonViewTellsNotValidJson() {
        val msg = checkNotNull(selectedMessage)
        val insp = formatter.inspect(msg, requestedKind = PayloadViewKind.JSON)
        assertNotNull(insp.validationWarning)
        assertTrue(insp.validationWarning!!.contains("not valid JSON", ignoreCase = true))
    }

    @Then("^the \"Text\" view still shows the body$")
    fun textViewStillShowsTheBody() {
        val msg = checkNotNull(selectedMessage)
        val insp = formatter.inspect(msg, requestedKind = PayloadViewKind.TEXT)
        assertEquals(msg.payload.decodeToString(), insp.displayText)
    }

    @Then("^only the first part of the body is shown$")
    fun onlyFirstPartOfBodyShown() {
        val inspection = checkNotNull(lastInspection)
        assertTrue(inspection.isCapped)
        assertTrue(inspection.displayText.length < checkNotNull(selectedMessage).payload.size)
    }

    @Then("^a \"Show all\" action is offered$")
    fun showAllActionOffered() {
        val inspection = checkNotNull(lastInspection)
        assertTrue(inspection.isCapped)
        assertTrue(inspection.canShowAll)
    }

    @Then("^the whole body is shown$")
    fun wholeBodyShown() {
        val inspection = checkNotNull(lastInspection)
        assertFalse(inspection.isCapped)
        assertEquals(checkNotNull(selectedMessage).payload.decodeToString(), inspection.displayText)
    }

    @Then("^I see the timestamp, the properties and the application properties$")
    fun seeTimestampPropertiesAndAppProperties() {
        val inspection = checkNotNull(lastInspection)
        assertTrue(inspection.timestamp > 0)
        assertEquals("application/json", inspection.properties["content-type"])
        assertEquals("A-1202", inspection.properties["message-id"])
        assertEquals("eu", inspection.applicationProperties["tenant"])
        assertEquals("checkout", inspection.applicationProperties["source"])
    }

    @Then("^the clipboard contains the offset (\\d+)$")
    fun clipboardContainsOffset(offset: Long) {
        assertEquals("$offset", clipboardContent)
    }

    @Then("^the clipboard contains the body exactly as published$")
    fun clipboardContainsBodyAsPublished() {
        val msg = checkNotNull(selectedMessage)
        assertEquals(msg.payload.decodeToString(), clipboardContent)
    }

    // --- Filter Scenarios (M4-02) ---

    @Given("^the next (\\d+) messages contain (\\d+) messages with \"(.*)\" in the body$")
    fun nextMessagesContainMatches(total: Int, matchCount: Int, needle: String) {
        val msgs = (0 until total).map { i ->
            val offset = 1000L + i
            val body = if (i < matchCount) "message-$offset with $needle" else "message-$offset"
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = body.encodeToByteArray(),
            )
        }
        reader.seed("/", "orders-events", msgs)
        viewModel = null
        ensureViewModel("orders-events")
    }

    @When("^I filter by the text \"(.*)\"$")
    fun iFilterByText(text: String) = runBlocking {
        val vm = ensureViewModel("orders-events")
        vm.filter(MessageFilterCriteria.Text(text))
        kotlinx.coroutines.withTimeout(5000) {
            vm.scanProgress.filterNotNull().first { !it.isRunning }
        }
        val loaded = vm.state.value as MessageBrowserState.Loaded
        listedMessages = loaded.messages
        lastScanProgress = vm.scanProgress.value
    }

    @Then("^only those (\\d+) messages are listed$")
    fun onlyThoseMessagesListed(count: Int) {
        assertEquals(count, listedMessages.size)
    }

    @Then("^the page says how many messages were scanned$")
    fun pageSaysHowManyMessagesScanned() {
        val progress = checkNotNull(lastScanProgress)
        assertTrue(progress.scannedCount > 0)
        val loaded = viewModel?.state?.value as? MessageBrowserState.Loaded
        val notice = loaded?.filterNotice ?: progress.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("scanned", ignoreCase = true) || notice.contains("${progress.scannedCount}"))
    }

    @Given("^messages with application property \"(.*)\" set to \"(.*)\" and to \"(.*)\"$")
    fun messagesWithAppPropertySetTo(prop: String, val1: String, val2: String) {
        val msgs = (0 until 20).map { i ->
            val offset = 2000L + i
            val value = if (i % 2 == 0) val1 else val2
            StreamMessage(
                offset = offset,
                timestamp = 1700000000000L + i * 1000L,
                payload = "body-$offset".encodeToByteArray(),
                applicationProperties = mapOf(prop to value),
            )
        }
        reader.seed("/", "orders-events", msgs)
        viewModel = null
        ensureViewModel("orders-events")
    }

    @When("^I filter by application property \"(.*)\" equal to \"(.*)\"$")
    fun iFilterByApplicationProperty(prop: String, value: String) = runBlocking {
        val vm = ensureViewModel("orders-events")
        vm.filter(MessageFilterCriteria.ApplicationProperty(prop, value))
        kotlinx.coroutines.withTimeout(5000) {
            vm.scanProgress.filterNotNull().first { !it.isRunning }
        }
        val loaded = vm.state.value as MessageBrowserState.Loaded
        listedMessages = loaded.messages
        lastScanProgress = vm.scanProgress.value
    }

    @Then("^only messages with \"(.*)\" equal to \"(.*)\" are listed$")
    fun onlyMessagesWithAppPropListed(prop: String, value: String) {
        assertTrue(listedMessages.isNotEmpty())
        assertTrue(listedMessages.all { it.applicationProperties[prop] == value })
    }

    @Given("^a stream with ([\\d,]+) messages and no match for \"(.*)\"$")
    fun streamWithMessagesAndNoMatch(countStr: String, needle: String) {
        val count = countStr.replace(",", "").toInt()
        val msgs = (0 until count).map { i ->
            StreamMessage(
                offset = i.toLong(),
                timestamp = 1700000000000L + i * 1000L,
                payload = "noise-$i".encodeToByteArray(),
            )
        }
        reader.seed("/", "orders-events", msgs)
        ensureViewModel("orders-events")
    }

    @Then("^the scan stops after ([\\d,]+) messages$")
    fun scanStopsAfterMessages(countStr: String) {
        val expected = countStr.replace(",", "").toInt()
        val progress = checkNotNull(lastScanProgress)
        assertEquals(expected, progress.scannedCount)
    }

    @Then("^the page says the scan limit was reached and the rest was not searched$")
    fun pageSaysScanLimitReached() {
        val progress = checkNotNull(lastScanProgress)
        assertTrue(progress.limitReached)
        val loaded = viewModel?.state?.value as? MessageBrowserState.Loaded
        val notice = loaded?.filterNotice ?: progress.notice
        assertNotNull(notice)
        assertTrue(notice!!.contains("limit", ignoreCase = true))
        assertTrue(notice.contains("not searched", ignoreCase = true))
    }

    @Given("^a long scan is running$")
    fun longScanIsRunning() = runBlocking {
        val msgs = (0 until 20_000).map { i ->
            StreamMessage(
                offset = i.toLong(),
                timestamp = 1700000000000L + i * 1000L,
                payload = if (i == 5) "needle-5".encodeToByteArray() else "noise-$i".encodeToByteArray(),
            )
        }
        reader.seed("/", "orders-events", msgs)
        val vm = ensureViewModel("orders-events")
        vm.filter(MessageFilterCriteria.Text("needle"))
        kotlinx.coroutines.withTimeout(5000) {
            vm.scanProgress.filterNotNull().first { it.scannedCount > 0 }
        }
    }

    @Then("^the scan stops at once$")
    fun scanStopsAtOnce() {
        val progress = viewModel?.scanProgress?.value
        assertFalse(progress?.isRunning == true)
    }

    @Then("^the messages found so far stay on screen$")
    fun messagesFoundStayOnScreen() {
        val loaded = viewModel?.state?.value as? MessageBrowserState.Loaded
        assertNotNull(loaded)
        assertTrue(loaded!!.messages.isNotEmpty())
    }
}
