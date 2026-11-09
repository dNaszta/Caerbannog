package dev.caerbannog.core.migrations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M5-08a: the one echo rule. A name is echoed only if 1 to 64 characters and either exactly "/" or only A-Z a-z 0-9 . _ - and not "." or "..". */
class EchoableNameTest {
    @Test
    fun `plain names are echoed`() {
        for (name in listOf("orders", "order-events", "orders.v2", "my_queue", "Q1", "/", "a", "...", "a..b", "x".repeat(64), "-", "_")) {
            assertTrue(echoableName(name), "\"$name\" should be echoed")
        }
    }

    @Test
    fun `everything else is never echoed`() {
        val never = listOf(
            "", ".", "..", "x".repeat(65), "a b", " ", "a\"b", "a'b", "back\\slash", "line\nbreak", "tab\t", "a\u0000b", "<script>", "a/b", "/orders", "//", "a%b",
            "a?b", "a#b", "a&b", "a;b", "ünï", "日本語", "a\u2028b", "a\u0085b",
        )
        for (name in never) assertFalse(echoableName(name), "\"${name.take(20).replace("\n", "\\n")}\" must not be echoed")
    }

    @Test
    fun `the length limit is 64 characters`() {
        assertTrue(echoableName("a".repeat(64)))
        assertFalse(echoableName("a".repeat(65)))
        assertFalse(echoableName("/" + "a".repeat(64)))
    }
}
