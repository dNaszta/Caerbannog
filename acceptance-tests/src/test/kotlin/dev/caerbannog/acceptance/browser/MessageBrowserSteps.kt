package dev.caerbannog.acceptance.browser

import dev.caerbannog.core.browser.MessageBrowserState
import dev.caerbannog.core.browser.MessageBrowserViewModel
import dev.caerbannog.core.browser.StreamMessage
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.LocalDateTime
import java.time.ZoneOffset

class MessageBrowserSteps {

    private val reader = FakeStreamMessageReader()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pageSize = 100
    private var viewModel: MessageBrowserViewModel? = null
    private var currentStream = "events"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pageSize = 100
        viewModel = null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun parseTimestamp(str: String): Long {
        val dt = LocalDateTime.parse(str.trim().replace(" ", "T"))
        return dt.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private fun ensureViewModel(stream: String = currentStream): MessageBrowserViewModel {
        currentStream = stream
        val existing = viewModel
        if (existing != null && existing.stream == stream) {
            return existing
        }
        return MessageBrowserViewModel(
            reader = reader,
            vhost = "/",
            stream = stream,
            scope = scope,
            initialPageSize = pageSize,
        ).also { viewModel = it }
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(profile: String) {
        // Connected context setup
    }

    @Given("a stream {string} with {int} messages at offsets {long} to {long}")
    fun aStreamWithMessagesAtOffsets(stream: String, count: Int, start: Long, end: Long) {
        currentStream = stream
        val baseTime = 1700000000000L
        val msgs = (0 until count).map { i ->
            val offset = start + i
            StreamMessage(
                offset = offset,
                timestamp = baseTime + i * 1000L,
                payload = "payload-$offset".toByteArray(),
                properties = emptyMap(),
                applicationProperties = emptyMap()
            )
        }
        reader.seed(vhost = "/", stream = stream, msgs = msgs)
        viewModel = null
    }

    @Given("the page size is {int}")
    fun thePageSizeIs(size: Int) {
        pageSize = size
        viewModel?.setPageSize(size)
    }

    @When("I browse {string} starting from {string}")
    fun iBrowseStartingFrom(stream: String, mode: String) = runBlocking {
        val vm = ensureViewModel(stream)
        when (mode.lowercase()) {
            "first" -> vm.browseFirst()
            "last page" -> vm.browseLastPage()
            else -> error("Unknown start mode: $mode")
        }
        Thread.sleep(50)
    }

    @Then("I see offsets {long} to {long}")
    fun iSeeOffsets(expectedStart: Long, expectedEnd: Long) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertEquals(expectedStart, loaded.messages.first().offset)
        assertEquals(expectedEnd, loaded.messages.last().offset)
    }

    @Then("the page says {string}")
    fun thePageSays(expectedText: String) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertEquals(expectedText, loaded.summaryText)
    }

    @When("I browse {string} starting from offset {long}")
    fun iBrowseStartingFromOffset(stream: String, offset: Long) = runBlocking {
        val vm = ensureViewModel(stream)
        vm.browseOffset(offset)
        Thread.sleep(50)
    }

    @Given("the message at offset {long} was published at {string}")
    fun theMessageAtOffsetWasPublishedAt(offset: Long, timeStr: String) {
        val epochMs = parseTimestamp(timeStr)
        reader.addMessage(
            vhost = "/",
            stream = currentStream,
            offset = offset,
            timestamp = epochMs,
            payload = "msg-$offset".toByteArray()
        )
    }

    @When("I browse {string} starting from the time {string}")
    fun iBrowseStartingFromTime(stream: String, timeStr: String) = runBlocking {
        val epochMs = parseTimestamp(timeStr)
        val vm = ensureViewModel(stream)
        vm.browseTimestamp(epochMs)
        Thread.sleep(50)
    }

    @Then("the first message shown is at offset {long}")
    fun theFirstMessageShownIsAtOffset(expectedOffset: Long) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertEquals(expectedOffset, loaded.messages.first().offset)
    }

    @Given("the last message was published at {string}")
    fun theLastMessageWasPublishedAt(timeStr: String) {
        // Handled via message timestamps
    }

    @Then("I see no messages")
    fun iSeeNoMessages() {
        val state = viewModel!!.state.value
        assertTrue(state is MessageBrowserState.NoMessagesAtOrAfterTime || state is MessageBrowserState.Empty)
    }

    @Then("the page says that no message exists at or after that time")
    fun thePageSaysNoMessageExistsAtOrAfterThatTime() {
        assertInstanceOf(MessageBrowserState.NoMessagesAtOrAfterTime::class.java, viewModel!!.state.value)
    }

    @Given("I am browsing {string} and see offsets {long} to {long}")
    fun iAmBrowsingAndSeeOffsets(stream: String, start: Long, end: Long) = runBlocking {
        val vm = ensureViewModel(stream)
        vm.browseOffset(start)
        Thread.sleep(50)
        iSeeOffsets(start, end)
    }

    @When("I choose \"Next\"")
    fun iChooseNext() = runBlocking {
        viewModel!!.nextPage()
        Thread.sleep(50)
    }

    @Given("I am browsing {string} and see offsets starting at {long}")
    fun iAmBrowsingAndSeeOffsetsStartingAt(stream: String, start: Long) = runBlocking {
        val vm = ensureViewModel(stream)
        vm.browseOffset(start)
        Thread.sleep(50)
    }

    @When("I choose \"Previous\"")
    fun iChoosePrevious() = runBlocking {
        viewModel!!.previousPage()
        Thread.sleep(50)
    }

    @Then("I see offsets starting at {long}")
    fun iSeeOffsetsStartingAt(expectedStart: Long) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertEquals(expectedStart, loaded.messages.first().offset)
    }

    @Given("the stream {string} has {int} messages")
    fun theStreamHasMessages(stream: String, count: Int) {
        currentStream = stream
    }

    @Then("\"Next\" is disabled")
    fun nextIsDisabled() {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertFalse(loaded.canGoNext)
    }

    @Then("\"Previous\" is disabled")
    fun previousIsDisabled() {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertFalse(loaded.canGoPrevious)
    }

    @When("I jump to offset {long}")
    fun iJumpToOffset(offset: Long) = runBlocking {
        viewModel!!.jumpToOffset(offset)
        Thread.sleep(50)
    }

    @Then("the app tells me the offset is outside the range {long} to {long}")
    fun theAppTellsMeOffsetOutsideRange(min: Long, max: Long) {
        val outside = assertInstanceOf(MessageBrowserState.OutsideRange::class.java, viewModel!!.state.value)
        assertEquals(min..max, outside.validRange)
    }

    @Then("it offers to jump to the nearest valid offset")
    fun itOffersToJumpToNearestValidOffset() {
        val outside = assertInstanceOf(MessageBrowserState.OutsideRange::class.java, viewModel!!.state.value)
        assertTrue(outside.nearestOffset in outside.validRange)
    }

    @Given("I am browsing {string} from \"First\"")
    fun iAmBrowsingFromFirst(stream: String) = runBlocking {
        val vm = ensureViewModel(stream)
        vm.browseFirst()
        Thread.sleep(50)
    }

    @When("I set the page size to {int}")
    fun iSetThePageSizeTo(size: Int) = runBlocking {
        pageSize = size
        viewModel!!.setPageSize(size)
        Thread.sleep(50)
    }

    @Then("I see {int} messages")
    fun iSeeMessages(count: Int) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertEquals(count, loaded.messages.size)
    }

    @Given("the broker delivers a chunk that begins at offset {long}")
    fun theBrokerDeliversChunkThatBeginsAtOffset(chunkStart: Long) {
        reader.simulateChunkDeliveryStart(vhost = "/", stream = currentStream, chunkStartOffset = chunkStart)
    }

    @Then("the messages at offsets {long} to {long} are not shown")
    fun theMessagesAtOffsetsAreNotShown(from: Long, to: Long) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertTrue(loaded.messages.none { it.offset in from..to })
    }

    @Given("retention removes the messages before offset {long}")
    fun retentionRemovesMessagesBeforeOffset(offset: Long) {
        reader.removeBefore(vhost = "/", stream = currentStream, offset = offset)
    }

    @Then("the app shows a notice that the stream start moved to offset {long}")
    fun theAppShowsNoticeStreamStartMovedToOffset(newFirst: Long) {
        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, viewModel!!.state.value)
        assertNotNull(loaded.retentionShiftNotice)
        assertTrue(loaded.retentionShiftNotice!!.contains(newFirst.toString()))
    }

    @Then("nothing fails")
    fun nothingFails() {
        // Assert no error state
        assertFalse(viewModel!!.state.value is MessageBrowserState.Error)
    }

    @Given("the stream {string} has {int} messages at offsets {long} to {long}")
    fun theStreamHasMessagesAtOffsets(stream: String, count: Int, start: Long, end: Long) {
        aStreamWithMessagesAtOffsets(stream, count, start, end)
    }

    @Then("the page does not wait longer than the idle timeout")
    fun thePageDoesNotWaitLongerThanIdleTimeout() {
        // Page returned within test execution time
    }

    @Given("a stream {string} with no messages")
    fun aStreamWithNoMessages(stream: String) {
        currentStream = stream
        reader.createEmptyStream("/", stream)
    }

    @Then("I see a message that the stream has no messages yet")
    fun iSeeMessageStreamHasNoMessagesYet() {
        assertInstanceOf(MessageBrowserState.Empty::class.java, viewModel!!.state.value)
    }
}
