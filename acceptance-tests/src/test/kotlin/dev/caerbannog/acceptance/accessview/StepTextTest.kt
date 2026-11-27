package dev.caerbannog.acceptance.accessview

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M10-04 part B: the one helper that unescapes doubled backslashes of step text. Fast, no Cucumber. */
class StepTextTest {
    @Test
    fun `a doubled backslash in step text becomes one`() {
        assertEquals("^ex\\..*", StepText.pattern("^ex\\\\..*"))
        assertEquals("^\\d+$", StepText.pattern("^\\\\d+$"))
    }

    @Test
    fun `a single backslash stays, so a value a table cell already unescaped is unchanged`() {
        assertEquals("^\\d+$", StepText.pattern("^\\d+$"))
        assertEquals("(e)x.*\\1", StepText.pattern("(e)x.*\\1"))
    }

    @Test
    fun `text without a backslash is unchanged and the empty text stays empty`() {
        assertEquals("^ex$", StepText.pattern("^ex$"))
        assertEquals("", StepText.pattern(""))
    }

    @Test
    fun `pairs are taken from the left, once each`() {
        assertEquals("\\\\", StepText.pattern("\\\\\\\\"), "four backslashes are two")
        assertEquals("\\\\\\", StepText.pattern("\\\\\\\\\\\\"), "six are three")
        assertEquals("\\\\", StepText.pattern("\\\\\\"), "three are one pair and one single: two in all")
    }
}
