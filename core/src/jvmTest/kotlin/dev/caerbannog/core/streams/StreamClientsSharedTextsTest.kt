package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/** M2-06 follow-up F-D: the words the stream screen used to hold as literals now live in [StreamClientsTexts] (values as drawn in `Clients.dc.html`). */
class StreamClientsSharedTextsTest {
    @Test
    fun `app bar, section titles and no user name`() {
        assertEquals("Refresh", StreamClientsTexts.REFRESH)
        assertEquals("Publishers", StreamClientsTexts.PUBLISHERS_TITLE)
        assertEquals("Consumers", StreamClientsTexts.CONSUMERS_TITLE)
        assertEquals("no user", StreamClientsTexts.NO_USER_NAME)
    }

    @Test
    fun `column headers`() {
        assertEquals(
            listOf("Reference", "Connection (user)", "Published", "Confirmed", "Errors", "Consumer", "Offset", "Messages behind", "Status"),
            listOf(
                StreamClientsTexts.HEADER_REFERENCE, StreamClientsTexts.HEADER_CONNECTION, StreamClientsTexts.HEADER_PUBLISHED,
                StreamClientsTexts.HEADER_CONFIRMED, StreamClientsTexts.HEADER_ERRORS, StreamClientsTexts.HEADER_CONSUMER,
                StreamClientsTexts.HEADER_OFFSET, StreamClientsTexts.HEADER_MESSAGES_BEHIND, StreamClientsTexts.HEADER_STATUS,
            ),
        )
    }
}
