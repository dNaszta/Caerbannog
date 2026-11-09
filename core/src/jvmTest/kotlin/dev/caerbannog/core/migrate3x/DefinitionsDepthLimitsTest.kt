package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.MAX_ARGUMENT_DEPTH
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefinitionsDepthLimitsTest {
    @Test
    fun `the text depth guard leaves room for the argument depth plus the file structure`() =
        assertTrue(MAX_TEXT_DEPTH >= MAX_ARGUMENT_DEPTH + 8)
}
