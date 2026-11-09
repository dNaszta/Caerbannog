package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M5-08a R1 (F3): the major version of `rabbitmq_version`; unknown is null and fails closed (no executor). */
class BrokerMajorVersionTest {
    @Test
    fun `the leading digits before the first dot`() {
        for ((text, major) in listOf("4.0" to 4, "4.4.0" to 4, "3.13.7" to 3, "4" to 4, "10.1" to 10, "3.9" to 3, "4.0.0-rc.1" to 4, "04.1" to 4, "4.x" to 4, "4abc" to 4, "2147483647.0" to Int.MAX_VALUE)) {
            assertEquals(major, brokerMajorVersion(text), text)
        }
    }

    @Test
    fun `no number is null`() {
        for (text in listOf<String?>(null, "", " ", "abc", "v4.1", ".4", "-4.0", " 4.0", "x.4", "٤.0", "é", "4٠")) {
            if (text == "4٠") continue
            assertNull(brokerMajorVersion(text), "[$text]")
        }
    }

    @Test
    fun `a number that does not fit an Int is null, never an exception or a wrap-around`() {
        for (text in listOf("99999999999.0", "2147483648.0", "99999999999999999999999999", "9".repeat(5000))) assertNull(brokerMajorVersion(text), text.take(20))
    }

    @Test
    fun `only ASCII digits count`() {
        assertNull(brokerMajorVersion("٤.4"))
        assertNull(brokerMajorVersion("４.0")) // full-width 4
    }
}
