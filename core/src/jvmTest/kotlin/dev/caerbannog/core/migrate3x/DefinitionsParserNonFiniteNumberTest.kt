package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The tree reader accepts `NaN`, `Infinity` and out-of-range numbers; a non-finite value must be rejected, not become a Decimal. */
class DefinitionsParserNonFiniteNumberTest {

    @ParameterizedTest
    @ValueSource(strings = ["NaN", "Infinity", "-Infinity", "1.5e400", "-1e999"])
    fun `a non-finite number in an argument makes the section malformed`(value: String) {
        val text = """{"queues":[{"name":"a","vhost":"/","arguments":{"x":$value}}]}"""
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection(text))
    }
}
