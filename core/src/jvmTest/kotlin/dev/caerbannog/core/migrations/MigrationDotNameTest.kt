package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-04 round 1 (F4): `.` and `..` are never a queue, exchange, stream, policy or vhost name; `...` and `a.b` are fine. */
class MigrationDotNameTest {

    private val refusal = "a name must not be \".\" or \"..\""

    private val templates: Map<String, (String) -> String> = mapOf(
        "declareQueue.name" to { v -> """{ "action": "declareQueue", "name": "$v" }""" },
        "deleteQueue.name" to { v -> """{ "action": "deleteQueue", "name": "$v" }""" },
        "declareExchange.name" to { v -> """{ "action": "declareExchange", "name": "$v" }""" },
        "deleteExchange.name" to { v -> """{ "action": "deleteExchange", "name": "$v" }""" },
        "declareStream.name" to { v -> """{ "action": "declareStream", "name": "$v" }""" },
        "deleteStream.name" to { v -> """{ "action": "deleteStream", "name": "$v" }""" },
        "declarePolicy.name" to { v -> """{ "action": "declarePolicy", "name": "$v" }""" },
        "declareOperatorPolicy.name" to { v -> """{ "action": "declareOperatorPolicy", "name": "$v" }""" },
        "deletePolicy.name" to { v -> """{ "action": "deletePolicy", "name": "$v" }""" },
        "declareBinding.exchange" to { v -> """{ "action": "declareBinding", "exchange": "$v", "queue": "q" }""" },
        "declareBinding.queue" to { v -> """{ "action": "declareBinding", "exchange": "e", "queue": "$v" }""" },
        "declareBinding.destination" to { v -> """{ "action": "declareBinding", "exchange": "e", "destination": "$v" }""" },
        "deleteBinding.exchange" to { v -> """{ "action": "deleteBinding", "exchange": "$v", "queue": "q" }""" },
        "deleteBinding.queue" to { v -> """{ "action": "deleteBinding", "exchange": "e", "queue": "$v" }""" },
        "deleteBinding.destination" to { v -> """{ "action": "deleteBinding", "exchange": "e", "destination": "$v" }""" },
        "declareQueue.vhost" to { v -> """{ "action": "declareQueue", "name": "q", "vhost": "$v" }""" },
        "declareExchange.vhost" to { v -> """{ "action": "declareExchange", "name": "x", "vhost": "$v" }""" },
        "declareBinding.vhost" to { v -> """{ "action": "declareBinding", "exchange": "e", "queue": "q", "vhost": "$v" }""" },
        "declarePolicy.vhost" to { v -> """{ "action": "declarePolicy", "name": "p", "vhost": "$v" }""" },
        "declareStream.vhost" to { v -> """{ "action": "declareStream", "name": "s", "vhost": "$v" }""" },
    )

    @Test
    fun `a single dot and two dots are refused in every name field`() {
        for ((field, template) in templates) for (v in listOf(".", "..")) {
            val text = fileWith(template(v))
            val result = try { reason(text) } catch (t: Throwable) { throw AssertionError("$field=\"$v\" was not refused: ${t.message}") }
            assertEquals(refusal, result, "$field=\"$v\"")
        }
    }

    @Test
    fun `three dots and a dot inside a name are accepted`() {
        for ((field, template) in templates) for (v in listOf("...", "a.b", ".a", "a.", "orders.v1")) {
            val parsed = MigrationParser.parse(fileWith(template(v)))
            assertTrue(parsed is MigrationValidationResult.Valid, "$field=\"$v\" must be accepted but got $parsed")
        }
    }

    @Test
    fun `the message does not name the field or the value`() {
        assertEquals(refusal, reason(fileWith(templates.getValue("declareQueue.name")(".."))))
    }
}
