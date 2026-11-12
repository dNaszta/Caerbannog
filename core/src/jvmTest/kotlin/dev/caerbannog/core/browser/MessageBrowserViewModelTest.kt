package dev.caerbannog.core.browser

import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageBrowserViewModelTest {

    private val reader = FakeStreamMessageReader()
    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)

    @BeforeEach
    fun setUp() {
        reader.addMessages(
            vhost = "/",
            stream = "events",
            count = 250,
            startOffset = 0L,
            startTimestamp = 1700000000000L,
            timestampStepMs = 1000L
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun createViewModel(pageSize: Int = 100): MessageBrowserViewModel {
        return MessageBrowserViewModel(
            reader = reader,
            vhost = "/",
            stream = "events",
            scope = scope,
            initialPageSize = pageSize,
            approximateTotalProvider = { _, _ -> 250L }
        )
    }

    @Test
    fun `initial state is Loading and transitions to Loaded on browseFirst`() = scope.runTest {
        val vm = createViewModel()
        vm.browseFirst()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(100, loaded.messages.size)
        assertEquals(0L, loaded.messages.first().offset)
        assertEquals(99L, loaded.messages.last().offset)
        assertEquals(0L, loaded.streamFirstOffset)
        assertEquals(249L, loaded.streamLastOffset)
        assertEquals(250L, loaded.approximateTotal)
        assertFalse(loaded.canGoPrevious, "Previous is disabled at the start of the stream")
        assertTrue(loaded.canGoNext, "Next is enabled when more messages exist")
        assertEquals("Offsets 0 to 99 of about 250", loaded.summaryText)
    }

    @Test
    fun `browseLastPage starts at streamLastOffset minus pageSize plus 1`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseLastPage()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(100, loaded.messages.size)
        assertEquals(150L, loaded.messages.first().offset)
        assertEquals(249L, loaded.messages.last().offset)
        assertTrue(loaded.canGoPrevious)
        assertFalse(loaded.canGoNext, "Next is disabled at the end of the stream")
    }

    @Test
    fun `browseOffset loads messages starting from requested offset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseOffset(120L)
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(100, loaded.messages.size)
        assertEquals(120L, loaded.messages.first().offset)
        assertEquals(219L, loaded.messages.last().offset)
    }

    @Test
    fun `nextPage loads the page starting immediately after the current last offset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseFirst()
        advanceUntilIdle()

        vm.nextPage()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(100, loaded.messages.size)
        assertEquals(100L, loaded.messages.first().offset)
        assertEquals(199L, loaded.messages.last().offset)
    }

    @Test
    fun `previousPage goes back one page and clamps to streamFirstOffset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseOffset(50L)
        advanceUntilIdle()

        vm.previousPage()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(100, loaded.messages.size)
        assertEquals(0L, loaded.messages.first().offset)
        assertEquals(99L, loaded.messages.last().offset)
        assertFalse(loaded.canGoPrevious)
    }

    @Test
    fun `jumpToOffset jumps directly to specified offset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseFirst()
        advanceUntilIdle()

        vm.jumpToOffset(180L)
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(70, loaded.messages.size)
        assertEquals(180L, loaded.messages.first().offset)
        assertEquals(249L, loaded.messages.last().offset)
        assertFalse(loaded.canGoNext)
    }

    @Test
    fun `browse offset outside stream range sets OutsideRange state and offers nearest valid offset`() = scope.runTest {
        val vm = createViewModel()
        vm.browseOffset(300L)
        advanceUntilIdle()

        val outside = assertInstanceOf(MessageBrowserState.OutsideRange::class.java, vm.state.value)
        assertEquals(300L, outside.requestedOffset)
        assertEquals(0L..249L, outside.validRange)
        assertEquals(249L, outside.nearestOffset)

        // Jumping to nearest valid offset transitions to Loaded
        vm.jumpToNearestValidOffset()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(249L, loaded.messages.last().offset)
    }

    @Test
    fun `setPageSize changes page size and reloads from current first offset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseFirst()
        advanceUntilIdle()

        vm.setPageSize(25)
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(25, loaded.pageSize)
        assertEquals(25, loaded.messages.size)
        assertEquals(0L, loaded.messages.first().offset)
        assertEquals(24L, loaded.messages.last().offset)
    }

    @Test
    fun `empty stream transitions to Empty state`() = scope.runTest {
        reader.createEmptyStream("/", "empty-stream")
        val vm = MessageBrowserViewModel(
            reader = reader,
            vhost = "/",
            stream = "empty-stream",
            scope = scope,
        )
        vm.browseFirst()
        advanceUntilIdle()

        val empty = assertInstanceOf(MessageBrowserState.Empty::class.java, vm.state.value)
        assertEquals("empty-stream", empty.stream)
    }

    @Test
    fun `seeking timestamp after last message transitions to NoMessagesAtOrAfterTime`() = scope.runTest {
        val vm = createViewModel()
        vm.browseTimestamp(1900000000000L)
        advanceUntilIdle()

        val noMsg = assertInstanceOf(MessageBrowserState.NoMessagesAtOrAfterTime::class.java, vm.state.value)
        assertEquals(1900000000000L, noMsg.epochMs)
    }

    @Test
    fun `retention moving firstOffset during paging sets retentionShiftNotice and clamps`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseOffset(100L)
        advanceUntilIdle()

        // Simulate retention truncating stream before offset 150
        reader.removeBefore("/", "events", 150L)

        vm.previousPage()
        advanceUntilIdle()

        val loaded = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(150L, loaded.streamFirstOffset)
        assertEquals(150L, loaded.messages.first().offset)
        assertNotNull(loaded.retentionShiftNotice)
        assertTrue(loaded.retentionShiftNotice!!.contains("150"))
    }

    @Test
    fun `selectMessage selects or unselects message by offset`() = scope.runTest {
        val vm = createViewModel(pageSize = 100)
        vm.browseFirst()
        advanceUntilIdle()

        vm.selectMessage(42L)
        val loaded1 = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertEquals(42L, loaded1.selectedMessage?.offset)

        vm.selectMessage(null)
        val loaded2 = assertInstanceOf(MessageBrowserState.Loaded::class.java, vm.state.value)
        assertNull(loaded2.selectedMessage)
    }

    @Test
    fun `state already carries the notice when a finished scan progress is published`() = runBlocking {
        val noMatchReader = FakeStreamMessageReader()
        noMatchReader.addMessages("/", "events", 300, 0L, 1700000000000L, 1000L)
        // The VM runs on another thread; the collector is Unconfined so it reads the state on the
        // publishing thread the moment the progress changes, like a UI observer would.
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = MessageBrowserViewModel(
                reader = noMatchReader,
                vhost = "/",
                stream = "events",
                scope = ownScope,
                approximateTotalProvider = { _, _ -> 300L },
            )
            vm.browseFirst()
            vm.state.first { it is MessageBrowserState.Loaded }
            val seen = mutableListOf<Pair<ScanProgressUpdate, String?>>()
            val collector = observerScope.launch {
                vm.scanProgress.filterNotNull().collect { p ->
                    seen += p to (vm.state.value as? MessageBrowserState.Loaded)?.filterNotice
                }
            }
            vm.filter(MessageFilterCriteria.Text("needle"), scanLimit = 100)
            vm.scanProgress.filterNotNull().first { !it.isRunning }
            collector.cancel()
            val finished = seen.filter { !it.first.isRunning }
            assertTrue(finished.isNotEmpty())
            finished.forEach { (progress, notice) -> assertEquals(progress.notice, notice) }
        } finally {
            ownScope.cancel()
            observerScope.cancel()
        }
    }
}
