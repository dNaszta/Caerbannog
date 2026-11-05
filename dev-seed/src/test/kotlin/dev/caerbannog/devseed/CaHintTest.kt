package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M2-12b (docs/tasks/M2-12b-dev-seed-hardening.md): a wrong CA surfaces as `StreamException: null`.
 * Contract: caHint(failureMessage: String?): String? returns a hint for a null or empty message, null for any
 * other message (a real message already says what went wrong). The hint names `--ca` and the broker certificate.
 * The function takes only the message, so a password cannot reach it; the tests still pin that.
 */
class CaHintTest {
    @Test
    fun `a null message gives a hint about the CA file`() {
        val hint = caHint(null)
        assertNotNull(hint, "null message (StreamException: null) must give a hint")
        assertTrue("--ca" in hint!!, "hint must name the --ca flag, was: $hint")
        assertTrue("certificate" in hint, "hint must mention the broker certificate, was: $hint")
    }

    @Test
    fun `an empty message gives the same hint as null`() {
        assertNotNull(caHint(""), "empty message must give a hint")
        assertTrue(caHint("") == caHint(null), "empty and null must give the same hint")
    }

    @Test
    fun `a real failure message gets no hint`() {
        assertNull(caHint("Connection refused"))
        assertNull(caHint("stream does not exist"))
    }

    @Test
    fun `the hint carries no password and no flag value`() {
        val hint = caHint(null)
        assertNotNull(hint)
        assertFalse("--password" in hint!!, "hint must not mention the password flag, was: $hint")
        assertFalse("dev-pw" in hint, "hint must not contain a password, was: $hint")
    }
}
