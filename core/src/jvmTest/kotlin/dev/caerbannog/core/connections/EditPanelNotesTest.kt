package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M2-14a step 2: which notes the edit panel shows for each position of the TLS switch. Red until `editPanelNotes` is built. */
class EditPanelNotesTest {

    @Test
    fun `TLS on shows the host name note and no warning`() {
        val notes = editPanelNotes(tls = true)
        assertEquals("Host name is not verified.", notes.hostNameNotVerified)
        assertNull(notes.notEncrypted)
    }

    @Test
    fun `TLS off shows Not encrypted and no host name note`() {
        val notes = editPanelNotes(tls = false)
        assertNull(notes.hostNameNotVerified)
        assertEquals("Not encrypted", notes.notEncrypted)
    }
}
