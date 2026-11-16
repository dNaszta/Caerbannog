package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.DeclareEquivalenceTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * M9-05b rule 3 as a pure function, `HonouredArgumentRule.extraOnBroker(action, body)`: the name of an argument that the broker's type HONOURS,
 * that is on the broker's item and that the declare does not carry; null otherwise. Called by `ManagementDeclareCheck` only (the matcher the
 * executor's read-back shares is not touched). The honoured sets are the production copy of `DeclareEquivalenceTable` (data-rabbit cannot see
 * core-testing in production), so a table test pins the two together.
 */
class HonouredArgumentRuleTest {

    private fun queueBody(type: String, arguments: String, durable: Boolean = true) =
        """{"name":"orders","vhost":"/","type":"$type","durable":$durable,"auto_delete":false,"arguments":{"x-queue-type":"$type"${if (arguments.isEmpty()) "" else ",$arguments"}}}"""

    private fun exchangeBody(arguments: String) =
        """{"name":"events","vhost":"/","type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{$arguments}}"""

    private fun extra(action: MigrationAction, body: String) = HonouredArgumentRule.extraOnBroker(action, body)

    @Test
    fun `the production sets are exactly the measured sets of the table`() {
        assertEquals(DeclareEquivalenceTable.queueComparedArguments, HonouredArgumentRule.queueHonoured)
        assertEquals(DeclareEquivalenceTable.exchangeComparedArguments, HonouredArgumentRule.exchangeHonoured)
    }

    @Test
    fun `an honoured argument on the broker that the declare lacks is named, for every honoured argument of every queue type`() {
        DeclareEquivalenceTable.queueComparedArguments.forEach { (type, honoured) ->
            honoured.forEach { key ->
                val found = extra(MigrationAction.DeclareQueue("orders", type), queueBody(type, "\"$key\":7"))
                assertEquals(key, found, "$type: $key is honoured, on the broker and not declared")
            }
        }
    }

    @Test
    fun `an argument on the broker that the queue type does not honour is ignored`() {
        // x-max-priority is honoured by classic only, x-delivery-limit by quorum only, x-max-age by stream only
        assertNull(extra(MigrationAction.DeclareQueue("orders", "quorum"), queueBody("quorum", "\"x-max-priority\":3")))
        assertNull(extra(MigrationAction.DeclareQueue("orders", "classic"), queueBody("classic", "\"x-delivery-limit\":3")))
        assertNull(extra(MigrationAction.DeclareQueue("orders", "stream"), queueBody("stream", "\"x-delivery-limit\":3")))
        assertNull(extra(MigrationAction.DeclareQueue("orders", "quorum"), queueBody("quorum", "\"x-max-age\":\"7D\"")))
        DeclareEquivalenceTable.queueComparedArguments.forEach { (type, honoured) ->
            val others = DeclareEquivalenceTable.queueComparedArguments.values.flatten().toSet() - honoured
            others.forEach { key -> assertNull(extra(MigrationAction.DeclareQueue("orders", type), queueBody(type, "\"$key\":7")), "$type does not honour $key") }
        }
    }

    @Test
    fun `an argument outside the twenty classified ones is ignored, as is the made-up name`() {
        assertNull(extra(MigrationAction.DeclareQueue("orders", "quorum"), queueBody("quorum", "\"x-foo\":\"a\",\"x-unclassified-by-the-spike\":1")))
        assertNull(extra(MigrationAction.DeclareExchange("events"), exchangeBody("\"x-foo\":\"a\"")))
    }

    @Test
    fun `the queue type is judged by the type rule, never as an argument`() {
        assertNull(extra(MigrationAction.DeclareQueue("orders", "quorum"), queueBody("quorum", "")), "x-queue-type on the broker and not declared is not an honoured extra")
        assertNull(extra(MigrationAction.DeclareQueue("orders", "classic"), queueBody("classic", "")))
    }

    @Test
    fun `an honoured argument that the declare carries too is not an extra, whatever its value or wire type`() {
        val limit = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
        assertNull(extra(limit, queueBody("quorum", "\"x-delivery-limit\":5")), "same value")
        assertNull(extra(limit, queueBody("quorum", "\"x-delivery-limit\":3")), "another value is the matcher's business (rule 2), not rule 3")
        assertNull(extra(limit, queueBody("quorum", "\"x-delivery-limit\":\"5\"")), "another wire type is the matcher's business too: the number 5 against the text 5")
        assertNull(extra(limit, queueBody("quorum", "\"x-delivery-limit\":5.0")), "5 against 5.0: another kind of number, named by otherNumberKind, not an extra")
    }

    private fun kind(action: MigrationAction, body: String) = HonouredArgumentRule.otherNumberKind(action, body)

    @Test
    fun `a declared honoured number that the broker holds as the other kind of number is named (the broker answers 400 for 10 against 10_0)`() {
        val decimal = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Decimal(5.0)))
        val whole = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
        assertEquals("x-delivery-limit", kind(decimal, queueBody("quorum", "\"x-delivery-limit\":5")), "a decimal declared, a whole number on the broker")
        assertEquals("x-delivery-limit", kind(whole, queueBody("quorum", "\"x-delivery-limit\":5.0")), "a whole number declared, a decimal on the broker")
        assertNull(kind(whole, queueBody("quorum", "\"x-delivery-limit\":5")), "same kind")
        assertNull(kind(decimal, queueBody("quorum", "\"x-delivery-limit\":5.0")), "same kind")
        assertNull(kind(whole, queueBody("quorum", "\"x-delivery-limit\":\"5\"")), "a number against a text is the matcher's business")
        assertNull(kind(whole, queueBody("quorum", "")), "an argument that is not on the broker is the matcher's business")
    }

    @Test
    fun `the number kind of an argument the type ignores is not judged, the broker answers 204 and the read-back compares by value`() {
        val decimal = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-max-priority" to ArgumentValue.Decimal(5.0), "x-foo" to ArgumentValue.Decimal(1.0)))
        assertNull(kind(decimal, queueBody("quorum", "\"x-max-priority\":5,\"x-foo\":1")))
        val exchange = MigrationAction.DeclareExchange("events", arguments = mapOf("x-foo" to ArgumentValue.Decimal(10.0)))
        assertNull(kind(exchange, exchangeBody("\"x-foo\":10")))
    }

    @Test
    fun `the number kind is judged by the same honoured sets, and never for a stream step, a delete or a body that is not an object`() {
        DeclareEquivalenceTable.queueComparedArguments.forEach { (type, honoured) ->
            honoured.forEach { key ->
                val declared = MigrationAction.DeclareQueue("orders", type, arguments = mapOf(key to ArgumentValue.Decimal(7.0)))
                assertEquals(key, kind(declared, queueBody(type, "\"$key\":7")), "$type: $key")
            }
        }
        assertNull(kind(MigrationAction.DeclareStream("s"), queueBody("stream", "\"x-max-length-bytes\":7")))
        assertNull(kind(MigrationAction.DeleteQueue("s"), queueBody("stream", "\"x-max-length-bytes\":7")))
        listOf("", "not json", "[]", "null", """{"arguments":[]}""").forEach { assertNull(kind(MigrationAction.DeclareQueue("o", "quorum"), it), it) }
    }

    @Test
    fun `one declared honoured argument does not hide another honoured one on the broker`() {
        val declared = MigrationAction.DeclareQueue("orders", "quorum", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
        assertEquals("x-max-length", extra(declared, queueBody("quorum", "\"x-delivery-limit\":5,\"x-max-length\":10")))
    }

    @Test
    fun `a queue declared as a stream is judged by the stream set, a bare declare against a product-made stream is different`() {
        val bare = MigrationAction.DeclareQueue("orders", "stream")
        assertEquals("x-queue-leader-locator", extra(bare, queueBody("stream", "\"x-queue-leader-locator\":\"balanced\"")))
        assertNull(extra(bare, queueBody("stream", "\"x-foo\":1")))
    }

    @Test
    fun `an unknown queue type has no honoured set, so nothing is an extra`() {
        assertNull(extra(MigrationAction.DeclareQueue("orders", "lazy-new-type"), queueBody("lazy-new-type", "\"x-max-length\":1")))
    }

    @Test
    fun `an exchange compares only alternate-exchange`() {
        val bare = MigrationAction.DeclareExchange("events")
        assertEquals("alternate-exchange", extra(bare, exchangeBody("\"alternate-exchange\":\"ae\"")))
        assertNull(extra(bare, exchangeBody("\"x-message-ttl\":5,\"x-dead-letter-exchange\":\"dlx\",\"x-max-length\":3")), "queue arguments mean nothing on an exchange")
        assertNull(extra(MigrationAction.DeclareExchange("events", arguments = mapOf("alternate-exchange" to ArgumentValue.Text("other"))), exchangeBody("\"alternate-exchange\":\"ae\"")), "another value is rule 2")
    }

    @Test
    fun `no other action is judged by this rule`() {
        val body = queueBody("stream", "\"x-queue-leader-locator\":\"balanced\"")
        assertNull(extra(MigrationAction.DeclareStream("orders"), body), "a stream step is only 'the item is of type stream'")
        assertNull(extra(MigrationAction.DeleteQueue("orders"), body))
        assertNull(extra(MigrationAction.DeleteExchange("events"), body))
        assertNull(extra(MigrationAction.DeclareBinding("events", "orders", "k"), "[]"))
        assertNull(extra(MigrationAction.DeclarePolicy("limits", "^orders"), body))
        assertNull(extra(MigrationAction.DeclareOperatorPolicy("caps", "^orders"), body))
    }

    @Test
    fun `a body that is not an object with arguments never throws and finds nothing`() {
        val action = MigrationAction.DeclareQueue("orders", "quorum")
        listOf("", "not json", "[]", "null", "42", "\"text\"", "{", "{}", """{"arguments":null}""", """{"arguments":[]}""", """{"arguments":"x"}""", """{"arguments":{}}""").forEach { body ->
            assertNull(extra(action, body), "body [$body]")
            assertNull(extra(MigrationAction.DeclareExchange("events"), body), "exchange body [$body]")
        }
    }

    @Test
    fun `a deeply nested body is not a crash`() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertNull(extra(MigrationAction.DeclareQueue("orders", "quorum"), deep))
    }
}
