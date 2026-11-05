package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M2-06a step 2: the texts of the tab as literals (the UI and the scenarios assert these exact words), and the status type of the lag ruling. */
class StreamClientsTextsTest {
    @Test
    fun `the texts are the words of the brief`() {
        assertEquals("Not connected", StreamClientsTexts.NOT_CONNECTED)
        assertEquals("unknown", StreamClientsTexts.UNKNOWN)
        assertEquals("updating", StreamClientsTexts.UPDATING)
        assertEquals("unnamed", StreamClientsTexts.UNNAMED)
        assertEquals("Caught up", StreamClientsTexts.CAUGHT_UP)
        assertEquals("Not active", StreamClientsTexts.NOT_ACTIVE)
        assertEquals("Showing only your own connections. The monitoring tag shows all.", StreamClientsTexts.OWN_CONNECTIONS_NOTICE)
        assertEquals("Publishers and consumers could not be loaded.", StreamClientsTexts.LOAD_FAILED)
        assertEquals("This stream no longer exists.", StreamClientsTexts.STREAM_GONE)
        assertEquals(
            "Stored offsets count as messages, so a consumer that stores its offset can read a few behind when it has read everything.",
            StreamClientsTexts.STORED_OFFSETS_HINT,
        )
    }

    @Test
    fun `Behind reads the lag with grouped digits and carries the stored offsets hint, Caught up carries none`() {
        assertEquals("3 behind", ConsumerStatus.Behind(3).text)
        assertEquals("349 behind", ConsumerStatus.Behind(349).text)
        assertEquals("1,234,567 behind", ConsumerStatus.Behind(1_234_567).text)
        assertEquals(StreamClientsTexts.STORED_OFFSETS_HINT, ConsumerStatus.Behind(3).hint)
        assertNull(ConsumerStatus.CaughtUp.hint)
        assertNull(ConsumerStatus.NotActive.hint)
        assertNull(ConsumerStatus.Updating.hint)
        assertEquals("Caught up", ConsumerStatus.CaughtUp.text)
        assertEquals("updating", ConsumerStatus.Updating.text)
        assertEquals("Not active", ConsumerStatus.NotActive.text)
    }
}
