package dev.caerbannog.core.access

import kotlin.test.Test
import kotlin.test.assertEquals

/** M10-03b item 3: the words of the Permissions tab live in core and are pinned, so a UI test and a review refer to one place. The words are the drawn ones (owner 2026-11-25, as drawn). */
class PermissionsTextsTest {
    @Test
    fun `the column and heading words are the drawn ones`() {
        assertEquals("User", PermissionsTexts.USER_LABEL)
        assertEquals("Virtual host", PermissionsTexts.VHOST_COLUMN)
        assertEquals("Configure", PermissionsTexts.CONFIGURE_COLUMN)
        assertEquals("Write", PermissionsTexts.WRITE_COLUMN)
        assertEquals("Read", PermissionsTexts.READ_COLUMN)
        assertEquals("Exchange", PermissionsTexts.EXCHANGE_COLUMN)
        assertEquals("Topic permissions", PermissionsTexts.TOPIC_HEADING)
    }

    @Test
    fun `the filter hints are the drawn ones`() {
        assertEquals("Filter by user", PermissionsTexts.USER_FILTER_HINT)
        assertEquals("Filter by virtual host", PermissionsTexts.VHOST_FILTER_HINT)
    }

    @Test
    fun `the note is the drawn one without the dropped tooltip parenthesis`() {
        assertEquals(
            "A pattern is a regular expression the broker applies to the name. An empty pattern means no permission and is shown as such. Cells are copyable as displayed.",
            PermissionsTexts.PATTERN_NOTE,
        )
    }

    @Test
    fun `the no-user message is the owner's wording`() {
        assertEquals("The broker lists no user to choose.", PermissionsTexts.NO_USER)
    }
}
