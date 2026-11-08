package dev.caerbannog.ui.streams

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.CreateStreamGate
import dev.caerbannog.core.streams.PartitionBalanceInfo
import dev.caerbannog.core.streams.PartitionHealth
import dev.caerbannog.core.streams.StreamKindFilter
import dev.caerbannog.core.streams.StreamListTexts
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-07 step 2 (tests first): super streams on the stream list screen (`docs/ux/Streams.dc.html`: kind chips, the expanded super stream with
 * indented partition rows, the note row). The screen draws the strings of the state and decides none. Window 1280 x 800 dp.
 *
 * Tag contract (the developer follows it, or asks me to change test and KDoc together), in addition to the M2-05c tags:
 *  - kind chips `kindChip:All`, `kindChip:Streams`, `kindChip:SuperStreams`, texts "All", "Streams", "Super streams", in the chip row under the
 *    app bar (Rows state only, like the search); the chip of `state.kindFilter` is selected (semantics selected), the others are not;
 *    a click calls `events.setKindFilter(kind)`.
 *  - `expandToggle:<name>` exists on super stream rows only, role Button, content description "Expand <name>" while collapsed and
 *    "Collapse <name>" while expanded; a click, Enter or Space on it calls `events.toggleExpanded(name)`. It is focusable.
 *  - `partitionNote:<name>` exists under an EXPANDED super stream only and shows [StreamListTexts.PARTITION_ORDER_NOTE].
 *  - the Type cell shows `StreamRow.typeText` ("Super stream · 3 partitions"); the cells of a super stream row are the row's strings.
 *  - the name cell `cell:<name>:Name` of a super stream is plain text (no role Button, a click or Enter opens nothing); the name cell of a
 *    partition is the M2-06 link: a click calls `onOpenStream(partition name)`. A partition name is indented to the right of a top-level name.
 *  - `groupingNotice` shows `state.groupingNotice` when it is not null (a notice above the table, not the lost-connection banner).
 */
@OptIn(ExperimentalTestApi::class)
class StreamsSuperStreamUiTest {

    private class FakeClipboard : Clipboard {
        var text: String? = null
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as String?
        }
        override val nativeClipboard: NativeClipboard get() = error("the test never reads the system clipboard")
    }

    private val mac = System.getProperty("os.name").lowercase().contains("mac")
    private val collapsed = rowsState(listOf(row("audit-log"), superRow()), totalStreams = 2)
    private val expanded = rowsState(expandedRows, totalStreams = 2)

    private fun DesktopComposeUiTest.node(tag: String): SemanticsNodeInteraction = onNodeWithTag(tag, useUnmergedTree = true)

    private fun DesktopComposeUiTest.left(tag: String): Float = node(tag).fetchSemanticsNode().boundsInRoot.left

    private fun DesktopComposeUiTest.key(tag: String, key: Key) {
        node(tag).requestFocus()
        node(tag).performKeyInput { keyDown(key); keyUp(key) }
        waitForIdle()
    }

    // --- kind chips ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the three kind chips are drawn with their texts and the selected one follows the state`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(rowsState(kindFilter = StreamKindFilter.SuperStreams))

        node("kindChip:All").assertTextEquals("All").assertIsNotSelected()
        node("kindChip:Streams").assertTextEquals("Streams").assertIsNotSelected()
        node("kindChip:SuperStreams").assertTextEquals("Super streams").assertIsSelected()

        shown.state = rowsState(kindFilter = StreamKindFilter.All)
        waitForIdle()
        node("kindChip:All").assertIsSelected()
        node("kindChip:SuperStreams").assertIsNotSelected()
    }

    @Test
    fun `a click on a chip sends that kind to the view model`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(rowsState(), events)

        node("kindChip:SuperStreams").performClick()
        node("kindChip:Streams").performClick()
        node("kindChip:All").performClick()

        assertEquals(listOf("setKindFilter:SuperStreams", "setKindFilter:Streams", "setKindFilter:All"), events.sent())
    }

    @Test
    fun `no chips without a table, in the not connected state`() = runDesktopComposeUiTest(1280, 800) {
        screen(notConnected)

        node("kindChip:All").assertDoesNotExistCompat()
    }

    // --- collapsed and expanded ---------------------------------------------------------------------------------------------

    @Test
    fun `a collapsed super stream is one row with its type, partition count and sums, and no partition rows or note`() = runDesktopComposeUiTest(1280, 800) {
        screen(collapsed)

        node("cell:orders:Type").assertTextEquals("Super stream · 3 partitions")
        node("cell:orders:Messages").assertTextEquals("96,100")
        node("cell:orders:FirstOffset").assertTextEquals("–")
        node("cell:orders:LastOffset").assertTextEquals("–")
        node("cell:orders:Publishers").assertTextEquals("4")
        node("cell:orders:Consumers").assertTextEquals("6")
        node("cell:orders:Retention").assertTextEquals("7D")
        node("cell:orders:Size").assertTextEquals("\u2014")
        node("cell:orders-0:Name").assertDoesNotExistCompat()
        node("partitionNote:orders").assertDoesNotExistCompat()
    }

    @Test
    fun `the Type text of a super stream is not cut at 1280 dp`() = runDesktopComposeUiTest(1280, 800) {
        screen(collapsed)

        val results = mutableListOf<TextLayoutResult>()
        node("cell:orders:Type").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertFalse(results.single().hasVisualOverflow, "the Type cell shows an ellipsis instead of the full text")
    }

    @Test
    fun `the Size cell of a super stream and of a partition is the em dash of every row (N1, blank until M3)`() = runDesktopComposeUiTest(1280, 800) {
        screen(expanded)

        for (name in listOf("orders", "orders-0", "orders-1", "orders-2")) node("cell:$name:Size").assertTextEquals("\u2014")
    }

    @Test
    fun `an expanded super stream shows the note and its three partitions with their own cells, indented`() = runDesktopComposeUiTest(1280, 800) {
        screen(expanded)

        node("partitionNote:orders").assertTextEquals("Messages are ordered only inside a partition. Choose a partition to browse it.")
        node("cell:orders-1:Type").assertTextEquals("partition")
        node("cell:orders-1:Messages").assertTextEquals("32,900")
        node("cell:orders-1:FirstOffset").assertTextEquals("0")
        node("cell:orders-1:LastOffset").assertTextEquals("32899")
        node("cell:orders-1:Publishers").assertTextEquals("1")
        node("cell:orders-1:Consumers").assertTextEquals("2")
        assertTrue(left("cell:orders-0:Name") >= left("cell:audit-log:Name") + 16f, "a partition name is indented: ${left("cell:orders-0:Name")} vs ${left("cell:audit-log:Name")}")
        assertEquals(left("cell:orders-0:Name"), left("cell:orders-2:Name"), "all partitions share one indent")
    }

    @Test
    fun `the note sits between the super stream row and its first partition`() = runDesktopComposeUiTest(1280, 800) {
        screen(expanded)

        fun top(tag: String) = node(tag).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("cell:orders:Name") < top("partitionNote:orders"), "note under the super stream row")
        assertTrue(top("partitionNote:orders") < top("cell:orders-0:Name"), "note above the partitions")
        assertTrue(top("cell:orders-0:Name") < top("cell:orders-1:Name") && top("cell:orders-1:Name") < top("cell:orders-2:Name"), "partitions in the given order")
    }

    // --- the expand control -------------------------------------------------------------------------------------------------

    @Test
    fun `the expand control has the accessible name Expand orders when collapsed and Collapse orders when expanded`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(collapsed)

        fun description() = node("expandToggle:orders").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Expand orders"), description())
        shown.state = expanded
        waitForIdle()
        assertEquals(listOf("Collapse orders"), description())
        assertEquals(Role.Button, node("expandToggle:orders").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun `only a super stream row has an expand control`() = runDesktopComposeUiTest(1280, 800) {
        screen(expanded)

        node("expandToggle:audit-log").assertDoesNotExistCompat()
        node("expandToggle:orders-0").assertDoesNotExistCompat()
        node("expandToggle:orders").assertExistsCompat()
    }

    @Test
    fun `a click on the expand control toggles that super stream`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(collapsed, events)

        node("expandToggle:orders").performClick()

        assertEquals(listOf("toggleExpanded:orders"), events.sent())
    }

    @Test
    fun `Enter on the focused expand control toggles it`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(collapsed, events)

        key("expandToggle:orders", Key.Enter)

        assertEquals(listOf("toggleExpanded:orders"), events.sent())
    }

    @Test
    fun `Space on the focused expand control toggles it`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(collapsed, events)

        key("expandToggle:orders", Key.Spacebar)

        assertEquals(listOf("toggleExpanded:orders"), events.sent())
    }

    @Test
    fun `another key does not toggle`() = runDesktopComposeUiTest(1280, 800) {
        val events = RecordedEvents()
        screen(collapsed, events)

        key("expandToggle:orders", Key.A)

        assertEquals(emptyList(), events.sent())
    }

    // --- names: plain text for the super stream, link for a partition ------------------------------------------------------

    @Test
    fun `the super stream name is plain text, not a link, a click or Enter opens nothing`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        setContent { StreamsScreen(collapsed, 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents, onOpenStream = { opened += it }) }

        assertNull(node("cell:orders:Name").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role), "no button role: there is no stream of that name to open")
        node("cell:orders:Name").performClick()
        node("cell:orders:Name").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForIdle()
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a partition name is a link that opens that partition`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        setContent { StreamsScreen(expanded, 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents, onOpenStream = { opened += it }) }

        assertEquals(Role.Button, node("cell:orders-1:Name").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        node("cell:orders-1:Name").performClick()
        node("cell:orders-2:Name").requestFocus()
        node("cell:orders-2:Name").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("orders-1", "orders-2"), opened)
    }

    @Test
    fun `a plain stream is still a link and nothing in a super stream row offers browse or open`() = runDesktopComposeUiTest(1280, 800) {
        screen(expanded)

        assertEquals(Role.Button, node("cell:audit-log:Name").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        node("browse:orders").assertDoesNotExistCompat()
        node("openStream:orders").assertDoesNotExistCompat()
    }

    // Architect ruling 3: a plain stream and a super stream both named "orders"; the name cells keep the tag `cell:orders:Name` (the plain one first).
    @Test
    fun `with the same name the plain stream keeps the link and the super stream stays plain text`() = runDesktopComposeUiTest(1280, 800) {
        val opened = mutableListOf<String>()
        val rows = listOf(row("orders"), superRow("orders"))
        setContent { StreamsScreen(rowsState(rows), 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents, onOpenStream = { opened += it }) }

        val names = onAllNodesWithTag("cell:orders:Name", useUnmergedTree = true)
        names.assertCountEquals(2)
        assertEquals(Role.Button, names[0].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role), "the plain stream is the link")
        assertNull(names[1].fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role), "the super stream is plain text")
        names[0].performClick()
        names[1].performClick()
        waitForIdle()
        assertEquals(listOf("orders"), opened, "only the plain stream opens")
    }

    // --- copy (UI1) ---------------------------------------------------------------------------------------------------------

    private fun DesktopComposeUiTest.copyOf(tag: String): String? {
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                StreamsScreen(expanded, 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents)
            }
        }
        node(tag).requestFocus()
        onRoot().performKeyInput {
            val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
            keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier)
        }
        waitForIdle()
        return clipboard.text
    }

    @Test
    fun `copying the super stream sum yields the displayed text`() = runDesktopComposeUiTest(1280, 800) {
        assertEquals("96,100", copyOf("cell:orders:Messages"))
    }

    @Test
    fun `copying the super stream type yields the displayed text, partition count included`() = runDesktopComposeUiTest(1280, 800) {
        assertEquals("Super stream · 3 partitions", copyOf("cell:orders:Type"))
    }

    @Test
    fun `copying the dash of a super stream offset yields the dash`() = runDesktopComposeUiTest(1280, 800) {
        assertEquals("–", copyOf("cell:orders:FirstOffset"))
    }

    @Test
    fun `copying a partition name yields the name only, not the indent`() = runDesktopComposeUiTest(1280, 800) {
        assertEquals("orders-1", copyOf("cell:orders-1:Name"))
    }

    // --- grouping notice and an n/a total ----------------------------------------------------------------------------------

    @Test
    fun `the grouping notice is shown when the state has one, and absent when it has none`() = runDesktopComposeUiTest(1280, 800) {
        val shown = screen(rowsState(listOf(row("orders-0"), row("orders-1")), groupingNotice = StreamListTexts.GROUPING_UNAVAILABLE))

        node("groupingNotice").assertTextEquals("Super stream grouping is unavailable. Partitions are listed as separate streams.")
        node("streamsBanner").assertDoesNotExistCompat()
        shown.state = rowsState(listOf(superRow()), groupingNotice = null)
        waitForIdle()
        node("groupingNotice").assertDoesNotExistCompat()
    }

    @Test
    fun `a super stream with an unknown partition count shows n_a and mixed retention as given`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(listOf(row("orders", type = "Super stream", messages = "n/a", first = "–", last = "–", retention = "mixed", kind = dev.caerbannog.core.streams.StreamRowKind.SuperStream, partitionCount = 3))))

        node("cell:orders:Messages").assertTextEquals("n/a")
        node("cell:orders:Retention").assertTextEquals("mixed")
    }

    @Test
    fun `the range text counts entries as the state says, not the rows drawn`() = runDesktopComposeUiTest(1280, 800) {
        screen(rowsState(expandedRows, totalStreams = 2, rangeText = "1 to 2 of 2"))

        node("rangeText").assertTextEquals("1 to 2 of 2")
        assertFalse(node("rangeText").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().toString().contains("5"))
    }

    @Test
    fun `expanded super stream partitions render partition balance bars and orphaned consumer warnings without affecting cell copy`() = runDesktopComposeUiTest(1280, 800) {
        val clipboard = FakeClipboard()
        val balances = mapOf(
            "orders-0" to PartitionBalanceInfo(
                partitionName = "orders-0",
                superStreamName = "orders",
                partitionIndex = 0,
                partitionCount = 3,
                messages = 80_000L,
                totalSuperStreamMessages = 100_000L,
                shareFraction = 0.80f,
                sharePercent = 80,
                shareLabel = "Hot \u00b7 80%",
                consumers = 2,
                isSkewedHot = true,
                isOrphaned = false,
                health = PartitionHealth.SkewedHot,
            ),
            "orders-1" to PartitionBalanceInfo(
                partitionName = "orders-1",
                superStreamName = "orders",
                partitionIndex = 1,
                partitionCount = 3,
                messages = 10_000L,
                totalSuperStreamMessages = 100_000L,
                shareFraction = 0.10f,
                sharePercent = 10,
                shareLabel = "10%",
                consumers = 2,
                isSkewedHot = false,
                isOrphaned = false,
                health = PartitionHealth.Balanced,
            ),
            "orders-2" to PartitionBalanceInfo(
                partitionName = "orders-2",
                superStreamName = "orders",
                partitionIndex = 2,
                partitionCount = 3,
                messages = 10_000L,
                totalSuperStreamMessages = 100_000L,
                shareFraction = 0.10f,
                sharePercent = 10,
                shareLabel = "10%",
                consumers = 0,
                isSkewedHot = false,
                isOrphaned = true,
                health = PartitionHealth.OrphanedNoConsumers,
            ),
        )
        val stateWithBalances = expanded.copy(partitionBalances = balances)
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                StreamsScreen(stateWithBalances, 0, CreateStreamGate.Enabled, RecordedEvents(), connected, noControlEvents)
            }
        }

        // Balance bars exist on partition rows, not on plain stream or super stream rows
        node("partitionBalance:audit-log").assertDoesNotExistCompat()
        node("partitionBalance:orders").assertDoesNotExistCompat()
        node("partitionBalance:orders-0").assertExistsCompat()
        node("partitionBalance:orders-0:bar").assertExistsCompat()
        node("partitionBalance:orders-0:label").assertTextEquals("Hot \u00b7 80%")
        node("partitionBalance:orders-1:label").assertTextEquals("10%")
        node("partitionBalance:orders-2:label").assertTextEquals("10%")

        // Orphaned consumer warning only on orders-2
        node("partitionOrphanedWarning:orders-0").assertDoesNotExistCompat()
        node("partitionOrphanedWarning:orders-1").assertDoesNotExistCompat()
        node("partitionOrphanedWarning:orders-2").assertExistsCompat()
        node("partitionOrphanedWarning:orders-2:label").assertTextEquals("Orphaned")

        // Copying the Messages cell of orders-0 still copies only the displayed count text
        node("cell:orders-0:Messages").requestFocus()
        onRoot().performKeyInput {
            val modifier = if (mac) Key.MetaLeft else Key.CtrlLeft
            keyDown(modifier); keyDown(Key.C); keyUp(Key.C); keyUp(modifier)
        }
        waitForIdle()
        assertEquals("31,200", clipboard.text)
    }
}

// assertExists / assertDoesNotExist under the unmerged tree, kept local so the contract above reads in one place.
@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.assertExistsCompat(): SemanticsNodeInteraction = assertExists()

@OptIn(ExperimentalTestApi::class)
private fun SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
