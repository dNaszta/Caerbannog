package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.fakes.MigrationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08a scope 6: a file that declares and deletes the same item is refused at parse time (a rerun after a failure would delete what the
 * first run created). Item identity: queue, exchange, stream and policy by kind, vhost and name; a binding by vhost, source, destination
 * and routing key. Operator policies are another kind than policies (`deletePolicy` deletes a policy). Either order is refused.
 * The message names the item only when it is short and plain; the wording is otherwise the same.
 */
class RerunSafetyTest {

    private fun file(vararg actions: String) = MigrationTestSupport.fileWith(*actions)
    private fun q(action: String, name: String, vhost: String? = null) =
        """{ "action": "$action", "name": ${quote(name)}${vhost?.let { ", \"vhost\": ${quote(it)}" } ?: ""} }"""
    private fun quote(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
    private fun binding(action: String, exchange: String = "events", queue: String = "orders", key: String? = "k", vhost: String? = null) =
        """{ "action": "$action", "exchange": "$exchange", "queue": "$queue"${key?.let { ", \"routingKey\": ${quote(it)}" } ?: ""}${vhost?.let { ", \"vhost\": ${quote(it)}" } ?: ""} }"""
    private fun policy(action: String, name: String = "limits") =
        if (action == "deletePolicy") """{ "action": "deletePolicy", "name": "$name" }"""
        else """{ "action": "$action", "name": "$name", "pattern": "^orders" }"""

    private fun refused(raw: String): String = assertIs<MigrationValidationResult.Invalid>(MigrationParser.parse(raw), "expected a refusal for: $raw").reason
    private fun accepted(raw: String) = assertIs<MigrationValidationResult.Valid>(MigrationParser.parse(raw), "expected a valid file for: $raw")

    // ---- refused: each kind --------------------------------------------------------------------------------------------

    @Test
    fun `declaring and deleting the same queue is refused and names it`() {
        assertEquals(
            "the file declares and deletes \"orders\"; split it into two migrations",
            refused(file(q("declareQueue", "orders"), q("deleteQueue", "orders"))),
        )
    }

    @Test
    fun `the same for an exchange, a stream and a policy`() {
        for (pair in listOf(
            q("declareExchange", "events") to q("deleteExchange", "events"),
            q("declareStream", "audit") to q("deleteStream", "audit"),
            policy("declarePolicy") to policy("deletePolicy"),
        )) {
            val reason = refused(file(pair.first, pair.second))
            assertTrue(reason.contains("declares and deletes") && reason.contains("split it into two migrations"), reason)
        }
    }

    @Test
    fun `the same for a binding with the same source, destination and routing key`() {
        val reason = refused(file(binding("declareBinding"), binding("deleteBinding")))
        assertTrue(reason.contains("declares and deletes") && reason.contains("split it into two migrations"), reason)
    }

    @Test
    fun `the order does not matter, the delete may come first`() {
        assertTrue(refused(file(q("deleteQueue", "orders"), q("declareQueue", "orders"))).contains("declares and deletes"))
        assertTrue(refused(file(binding("deleteBinding"), binding("declareBinding"))).contains("declares and deletes"))
    }

    @Test
    fun `a delete far from its declare in a long file is still found`() {
        val filler = (1..30).map { q("declareQueue", "other-$it") }
        assertTrue(refused(file(*(listOf(q("declareQueue", "orders")) + filler + listOf(q("deleteQueue", "orders"))).toTypedArray())).contains("declares and deletes"))
    }

    @Test
    fun `a binding with an empty routing key is identified by the empty key`() {
        assertTrue(refused(file(binding("declareBinding", key = null), binding("deleteBinding", key = null))).contains("declares and deletes"))
        assertTrue(refused(file(binding("declareBinding", key = ""), binding("deleteBinding", key = null))).contains("declares and deletes"))
    }

    @Test
    fun `an explicit default virtual host is the same item as an absent one`() {
        assertTrue(refused(file(q("declareQueue", "orders", vhost = "/"), q("deleteQueue", "orders"))).contains("declares and deletes"))
    }

    // ---- accepted --------------------------------------------------------------------------------------------------------

    @Test
    fun `different names are fine`() {
        accepted(file(q("declareQueue", "orders"), q("deleteQueue", "old-orders")))
        accepted(file(q("declareExchange", "events"), q("deleteExchange", "old-events")))
    }

    @Test
    fun `different kinds with the same name are fine`() {
        accepted(file(q("declareQueue", "orders"), q("deleteExchange", "orders")))
        accepted(file(q("declareExchange", "orders"), q("deleteQueue", "orders")))
        accepted(file(q("declareQueue", "orders"), q("deleteStream", "orders")))
        accepted(file(q("declareStream", "orders"), q("deleteQueue", "orders")))
        accepted(file(q("declareQueue", "limits"), policy("deletePolicy")))
    }

    @Test
    fun `an operator policy and a policy are different kinds`() {
        accepted(file(policy("declareOperatorPolicy"), policy("deletePolicy")))
    }

    @Test
    fun `the same name in another virtual host is another item`() {
        accepted(file(q("declareQueue", "orders", vhost = "/a"), q("deleteQueue", "orders", vhost = "/b")))
    }

    @Test
    fun `a binding differs by source, destination or routing key`() {
        accepted(file(binding("declareBinding"), binding("deleteBinding", key = "other")))
        accepted(file(binding("declareBinding"), binding("deleteBinding", exchange = "other")))
        accepted(file(binding("declareBinding"), binding("deleteBinding", queue = "other")))
        accepted(file(binding("declareBinding"), binding("deleteBinding", vhost = "/other")))
    }

    @Test
    fun `a file that only declares, only deletes, or repeats itself is fine`() {
        accepted(file(q("declareQueue", "orders"), q("declareQueue", "orders")))
        accepted(file(q("deleteQueue", "orders"), q("deleteQueue", "orders")))
        accepted(file())
    }

    @Test
    fun `a declare in one file and a delete in another file are independent`() {
        accepted(file(q("declareQueue", "orders")))
        accepted(file(q("deleteQueue", "orders")))
    }

    // ---- the message ----------------------------------------------------------------------------------------------------

    @Test
    fun `a name that is not short and plain is not echoed, the wording stays the same`() {
        val hostile = listOf("a\"quoted", "line\nbreak", "<script>alert(1)</script>", "x".repeat(65), "back\\slash", "a b", "tab\there", "unicode- -sep")
        for (name in hostile) {
            val reason = refused(file(q("declareQueue", name), q("deleteQueue", name)))
            assertTrue(reason.contains("declares and deletes"), reason)
            assertTrue(reason.endsWith("split it into two migrations"), reason)
            assertFalse(reason.contains(name), "echoes the name: $reason")
            assertFalse(reason.contains("\n"), "multi-line reason")
        }
    }

    @Test
    fun `names with dot, underscore and dash are named too`() {
        for (name in listOf("order-events", "orders.v2", "my_queue", "Q-1.a_b")) {
            assertTrue(refused(file(q("declareQueue", name), q("deleteQueue", name))).contains("\"$name\""), name)
        }
    }

    @Test
    fun `a short plain name is named in quotes`() {
        assertTrue(refused(file(q("declareStream", "audit64"), q("deleteStream", "audit64"))).contains("\"audit64\""))
    }

    // ---- compatibility -------------------------------------------------------------------------------------------------

    @Test
    fun `the old-format and widened fixtures and the export golden file still parse`() {
        for (name in listOf(MigrationFixtures.OLD_FORMAT, MigrationFixtures.WIDENED, MigrationFixtures.EXPORT_GOLDEN)) {
            accepted(MigrationFixtures.read(name))
        }
    }

    @Test
    fun `the refusal goes through the file repository import as well`() {
        val repository = InMemoryMigrationFileRepository()
        val result = kotlinx.coroutines.runBlocking { repository.importFile(file(q("declareQueue", "orders"), q("deleteQueue", "orders"))) }
        assertIs<MigrationValidationResult.Invalid>(result)
        assertTrue(kotlinx.coroutines.runBlocking { repository.listFiles() }.isEmpty())
    }
}
