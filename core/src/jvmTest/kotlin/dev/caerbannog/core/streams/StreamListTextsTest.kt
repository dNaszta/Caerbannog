package dev.caerbannog.core.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-05a step 2 (brief Scope item 4, review of M2-04 test finding 1): the texts are asserted as LITERALS, so a rewording is a
 * visible change in a diff. The old test only checked that [StreamListTexts.LOAD_FAILED] is not blank.
 */
class StreamListTextsTest {
    private val loadFailed = "The stream list could not be loaded. Check that the virtual host exists and that this user may use it."

    @Test
    fun `the failed load text tells the user what to check`() {
        assertEquals(loadFailed, StreamListTexts.LOAD_FAILED)
    }

    @Test
    fun `a failed first load shows that text, with Retry`() = runTest {
        val s = StreamListHarness(this)
        s.catalog.failListPage(1, CatalogResult.Unexpected)
        s.open()

        assertEquals(StreamListScreen.Failed(loadFailed), s.state.screen)
        assertEquals(loadFailed, s.state.message)
        assertEquals("Retry", s.state.action)
    }
}
