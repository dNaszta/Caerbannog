package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.fixtures.AccessMatchCases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M10-04: ONE source for the S-I rows. The spike M10-00 recorded them in `fixtures/m10-00/s-i-pattern-rows.tsv`; `core-testing` holds a byte copy
 * (`fixtures/m10-04/s-i-pattern-rows.tsv`) so that `core` tests can read it without a dependency on `data-rabbit`. This fast test (no container) fails when the two differ.
 */
class AccessMatchCasesSourceTest {
    @Test
    fun `the copy of the S-I rows in core-testing equals the rows the spike recorded`() {
        val recorded = AccessMatchCasesSourceTest::class.java.classLoader.getResourceAsStream("fixtures/m10-00/s-i-pattern-rows.tsv")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(recorded, AccessMatchCases.readSpikeText(), "core-testing/src/main/resources/fixtures/m10-04/s-i-pattern-rows.tsv must be a byte copy of data-rabbit/src/test/resources/fixtures/m10-00/s-i-pattern-rows.tsv")
    }

    @Test
    fun `every spike row is a case of the table`() {
        val rows = AccessMatchCases.readSpikeText().lines().drop(1).count { it.isNotEmpty() }
        assertEquals(rows, AccessMatchCases.spikeRows.size)
    }
}
