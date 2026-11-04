package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-04 step 2: what a row shows (display strings only: what is shown is what is copied, UI1), the column rules of
 * spec "Stream list" and decisions D8 (publishers), D9 (retention), N1 (no size). Contract: see [StreamListHarness].
 * Fixed choices these tests make (flagged in the report): messages are grouped with commas ("48,300", "1,204,000", always
 * the same, no locale); offsets are plain digits ("2083999"); an Empty first offset shows blank first and last offsets.
 */
class StreamListViewModelRowsTest {

    @Test
    fun `a row shows type, messages, first and last offset, publishers, consumers and retention`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("orders-events", messages = 48_300, first = 0, consumers = 3, publishers = 2, retention = Retention(maxAge = "604800s"))
        s.open()

        assertEquals(
            StreamRow(
                name = "orders-events", type = "stream", messages = "48,300", firstOffset = "0", lastOffset = "48299",
                publishers = "2", consumers = "3", retention = "7D",
            ),
            s.state.rows.single(),
        )
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `the last offset is first plus messages minus one`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("audit-log", messages = 1_204_000, first = 880_000)
        s.addStream("one", messages = 1, first = 0)
        s.open()

        assertEquals("2083999", s.state.rows.first { it.name == "audit-log" }.lastOffset)
        assertEquals("1,204,000", s.state.rows.first { it.name == "audit-log" }.messages)
        assertEquals("0", s.state.rows.first { it.name == "one" }.lastOffset)
    }

    @Test
    fun `no message count shows n_a, never 0, and no last offset`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("fresh", messages = null, first = 0)
        s.open()

        val row = s.state.rows.single()
        assertEquals("n/a", row.messages)
        assertEquals(StreamListTexts.NOT_AVAILABLE, row.messages)
        assertEquals("0", row.firstOffset)
        assertEquals(StreamListTexts.UNAVAILABLE, row.lastOffset, "last offset needs first AND messages")
    }

    @Test
    fun `a denied first offset shows unavailable for both offsets and keeps the other cells`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("orders-events", messages = 10, first = null, consumers = 3, publishers = 2)
        s.offsets.deny("/", "orders-events")
        s.addStream("audit-log", messages = 100, first = 0)
        s.open()

        val denied = s.state.rows.first { it.name == "orders-events" }
        assertEquals("unavailable", denied.firstOffset)
        assertEquals("unavailable", denied.lastOffset)
        assertEquals("10", denied.messages)
        assertEquals("2", denied.publishers)
        assertEquals("3", denied.consumers)
        val fine = s.state.rows.first { it.name == "audit-log" }
        assertEquals("0", fine.firstOffset)
        assertEquals("99", fine.lastOffset)
    }

    @Test
    fun `a silent stream port makes every offset unavailable and the list still works`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a", first = 0)
        s.addStream("b", first = 0)
        s.offsets.silent = true
        s.open()

        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertEquals(listOf("unavailable", "unavailable"), s.state.rows.map { it.firstOffset })
    }

    @Test
    fun `an empty stream has blank offsets`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("empty", messages = 0, first = null)
        s.offsets.setEmpty("/", "empty")
        s.open()

        val row = s.state.rows.single()
        assertEquals("", row.firstOffset)
        assertEquals("", row.lastOffset)
    }

    @Test
    fun `a stream deleted between the list and the offset call reads unavailable, not an error`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.addStream(stream("gone", messages = 5)) // no first offset known: the fake answers like a deleted stream
        s.open()

        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertEquals("unavailable", s.state.rows.single().firstOffset)
    }

    @Test
    fun `offsets are loading until the stream port answers`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("slow", messages = 5, first = 10)
        val gate = s.offsets.hold("slow")
        s.open()

        val loading = s.state.rows.single()
        assertEquals("slow", loading.name)
        assertEquals(StreamListTexts.LOADING, loading.firstOffset)
        assertEquals(StreamListTexts.LOADING, loading.lastOffset)
        assertEquals("5", loading.messages, "the counts come from the management API and do not wait for the offsets")
        assertTrue(StreamListTexts.LOADING.isNotBlank())

        gate.release()
        s.settle()
        assertEquals("10", s.state.rows.single().firstOffset)
        assertEquals("14", s.state.rows.single().lastOffset)
    }

    @Test
    fun `the row model has no size`() = runTest {
        val members = StreamRow::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(members.none { it.contains("size") || it.contains("bytes") }, "N1: no size in the row model, found $members")
    }

    @Test
    fun `retention shows the compact form and the shown text is the copied text`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a", retention = Retention(maxAge = "604800s"))
        s.addStream("b", retention = Retention(maxAge = "7D"))
        s.addStream("c", retention = Retention(maxAge = "weird"))
        s.addStream("d")
        s.open()

        assertEquals(listOf("7D", "7D", "weird", ""), s.state.rows.map { it.retention })
    }

    @Test
    fun `counts are labelled as approximate`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a")
        s.open()

        assertTrue(s.state.approximateNote.contains("approximate", ignoreCase = true), "note: <${s.state.approximateNote}>")
    }

    // --- D8: publishers ----------------------------------------------------------------------------------

    @Test
    fun `publishers are shown for the monitoring or administrator tag, and a stream with none has 0`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.visibility = Visibility.FULL
        s.addStream("a-busy", publishers = 2)
        s.addStream("b-quiet", publishers = 0)
        s.open()

        assertEquals(listOf("2", "0"), s.state.rows.map { it.publishers })
    }

    @Test
    fun `publishers are unavailable for the management tag only, and the broker is not asked`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.visibility = Visibility.OWN_CONNECTIONS_ONLY
        s.addStream("orders-events", publishers = 2)
        s.open()

        assertEquals("unavailable", s.state.rows.single().publishers, "never a partial number (D8)")
        assertEquals(0, s.count("publisherCounts"), "a call whose answer is not shown must not be made")
        assertIs<StreamListScreen.Rows>(s.state.screen)
    }

    @Test
    fun `publishers are unavailable without the stream management plugin, the list still works`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.pluginPresent = false
        s.addStream("orders-events", publishers = 2, consumers = 1)
        s.open()

        val row = s.state.rows.single()
        assertEquals("unavailable", row.publishers)
        assertEquals("1", row.consumers)
        assertIs<StreamListScreen.Rows>(s.state.screen)
        assertEquals(0, s.count("publisherCounts"), "ruling 8: with no plugin the call is not made at all")
    }

    @Test
    fun `exactly one publisherCounts call per load, not one per row`() = runTest {
        val s = StreamListHarness(this)
        s.streams(5)
        s.open()
        assertEquals(1, s.count("publisherCounts"))
        assertEquals(0, s.count("publishers"), "the per-stream call is for the detail screen")

        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.count("publisherCounts"))
    }

    // --- capabilities and permissions ---------------------------------------------------------------------

    @Test
    fun `a user without the management tag gets the message and no list call is made`() = runTest {
        val s = StreamListHarness(this)
        s.h.gateway.noManagementTag("uat-eu")
        s.addStream("orders-events")
        s.open()

        assertIs<StreamListScreen.CannotReadStatistics>(s.state.screen)
        assertEquals("This user cannot read stream statistics. Ask for the monitoring tag.", s.state.message)
        assertEquals(CatalogMessages.NOT_MANAGEMENT_USER, s.state.message)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(0, s.count("listStreams"))
        assertEquals(0, s.offsets.calls.size)
    }

    @Test
    fun `a refused list call (permission changed after connecting) is the same message, not a lost connection`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a")
        s.open()
        assertIs<StreamListScreen.Rows>(s.state.screen)

        s.catalog.visibility = Visibility.NONE
        s.vm.refreshNow()
        s.settle()

        assertIs<StreamListScreen.CannotReadStatistics>(s.state.screen)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(LinkState.Connected("uat-eu"), s.h.active.state.value, "a 401 on a data call is not a lost connection")
    }

    @Test
    fun `capabilities are read once per connection, not on every refresh`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a")
        s.open()
        s.vm.refreshNow()
        s.settle()
        s.pass(5)
        s.pass(5)

        assertEquals(1, s.count("capabilities"), "a tick costs the list, the publishers and the offsets, nothing more")
    }

    // --- empty and failed ---------------------------------------------------------------------------------

    @Test
    fun `a broker with no streams is Empty with the message and the Create stream action`() = runTest {
        val s = StreamListHarness(this)
        s.open()

        assertIs<StreamListScreen.Empty>(s.state.screen)
        assertTrue(s.state.message!!.contains("no streams", ignoreCase = true), "message: <${s.state.message}>")
        assertEquals("Create stream", s.state.action)
        assertEquals(emptyList(), s.state.rows)
    }

    @Test
    fun `a broker with only quorum queues is Empty too`() = runTest {
        val s = StreamListHarness(this)
        repeat(3) { s.catalog.addQueue("q-$it") }
        s.open()

        assertIs<StreamListScreen.Empty>(s.state.screen)
    }

    @Test
    fun `an unexpected answer on the first load is Failed with a plain text and Retry`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.failListPage(1, CatalogResult.Unexpected)
        s.open()

        assertEquals(StreamListScreen.Failed(StreamListTexts.LOAD_FAILED), s.state.screen)
        assertTrue(StreamListTexts.LOAD_FAILED.isNotBlank())
        assertEquals("Retry", s.state.action)
        assertEquals(emptyList(), s.state.rows)
        assertNull(s.vm.updatedSecondsAgo(), "nothing loaded yet")
    }
}
