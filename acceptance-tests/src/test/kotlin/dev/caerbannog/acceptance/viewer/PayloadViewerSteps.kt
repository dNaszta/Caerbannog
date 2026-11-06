package dev.caerbannog.acceptance.viewer

import dev.caerbannog.core.browser.JsonFormatResult
import dev.caerbannog.core.browser.PayloadFormatter
import dev.caerbannog.core.browser.PayloadInspection
import dev.caerbannog.core.browser.PayloadViewKind
import dev.caerbannog.core.browser.StreamMessage
import io.cucumber.datatable.DataTable
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
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

    @Before
    fun setUp() {
        messages.clear()
        selectedMessage = null
        currentViewKind = null
        showAll = false
        clipboardContent = null
        lastInspection = null
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(profile: String) {
        // Connected context setup
    }

    @Given("I am browsing the stream {string}")
    fun iAmBrowsingTheStream(stream: String) {
        // Stream context setup
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
        // 2.1 KB of non-text binary bytes
        val size = 2150
        val binaryBytes = ByteArray(size) { idx -> (idx % 256).toByte() }
        binaryBytes[0] = 0x00
        binaryBytes[1] = 0x1F.toByte()
        binaryBytes[2] = 0x8B.toByte() // gzip header bytes
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
        val largeText = "Large message body content. ".repeat(3000) // ~84 KB
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
        val msg = checkNotNull(selectedMessage) { "No message selected" }
        when (action) {
            "Show all" -> {
                showAll = true
                updateInspection()
            }
            "Copy offset" -> {
                clipboardContent = formatter.copyOffset(msg)
            }
            "Copy body" -> {
                clipboardContent = formatter.copyBody(msg)
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
}
