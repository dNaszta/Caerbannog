package dev.caerbannog.ui.streams

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.streams.ConsumerRowState
import dev.caerbannog.core.streams.ConsumerStatus
import dev.caerbannog.core.streams.StreamClientsTexts
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-C step 2: the Publishers and consumers tab composes only the rows near the viewport (one LazyColumn for the whole tab body).
 * Fake state only, no broker. Written first and red today because the `Column` composes every row.
 *
 * Contract the tests pin (so the developer is free in the rest):
 *  - the page body is ONE node with the semantics action ScrollToIndex (a LazyColumn); no other node of the screen has it. Its tag is free;
 *  - the row cells keep their tags: `consumerCell:<key>:<Column>`, `statusHint:<key>`, `statusHintPopup:<key>`, `consumersCaption`, `consumersHeader:<key>`;
 *  - the brief names the count of `consumerRow:*` nodes; no such tag exists today, so the count is of the `consumerCell:*:Name` nodes (one per row).
 * The viewport is the 1280x800 window of the other client tests.
 */
@OptIn(ExperimentalTestApi::class)
class StreamClientsLazyUiTest {

    private companion object {
        const val ROWS = 5000

        /** Composed rows allowed at 1280x800 (about 8 are visible): the buffer of the list plus slack. */
        const val MAX_COMPOSED_ROWS = 100
        const val ROWS_IN_FOCUS_TEST = 300
        const val WIDTH = 1280
        const val HEIGHT = 800
    }

    /** Every third row is Behind (has the "i"), the last row is Behind too, so it can be scrolled to and focused. */
    private fun rows(n: Int = ROWS, offset: String = "100"): List<ConsumerRowState> = (0 until n).map { i ->
        val behind = i % 3 == 0 || i == n - 1
        consumer(
            "c$i", "c$i", offset = offset, behind = if (behind) "${100 + i}" else "0",
            status = if (behind) ConsumerStatus.Behind(100L + i) else ConsumerStatus.CaughtUp,
        )
    }

    private fun DesktopComposeUiTest.names(): List<String> =
        onAllNodes(SemanticsMatcher("consumer name cell") { tag(it).let { t -> t.startsWith("consumerCell:") && t.endsWith(":Name") } }, useUnmergedTree = true)
            .fetchSemanticsNodes().map { tag(it).removePrefix("consumerCell:").removeSuffix(":Name") }

    private fun tag(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.TestTag) ?: ""

    private fun DesktopComposeUiTest.exists(tag: String): Boolean = onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.list() = onNode(hasScrollToIndexAction())

    /** The index of the last item of the page list, found by probing (the item structure is the developer's). */
    private fun DesktopComposeUiTest.lastItemIndex(): Int {
        fun ok(index: Int) = runCatching { list().performScrollToIndex(index) }.isSuccess
        var hi = 1
        while (ok(hi)) hi *= 2
        var lo = hi / 2 // ok(lo) holds (lo = 0 when hi = 1)
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (ok(mid)) lo = mid else hi = mid }
        return lo
    }

    private fun DesktopComposeUiTest.scrollToEnd() { list().performScrollToIndex(lastItemIndex()); waitForIdle() }
    private fun DesktopComposeUiTest.scrollToTop() { list().performScrollToIndex(0); waitForIdle() }
    private fun DesktopComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun DesktopComposeUiTest.image(): BufferedImage = onAllNodes(isRoot())[0].captureToImage().toAwtImage()

    // --- 1 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `with 5000 consumers only the rows near the viewport are composed and the caption still says 5000`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        clientsScreen(content(consumers = rows()))
        waitForIdle()
        val composed = names()
        assertTrue(composed.size <= MAX_COMPOSED_ROWS, "expected at most $MAX_COMPOSED_ROWS composed consumer rows of $ROWS, but ${composed.size} are composed")
        assertTrue(composed.isNotEmpty() && composed.first() == "c0", "the first row is shown: $composed")
        val caption = onNodeWithTag("consumersCaption", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.map { it.text }
        assertEquals(listOf("Consumers", "$ROWS"), caption, "the caption counts all rows, not the composed ones")
    }

    // --- 2 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `scrolling to the end composes the last row and drops the first, and the caption and header are there again at the top`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        clientsScreen(content(consumers = rows()))
        waitForIdle()
        assertTrue(exists("consumerCell:c0:Name"))
        assertTrue(!exists("consumerCell:c${ROWS - 1}:Name"), "the last row is not composed before the scroll")

        scrollToEnd()
        consumerCell("c${ROWS - 1}", "Name").assertExists("the last row exists after scrolling to the end")
        consumerCell("c${ROWS - 1}", "Status").assertTextEquals("5,099 behind")
        consumerCell("c0", "Name").assertDoesNotExist()
        assertTrue(names().size <= MAX_COMPOSED_ROWS, "still few rows composed at the end: ${names().size}")
        onNodeWithTag("clientsFootnote", useUnmergedTree = true).assertExists("the footnote stays at the end of the list")

        scrollToTop()
        consumerCell("c0", "Name").assertExists()
        onNodeWithTag("consumersCaption", useUnmergedTree = true).assertExists("the caption is there at the top")
        onNodeWithTag("consumersHeader:Status", useUnmergedTree = true).assertExists("the header is there at the top")
        assertTrue(!exists("consumerCell:c${ROWS - 1}:Name"), "the last row left the composition again")
    }

    // --- 3 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a Behind row that was not composed first opens its hint on focus and Escape closes it`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        clientsScreen(content(consumers = rows()))
        waitForIdle()
        val key = "c${ROWS - 1}"
        assertTrue(!exists("statusHint:$key"), "the row is not composed before the scroll")

        scrollToEnd()
        onNodeWithTag("statusHint:$key", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("statusHint:$key", useUnmergedTree = true).assertIsFocused()
        onNodeWithTag("statusHintPopup:$key", useUnmergedTree = true).assertTextEquals(HINT_TEXT)

        onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        waitForIdle()
        onNodeWithTag("statusHintPopup:$key", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- 4 ------------------------------------------------------------------------------------------------------------------

    /** The same bounds rule as [StreamClientsHintPopupUiTest]: inside the window and the list, really drawn; off the header when it is composed. */
    private fun DesktopComposeUiTest.popupProblems(label: String, key: String, without: BufferedImage): List<String> {
        val problems = mutableListOf<String>()
        onNodeWithTag("statusHint:$key", useUnmergedTree = true).requestFocus()
        waitForIdle()
        val popup = onNodeWithTag("statusHintPopup:$key", useUnmergedTree = true)
        popup.assertExists("$label row ($key): popup node exists")
        val rect = bounds("statusHintPopup:$key")
        val iRect = bounds("statusHint:$key")
        val listRect = list().fetchSemanticsNode().boundsInRoot
        val window = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        runCatching { popup.assertIsDisplayed() }.exceptionOrNull()?.let { problems += "$label ($key): not displayed: ${it.message}" }
        if (rect.width < 40f || rect.height < 20f) problems += "$label ($key): popup bounds $rect are too small to hold the hint text"
        if (rect.left < window.left || rect.top < window.top || rect.right > window.right || rect.bottom > window.bottom) problems += "$label ($key): popup $rect leaves the window $window"
        if (rect.left < listRect.left || rect.right > listRect.right || rect.top < listRect.top || rect.bottom > listRect.bottom) problems += "$label ($key): popup $rect leaves the list viewport $listRect (clipped)"
        val above = rect.bottom <= iRect.top + 1f
        val beside = rect.right <= iRect.left + 1f || rect.left >= iRect.right - 1f
        if (!above && !beside) problems += "$label ($key): popup $rect is neither above nor beside the i $iRect"
        listOf("Consumer", "Connection", "Offset", "Behind", "Status").mapNotNull { runCatching { bounds("consumersHeader:$it") }.getOrNull() }.forEach { header ->
            if (rect.overlaps(header)) problems += "$label ($key): popup $rect covers the table header $header"
        }
        val withPopup = image()
        var changedOutsideI = 0
        for (x in 0 until WIDTH) for (y in 0 until HEIGHT) {
            val nearI = x >= iRect.left - 6 && x <= iRect.right + 6 && y >= iRect.top - 6 && y <= iRect.bottom + 6
            if (!nearI && withPopup.getRGB(x, y) != without.getRGB(x, y)) changedOutsideI++
        }
        var changedInside = 0; var totalInside = 0
        for (x in rect.left.toInt().coerceIn(0, WIDTH - 1) until rect.right.toInt().coerceIn(0, WIDTH)) for (y in rect.top.toInt().coerceIn(0, HEIGHT - 1) until rect.bottom.toInt().coerceIn(0, HEIGHT)) {
            totalInside++
            if (withPopup.getRGB(x, y) != without.getRGB(x, y)) changedInside++
        }
        if (totalInside == 0 || changedInside.toDouble() / totalInside < 0.9) problems += "$label ($key): only $changedInside of $totalInside pixels inside the popup bounds $rect differ from the picture without it (partly clipped?)"
        if (changedOutsideI < 1500) problems += "$label ($key): only $changedOutsideI pixels outside the i changed when the popup opened; nothing visible was drawn"
        return problems
    }

    /** Behind rows fully inside the list viewport, first and last by position. */
    private fun DesktopComposeUiTest.visibleBehindRows(): List<String> {
        val view = list().fetchSemanticsNode().boundsInRoot
        return onAllNodes(SemanticsMatcher("hint") { tag(it).startsWith("statusHint:") && !tag(it).startsWith("statusHintPopup:") }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= view.top + 1 && it.boundsInRoot.bottom <= view.bottom - 1 }
            .sortedBy { it.boundsInRoot.top }.map { tag(it).removePrefix("statusHint:") }
    }

    @Test
    fun `the hint popup is visible for the first and the last fully visible Behind row, at the top and after scrolling to the end`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        clientsScreen(content(consumers = rows()))
        waitForIdle()
        val problems = mutableListOf<String>()
        for ((place, scroll) in listOf<Pair<String, DesktopComposeUiTest.() -> Unit>>("top" to { scrollToTop() }, "end" to { scrollToEnd() })) {
            scroll()
            val without = image()
            val behind = visibleBehindRows()
            assertTrue(behind.size >= 2, "$place: at least two fully visible Behind rows expected, got $behind")
            problems += popupProblems("$place first", behind.first(), without)
            onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            waitForIdle()
            problems += popupProblems("$place last", behind.last(), without)
            onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
            waitForIdle()
        }
        assertTrue(problems.isEmpty(), "The hint popup is not usable in a long list:\n" + problems.joinToString("\n"))
    }

    // --- 5 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a refresh with the same keys keeps the first visible row and shows its new number, and a row inserted above does not move the view`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val shown = clientsScreen(content(consumers = rows(offset = "100")))
        waitForIdle()
        list().performScrollToIndex(lastItemIndex() / 2)
        waitForIdle()
        val listTop = list().fetchSemanticsNode().boundsInRoot.top
        val firstVisible = names().filter { bounds("consumerCell:$it:Name").top >= listTop }.minByOrNull { bounds("consumerCell:$it:Name").top }!!
        val topBefore = bounds("consumerCell:$firstVisible:Name").top
        assertTrue(firstVisible != "c0", "scrolled away from the top: $firstVisible")
        consumerCell(firstVisible, "Offset").assertTextEquals("100")

        shown.state = content(consumers = rows(offset = "777"))
        waitForIdle()
        consumerCell(firstVisible, "Offset").assertTextEquals("777")
        assertEquals(topBefore, bounds("consumerCell:$firstVisible:Name").top, "the row did not move on a refresh")

        shown.state = content(consumers = listOf(consumer("new0", "new0", offset = "1"), consumer("new1", "new1", offset = "1")) + rows(offset = "777"))
        waitForIdle()
        assertEquals(topBefore, bounds("consumerCell:$firstVisible:Name").top, "rows inserted above the view must not move the row the user looks at")
        consumerCell("new0", "Name").assertDoesNotExist()
    }

    // --- 6 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a refresh with the same keys keeps the focus and the open popup, and a row that scrolled out comes back closed and unfocused`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val shown = clientsScreen(content(consumers = rows(ROWS_IN_FOCUS_TEST)))
        waitForIdle()
        onNodeWithTag("statusHint:c0", useUnmergedTree = true).requestFocus()
        waitForIdle()
        onNodeWithTag("statusHintPopup:c0", useUnmergedTree = true).assertExists()

        shown.state = content(consumers = rows(ROWS_IN_FOCUS_TEST, offset = "555"))
        waitForIdle()
        consumerCell("c0", "Offset").assertTextEquals("555")
        onNodeWithTag("statusHint:c0", useUnmergedTree = true).assertIsFocused()
        onNodeWithTag("statusHintPopup:c0", useUnmergedTree = true).assertTextEquals(HINT_TEXT)

        // Pinned (Compose behaviour, fine for keyboard users): a lazy item that holds the focus is kept composed while the list scrolls away.
        scrollToEnd()
        assertTrue(exists("statusHint:c0"), "a row that holds the focus stays composed when scrolled far away")
        onNodeWithTag("statusHintPopup:c0", useUnmergedTree = true).assertExists("its open popup stays with it")
        // Move the focus to a row at the end: the first row is no longer held, so it leaves the composition and comes back closed.
        consumerCell("c${ROWS_IN_FOCUS_TEST - 1}", "Name").requestFocus()
        waitForIdle()
        assertTrue(!exists("statusHint:c0"), "the unfocused row scrolled out of the composition")
        scrollToTop()
        onNodeWithTag("statusHint:c0", useUnmergedTree = true).assertIsNotFocused()
        onNodeWithTag("statusHintPopup:c0", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- 7 ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `the loading placeholder rows and the empty sentences still render`() {
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            clientsScreen(clientsLoading.copy(screen = dev.caerbannog.core.streams.StreamClientsScreen.Loading))
            waitForIdle()
            onAllNodes(hasText(StreamClientsTexts.LOADING), useUnmergedTree = true).assertCountEquals(4 * 5)
            assertEquals("Loading", onNodeWithTag("consumersTable", useUnmergedTree = true).stateDescription())
        }
        runDesktopComposeUiTest(WIDTH, HEIGHT) {
            clientsScreen(content(publishers = emptyList(), consumers = emptyList()))
            waitForIdle()
            onNodeWithTag("publishersEmpty", useUnmergedTree = true).assertTextEquals(StreamClientsTexts.NO_PUBLISHERS)
            onNodeWithTag("consumersEmpty", useUnmergedTree = true).assertTextEquals(StreamClientsTexts.NO_CONSUMERS)
        }
    }

    // --- 8 ------------------------------------------------------------------------------------------------------------------

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")

    private fun DesktopComposeUiTest.pressCopy() {
        val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
        onRoot().performKeyInput { keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier) }
        waitForIdle()
    }

    @Test
    fun `a cell copies as displayed, in a row at the top, after a drag and in a row that was scrolled in`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val clipboard = FakeClipboard()
        clientsScreen(content(consumers = rows(offset = "47950")), wrap = { c -> CompositionLocalProvider(LocalClipboard provides clipboard) { c() } })
        waitForIdle()
        consumerCell("c1", "Offset").performMouseInput {
            val middle = visibleSize.height / 2f
            moveTo(Offset(1f, middle)); press(); moveTo(Offset(visibleSize.width - 1f, middle)); release()
        }
        pressCopy()
        assertEquals("47950", clipboard.text, "drag in a visible row")

        scrollToEnd()
        clipboard.text = null
        consumerCell("c${ROWS - 1}", "Status").requestFocus()
        waitForIdle()
        pressCopy()
        assertEquals("5,099 behind", clipboard.text, "a row that was not composed first copies as displayed")
    }

    // --- 9 (R-FC1) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a publisher and a consumer with the same connection and id, and two just-attached rows of each, compose without a duplicate key`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val unknown = StreamClientsTexts.UNKNOWN
        clientsScreen(
            content(
                publishers = listOf(
                    publisher("p-shared", "c#1", key = "c#1"),
                    publisher("p-new-1", unknown, key = "#0", settling = true),
                    publisher("p-new-2", unknown, key = "#0~2", settling = true),
                ),
                consumers = listOf(
                    consumer("c#1", "k-shared", connection = "c#1"),
                    consumer("#0", "k-new-1", connection = unknown, status = ConsumerStatus.Updating, browseFromOffset = null),
                    consumer("#0~2", "k-new-2", connection = unknown, status = ConsumerStatus.Updating, browseFromOffset = null),
                ),
            ),
        )
        waitForIdle()
        listOf("p-shared", "p-new-1", "p-new-2").forEachIndexed { i, ref -> publisherCell(i, "Reference").assertTextEquals(ref) }
        consumerCell("c#1", "Name").assertTextEquals("k-shared")
        consumerCell("#0", "Name").assertTextEquals("k-new-1")
        consumerCell("#0~2", "Name").assertTextEquals("k-new-2")
    }

    // --- 10 (verify round) ---------------------------------------------------------------------------------------------------

    @Test
    fun `in a narrow window the header and the rows of a table scroll sideways together, and never past the last column`() = runDesktopComposeUiTest(700, HEIGHT) {
        clientsScreen(content(consumers = rows(40)))
        waitForIdle()
        val headerBefore = bounds("consumersHeader:Connection")
        assertEquals(headerBefore.left, bounds("consumerCell:c0:Connection").left, "header and row columns line up before scrolling")

        onNodeWithTag("consumersHeader:Connection", useUnmergedTree = true).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 60f, durationMillis = 200) }
        waitForIdle()
        val headerAfter = bounds("consumersHeader:Connection")
        assertTrue(headerAfter.left < headerBefore.left, "the header scrolled sideways: $headerBefore -> $headerAfter")
        assertEquals(headerAfter.left, bounds("consumerCell:c0:Connection").left, "the first row moved with the header")
        assertEquals(headerAfter.left, bounds("consumerCell:c5:Connection").left, "a lower visible row moved with the header")

        onNodeWithTag("consumersHeader:Connection", useUnmergedTree = true).performTouchInput { swipeLeft(startX = right - 1f, endX = 1f, durationMillis = 100); swipeLeft(startX = right - 1f, endX = 1f, durationMillis = 100); swipeLeft(startX = right - 1f, endX = 1f, durationMillis = 100) }
        waitForIdle()
        val view = list().fetchSemanticsNode().boundsInRoot
        val status = bounds("consumersHeader:Status")
        assertEquals(status.left, bounds("consumerCell:c0:Status").left, "the rows are at the same place as the header at the far end")
        assertTrue(status.right <= view.right + 1f && status.right >= view.right - 40f, "scrolled to the end, the last column ends at the viewport edge (not past it, not short of it): $status vs $view")
    }

    // --- 11 (verify round) ---------------------------------------------------------------------------------------------------

    @Test
    fun `Tab from the last composed row reaches rows below the fold`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        clientsScreen(content(consumers = rows(300)))
        waitForIdle()
        val composedBefore = names().toSet()
        val last = names().last()
        consumerCell(last, "Name").requestFocus()
        waitForIdle()
        val reached = mutableSetOf<String>()
        repeat(12) {
            onRoot().performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
            waitForIdle()
            onAllNodes(SemanticsMatcher("focused") { it.config.getOrNull(SemanticsProperties.Focused) == true }, useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { reached += tag(it) }
        }
        val newRows = reached.filter { it.startsWith("consumerCell:") }.map { it.split(":")[1] }.filter { it !in composedBefore }
        assertTrue(newRows.isNotEmpty(), "Tab never reached a row that was not composed at first (from $last); focused: $reached")
    }

    // --- F-C-03 A, B, C ---------------------------------------------------------------------------------------------------

    @Test
    fun `after a far sideways scroll in a narrow window and then widening it, the rows are not shifted off the viewport, header out of the composition`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        // The header's own horizontalScroll shrinks the shared ScrollState when the width grows, so the rows' clamp only matters while the
        // header is not composed: scroll sideways, scroll down until the header leaves the list, then widen the window.
        var width by androidx.compose.runtime.mutableStateOf(700.dp)
        clientsScreen(content(consumers = rows(300)), wrap = { c -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(width)) { c() } })
        waitForIdle()
        onNodeWithTag("consumersHeader:Connection", useUnmergedTree = true).performTouchInput {
            repeat(3) { swipeLeft(startX = right - 1f, endX = 1f, durationMillis = 100) }
        }
        waitForIdle()
        scrollToEnd()
        assertTrue(!exists("consumersHeader:Status"), "the header is out of the composition")
        width = 1400.dp // same composition: the ScrollState keeps its large value
        waitForIdle()

        val view = list().fetchSemanticsNode().boundsInRoot
        val last = "c299"
        val name = bounds("consumerCell:$last:Name")
        assertTrue(name.left >= view.left - 1f && name.right > name.left, "the first column of a row is shifted off the left of the viewport after widening: $name vs $view")
        assertTrue(bounds("consumerCell:$last:Status").right <= view.right + 1f, "the last column ends inside the viewport: ${bounds("consumerCell:$last:Status")} vs $view")
        scrollToTop()
        assertEquals(bounds("consumersHeader:Consumer").left, bounds("consumerCell:c0:Name").left, "header and row line up after coming back")
    }

    @Test
    fun `the hint popup of a dimmed stale row is drawn whole, as big as the popup of a fresh row`() = runDesktopComposeUiTest(WIDTH, HEIGHT) {
        val shown = clientsScreen(content(consumers = rows(40), stale = false))
        waitForIdle()
        val behind = visibleBehindRows()
        assertTrue(behind.size >= 3, "at least three fully visible Behind rows expected: $behind")
        val keys = behind.take(2)
        val fresh = keys.map { key ->
            onNodeWithTag("statusHint:$key", useUnmergedTree = true).requestFocus(); waitForIdle()
            bounds("statusHintPopup:$key").also { onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }; waitForIdle() }
        }

        shown.state = content(consumers = rows(40), stale = true)
        waitForIdle()
        val without = image()
        val problems = mutableListOf<String>()
        keys.forEachIndexed { n, key ->
            problems += popupProblems("stale", key, without)
            val rect = bounds("statusHintPopup:$key")
            if (kotlin.math.abs(rect.height - fresh[n].height) > 1f || kotlin.math.abs(rect.width - fresh[n].width) > 1f) {
                problems += "stale ($key): popup is ${rect.width} x ${rect.height} but ${fresh[n].width} x ${fresh[n].height} on a fresh row (cut by the row's layer?)"
            }
            onRoot().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }; waitForIdle()
        }
        assertTrue(problems.isEmpty(), "The popup of a stale row is not usable:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `a sideways swipe on a data row moves the header and all rows together`() = runDesktopComposeUiTest(700, HEIGHT) {
        clientsScreen(content(consumers = rows(40)))
        waitForIdle()
        val before = bounds("consumersHeader:Connection")
        onNodeWithTag("consumerCell:c2:Connection", useUnmergedTree = true).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 60f, durationMillis = 200) }
        waitForIdle()
        val after = bounds("consumersHeader:Connection")
        assertTrue(after.left < before.left, "a swipe on a data row scrolled the table: $before -> $after")
        assertEquals(after.left, bounds("consumerCell:c0:Connection").left, "another row moved with it")
        assertEquals(after.left, bounds("consumerCell:c5:Connection").left, "a lower row moved with it")
    }
}
