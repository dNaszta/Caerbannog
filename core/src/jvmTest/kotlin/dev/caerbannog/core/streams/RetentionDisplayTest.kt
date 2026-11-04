package dev.caerbannog.core.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-04 step 2, decision D9 (product owner 2026-11-04, wording still to confirm): the retention age is shown in the
 * compact natural form, the largest whole unit among D, h, m, s. Pure function, so a table.
 * Contract (package `dev.caerbannog.core.streams`, commonMain): `fun Retention.displayMaxAge(): String`, "" when no age is set.
 * What is shown is what is copied (UI1), so this string is also the copied text.
 * Month and year are RabbitMQ's own units: they are shown only when the broker sent them, and a seconds value is NEVER
 * converted to them (no 30-day month, no 365-day year assumed).
 */
class RetentionDisplayTest {

    private fun shown(maxAge: String?) = Retention(maxAge = maxAge).displayMaxAge()

    @Test
    fun `a unit text stays as it is`() {
        assertEquals("7D", shown("7D"))
        assertEquals("90m", shown("90m"))
        assertEquals("1h", shown("1h"))
    }

    @Test
    fun `a seconds text becomes the largest whole unit`() {
        assertEquals("7D", shown("604800s"))
        assertEquals("1h", shown("3600s"))
        assertEquals("90m", shown("5400s"))
        assertEquals("1D", shown("86400s"))
        assertEquals("59s", shown("59s"))
        assertEquals("1m", shown("60s"))
    }

    @Test
    fun `no whole unit means the seconds stay, no decimals`() {
        assertEquals("86401s", shown("86401s"))
        assertEquals("3601s", shown("3601s"))
        assertEquals("61s", shown("61s"))
    }

    @Test
    fun `zero seconds is shown as 0s`() {
        assertEquals("0s", shown("0s"))
    }

    @Test
    fun `month and year are shown only when the broker sent them`() {
        assertEquals("1Y", shown("1Y"))
        assertEquals("2M", shown("2M"))
        // A seconds value is never turned into M or Y: 30 days and 365 days are D, not M or Y.
        assertEquals("30D", shown("2592000s"))
        assertEquals("365D", shown("31536000s"))
    }

    @Test
    fun `an unknown text is shown unchanged and never throws`() {
        for (text in listOf("abc", "7X", "", " ", "7 D", "D7", "7d", "1.5h", "-", "s", "ss")) {
            assertEquals(text, shown(text), "unknown text <$text>")
        }
    }

    @Test
    fun `a missing age is blank`() {
        assertEquals("", shown(null))
        assertEquals("", Retention().displayMaxAge())
    }

    @Test
    fun `a huge number is unchanged and does not overflow`() {
        assertEquals("99999999999999999999s", shown("99999999999999999999s"))
        assertEquals("99999999999999999999D", shown("99999999999999999999D"))
        // Long.MAX_VALUE seconds does not divide into whole days: unchanged, not a wrong number.
        assertEquals("9223372036854775807s", shown("9223372036854775807s"))
    }

    @Test
    fun `a negative text is unchanged`() {
        assertEquals("-3600s", shown("-3600s"))
        assertEquals("-7D", shown("-7D"))
    }

    @Test
    fun `only the age is looked at, the other retention fields do not change it`() {
        val retention = Retention(maxAge = "604800s", maxLengthBytes = 5_000_000_000, segmentSizeBytes = 500_000_000)
        assertEquals("7D", retention.displayMaxAge())
    }
}
