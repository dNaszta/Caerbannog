package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-04: no Shovel action, no delete action for a vhost or user, and an untrusted action name is not echoed. (M8-12a: `declareUser` is a known action now; its tests are in AccessActionCodecsTest.) */
class MigrationUnknownActionTest {

    private val phrase = "which action is not supported"

    @Test
    fun `declareShovel, deleteVhost, deleteUser and deletePermissions are refused as unknown actions`() {
        for (name in listOf("declareShovel", "deleteVhost", "deleteUser", "deletePermissions")) {
            val message = reason(fileWith("""{ "action": "$name", "name": "x" }"""))
            assertTrue(message.endsWith(phrase), "for $name: \"$message\"")
        }
    }

    @Test
    fun `a short plain name is still named in the message`() {
        assertEquals("action 'declareShovel' is not supported; which action is not supported", reason(fileWith("""{ "action": "declareShovel" }""")))
        assertEquals("action 'unknownActionXYZ' is not supported; which action is not supported", reason(fileWith("""{ "action": "unknownActionXYZ" }""")))
    }

    @Test
    fun `a 200-character action name is not echoed`() {
        val long = "a".repeat(200)
        val message = reason(fileWith("""{ "action": "$long" }"""))
        assertTrue(message.endsWith(phrase), message)
        assertFalse(message.contains("aaaaaaaa"), "the long name is echoed: $message")
        assertTrue(message.length < 120, "message length ${message.length}")
    }

    @Test
    fun `a 64-character name may be named and a 65-character name may not`() {
        assertTrue(reason(fileWith("""{ "action": "${"b".repeat(64)}" }""")).contains("b".repeat(64)))
        assertFalse(reason(fileWith("""{ "action": "${"b".repeat(65)}" }""")).contains("bbbb"))
    }

    @Test
    fun `an action name with anything but letters and digits is not echoed`() {
        for (hostile in listOf("declare-Shovel", "declare Shovel", "<script>alert(1)</script>", "ab\\ncd", "x'; DROP", "declare_shovel", "café")) {
            val message = reason(fileWith("""{ "action": "$hostile" }"""))
            assertTrue(message.endsWith(phrase), message)
            assertFalse(message.contains("Shovel") || message.contains("script") || message.contains("DROP") || message.contains("shovel"), "echoed: $message")
        }
    }

    @Test
    fun `a missing, blank or non-object action entry says which action is not supported`() {
        assertEquals(phrase, reason(fileWith("""{ "name": "x" }""")))
        assertEquals(phrase, reason(fileWith("""{ "action": "" }""")))
        assertEquals(phrase, reason(fileWith("42")))
    }

    @Test
    fun `an unknown action after valid ones refuses the whole file`() {
        val text = fileWith("""{ "action": "declareQueue", "name": "q" }""", """{ "action": "declareShovel" }""")
        assertTrue(reason(text).endsWith(phrase))
    }
}
