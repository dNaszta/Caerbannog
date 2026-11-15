package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-06a step 2: what the tab shows per row (scenarios 04: publishers, lag outline, stored offsets, just attached, last offset).
 * Display strings in the state (UI1). Contract: [ClientsHarness]. Pinned where the brief is silent (listed in the Status of the task):
 *  - a null, empty OR blank reference or consumer name reads "unnamed" (lag ruling, S-B);
 *  - row keys are unique among the rows of one state and the same for the same row after a refresh (the format is free);
 *  - rows keep the order the broker sends.
 */
class StreamClientsViewModelRowsTest {

    @Test
    fun `publishers are listed in the broker's order with connection, reference and counts`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(
            FakeRow.publisher("checkout-1", 48100, "10.0.4.21:51234 - checkout-service"),
            FakeRow.publisher("backfill-job", 200, "10.0.7.8:40022 - backfill", id = 1),
        )
        s.open()

        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(
            listOf(
                PublisherRowState("10.0.4.21:51234 - checkout-service#0", "10.0.4.21:51234 - checkout-service", "svc", "checkout-1", "48,100", "48,100", "0", settling = false),
                PublisherRowState("10.0.7.8:40022 - backfill#1", "10.0.7.8:40022 - backfill", "svc", "backfill-job", "200", "200", "0", settling = false),
            ),
            s.state.publishers,
        )
        assertEquals(emptyList(), s.state.consumers)
    }

    @Test
    fun `rows keep the order the broker sends, there is no sort`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeRow.publisher("zeta", id = 0), FakeRow.publisher("alpha", id = 1), FakeRow.publisher("mid", id = 2))
        s.consumers(s.consumerAt("zz", 5, id = 0), s.consumerAt("aa", 5, id = 1))
        s.open()

        assertEquals(listOf("zeta", "alpha", "mid"), s.state.publishers.map { it.reference })
        assertEquals(listOf("zz", "aa"), s.state.consumers.map { it.name })
    }

    @Test
    fun `errors are the broker's errored count, counts are grouped`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeRow.publisher("p", published = 1_234_567, confirmed = 1_234_000, errored = 1_000))
        s.open()

        val row = s.state.publishers.single()
        assertEquals(Triple("1,234,567", "1,234,000", "1,000"), Triple(row.published, row.confirmed, row.errors))
    }

    // --- the lag rule (lag ruling 2026-11-05) ---------------------------------------------------------

    @Test
    fun `a consumer at the last offset is Caught up with 0 behind`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299))
        s.open()

        val row = s.state.consumers.single()
        assertEquals(ConsumerStatus.CaughtUp, row.status)
        assertEquals("Caught up", row.status.text)
        assertEquals("0", row.behind)
        assertEquals("48299", row.offset, "offsets are plain digits")
    }

    @Test
    fun `the lag rule at 0, 1, 2, 3 and a large value, status text neutral and the hint only above 0`() = runTest {
        val cases = listOf(0L to "Caught up", 1L to "1 behind", 2L to "2 behind", 3L to "3 behind", 349L to "349 behind", 1_234_567L to "1,234,567 behind")
        for ((lag, text) in cases) {
            val s = ClientsHarness(this)
            s.consumers(s.consumerAt("c", 100, lag = lag))
            s.open()
            val row = s.state.consumers.single()
            assertEquals(text, row.status.text, "status for offset_lag $lag")
            assertEquals(groupedLag(lag), row.behind, "messages behind is the broker's number for offset_lag $lag")
            if (lag == 0L) {
                assertEquals(ConsumerStatus.CaughtUp, row.status)
                assertNull(row.status.hint, "Caught up has no tooltip")
            } else {
                assertEquals(ConsumerStatus.Behind(lag), row.status, "lag $lag is Behind, never Caught up (no tolerance: lag 1 is NOT caught up)")
                assertEquals(StreamClientsTexts.STORED_OFFSETS_HINT, row.status.hint, "Behind carries the stored offsets hint")
            }
        }
    }

    private fun groupedLag(lag: Long): String = String.format(java.util.Locale.ROOT, "%,d", lag)

    @Test
    fun `scenario outline rows read as written, the lag is last offset minus offset`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(
            s.consumerAt("billing", 48299, id = 0), s.consumerAt("analytics", 47950, id = 1),
            s.consumerAt("audit", 48100, id = 2), s.consumerAt("reports", 48297, id = 3),
        )
        s.open()

        assertEquals(
            listOf(Triple("billing", "0", "Caught up"), Triple("analytics", "349", "349 behind"), Triple("audit", "199", "199 behind"), Triple("reports", "2", "2 behind")),
            s.state.consumers.map { Triple(it.name, it.behind, it.status.text) },
        )
    }

    @Test
    fun `a consumer that stored its offset and is 3 behind reads 3 behind and carries the hint`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299, lag = 3))
        s.open()

        val row = s.state.consumers.single()
        assertEquals("3 behind", row.status.text)
        assertEquals(StreamClientsTexts.STORED_OFFSETS_HINT, row.status.hint)
        assertEquals("3", row.behind, "the broker's number is shown as reported, nothing is subtracted")
    }

    @Test
    fun `a consumer that is not the active one of its group reads Not active with its lag unchanged`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("standby", 48000, active = false))
        s.open()

        val row = s.state.consumers.single()
        assertEquals(ConsumerStatus.NotActive, row.status)
        assertEquals("Not active", row.status.text)
        assertEquals("299", row.behind)
    }

    @Test
    fun `browseFromOffset is the consumer's offset`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("analytics", 47950))
        s.open()

        assertEquals(47950L, s.state.consumers.single().browseFromOffset)
    }

    // --- settling rows ------------------------------------------------------------------------------

    @Test
    fun `a consumer row with an empty connection name reads unknown and updating, never 0, and cannot be browsed`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("fresh", 0, lag = 0, connection = ""))
        s.open()

        val row = s.state.consumers.single()
        assertEquals("unknown", row.connection)
        assertEquals("updating", row.offset)
        assertEquals("updating", row.behind)
        assertEquals("updating", row.status.text)
        assertEquals(ConsumerStatus.Updating, row.status)
        assertNull(row.status.hint)
        assertNull(row.browseFromOffset)
    }

    @Test
    fun `a publisher row with an empty connection name reads unknown and every counter updating`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeRow.publisher("p", 0, connection = ""))
        s.open()

        val row = s.state.publishers.single()
        assertEquals(true, row.settling)
        assertEquals("unknown", row.connection)
        assertEquals(Triple("updating", "updating", "updating"), Triple(row.published, row.confirmed, row.errors))
    }

    @Test
    fun `a settled row next to a settling one is shown as it is`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299, id = 0), s.consumerAt("fresh", 0, connection = "", id = 1))
        s.open()

        assertEquals(listOf("Caught up", "updating"), s.state.consumers.map { it.status.text })
    }

    @Test
    fun `a row settles on the next refresh`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 0, connection = ""))
        s.open()
        assertEquals("updating", s.state.consumers.single().status.text)

        s.consumers(s.consumerAt("billing", 48299, connection = "10.0.5.14:38812 - billing-service"))
        s.pass(5)

        val row = s.state.consumers.single()
        assertEquals("10.0.5.14:38812 - billing-service", row.connection)
        assertEquals("0", row.behind)
        assertEquals(ConsumerStatus.CaughtUp, row.status)
        assertEquals(48299L, row.browseFromOffset)
    }

    // --- a row for which the broker sent no lag number (M2-17c): the same state as a client that has just attached ---

    @Test
    fun `a consumer row without a lag number reads updating in every cell, never 0 or Caught up, and cannot be browsed`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299, lag = null))
        s.open()

        val row = s.state.consumers.single()
        assertEquals("updating", row.status.text, "a lag the broker did not send must not read Caught up or 0")
        assertEquals(ConsumerStatus.Updating, row.status)
        assertEquals("updating", row.offset)
        assertEquals("updating", row.behind)
        assertNull(row.status.hint)
        assertNull(row.browseFromOffset, "a row in this state cannot be browsed")
        assertEquals("unknown", row.connection, "the state is all-or-nothing: the same as an empty connection name (ruling 3)")
    }

    @Test
    fun `a settled row on each side of one without a lag number keeps its own state`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(
            s.consumerAt("first", 48299, lag = 0, id = 0),
            s.consumerAt("middle", 48299, lag = null, id = 1),
            s.consumerAt("last", 47950, lag = 349, id = 2),
        )
        s.open()

        assertEquals(
            listOf(Triple("first", "0", "Caught up"), Triple("middle", "updating", "updating"), Triple("last", "349", "349 behind")),
            s.state.consumers.map { Triple(it.name, it.behind, it.status.text) },
        )
    }

    @Test
    fun `a row settles when its lag number arrives`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt("billing", 48299, lag = null))
        s.open()
        assertEquals("updating", s.state.consumers.single().status.text, "no lag number yet")

        s.consumers(s.consumerAt("billing", 48299, lag = 0))
        s.pass(5)

        val row = s.state.consumers.single()
        assertEquals(ConsumerStatus.CaughtUp, row.status)
        assertEquals("0", row.behind)
        assertEquals(48299L, row.browseFromOffset)
    }

    @Test
    fun `the lag trend of a consumer gets no point while its lag number is missing`() = runTest {
        val s = ClientsHarness(this)
        val key = "10.0.5.14:38812 -> 10.0.0.1:5552#0"
        s.consumers(s.consumerAt("worker", 100, lag = 5))
        s.open()
        repeat(3) {
            s.consumers(s.consumerAt("worker", 100, lag = null))
            s.pass(5)
        }
        assertTrue(s.state.consumerLagSparklines.isEmpty(), "one real point is no trend yet; a 0 recorded for a missing lag would already make one")

        s.consumers(s.consumerAt("worker", 100, lag = 5))
        s.pass(5)

        assertEquals(listOf(5L, 5L), s.state.consumerLagSparklines.getValue(key).samples, "never a 0 between the two real points")
    }

    // --- unnamed ------------------------------------------------------------------------------------

    @Test
    fun `a null, empty or blank reference reads unnamed`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeRow.publisher(null, id = 0), FakeRow.publisher("", id = 1), FakeRow.publisher("   ", id = 2), FakeRow.publisher("named", id = 3))
        s.open()

        assertEquals(listOf("unnamed", "unnamed", "unnamed", "named"), s.state.publishers.map { it.reference })
    }

    @Test
    fun `a null, empty or blank consumer name reads unnamed`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt(null, 5, id = 0), s.consumerAt("", 5, id = 1), s.consumerAt("  ", 5, id = 2), s.consumerAt("named", 5, id = 3))
        s.open()

        assertEquals(listOf("unnamed", "unnamed", "unnamed", "named"), s.state.consumers.map { it.name })
    }

    @Test
    fun `consumer keys are unique in a state and stable across a refresh`() = runTest {
        val s = ClientsHarness(this)
        s.consumers(s.consumerAt(null, 5, id = 0), s.consumerAt(null, 5, id = 1), s.consumerAt("same", 5, id = 2, connection = "c1"), s.consumerAt("same", 5, id = 3, connection = "c2"))
        s.open()
        val keys = s.state.consumers.map { it.key }
        assertEquals(4, keys.toSet().size, "keys must be unique: $keys")

        s.pass(5)
        assertEquals(keys, s.state.consumers.map { it.key }, "the same row keeps its key")
    }

    // --- last offset (one rule with the stream list) ---------------------------------------------------

    @Test
    fun `the last offset is first offset plus messages minus one`() = runTest {
        val s = ClientsHarness(this)
        s.open()
        assertEquals("48299", s.state.lastOffset)
        assertEquals(ClientsHarness.STREAM, s.state.stream)
    }

    @Test
    fun `the last offset follows the stream list rule for every combination`() = runTest {
        // (first offset, messages) -> text; the same table as the list rows: StreamRowFormatTest
        val cases = listOf(
            FirstOffset.Value(100) to (10L to "109"),
            FirstOffset.Value(0) to (1L to "0"),
            FirstOffset.Value(0) to (0L to ""),
            FirstOffset.Empty to (0L to ""),
            FirstOffset.Unavailable to (5L to "unavailable"),
            FirstOffset.Value(0) to (null to "unavailable"),
        )
        for ((first, pair) in cases) {
            val (messages, expected) = pair
            val s = ClientsHarness(this)
            s.catalog.addStream(stream(ClientsHarness.STREAM, messages = messages))
            when (first) {
                is FirstOffset.Value -> s.offsets.setFirst("/", ClientsHarness.STREAM, first.offset)
                FirstOffset.Empty -> s.offsets.setEmpty("/", ClientsHarness.STREAM)
                FirstOffset.Unavailable -> s.offsets.deny("/", ClientsHarness.STREAM)
            }
            s.open()
            assertEquals(expected, s.state.lastOffset, "first=$first messages=$messages")
            assertIs<StreamClientsScreen.Content>(s.state.screen, "an unavailable offset does not break the tab")
        }
    }

    @Test
    fun `a stream nobody uses shows both sections empty`() = runTest {
        val s = ClientsHarness(this)
        s.open()

        assertIs<StreamClientsScreen.Content>(s.state.screen)
        assertEquals(emptyList(), s.state.publishers)
        assertEquals(emptyList(), s.state.consumers)
        assertNull(s.state.ownConnectionsNotice, "a full-visibility user sees no notice")
        assertNull(s.state.message)
        assertTrue(s.state.stream.isNotEmpty())
    }

    /** Short alias so the rows read like the scenario tables. */
    private object FakeRow {
        fun publisher(ref: String?, published: Long = 10, connection: String = "10.0.4.21:51234 -> 10.0.0.1:5552", id: Int = 0, confirmed: Long = published, errored: Long = 0) =
            dev.caerbannog.core.connections.fakes.FakeStreamCatalog.publisher(ref, published, confirmed, errored, connection, publisherId = id)
    }
}
