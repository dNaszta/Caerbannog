package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * M5-08a scope 2 (conflict rule), open point 2 pinned here: a policy that exists is compared before any PUT. Same pattern, apply-to,
 * priority and definition (typed, numbers numerically: `5` equals `5.0` and `5.00`, never `"5"`; object key order irrelevant, list order
 * relevant) is SAME; every other body, including one that cannot be read, is DIFFERENT (never overwrite what cannot be compared).
 */
class ConflictCheckTest {

    private fun broker(
        pattern: String = "^orders", applyTo: String = "queues", priority: String = "0",
        definition: String = """{"max-length":500}""", extra: String = """"vhost":"/","name":"limits",""",
    ) = """{$extra"pattern":"$pattern","apply-to":"$applyTo","definition":$definition,"priority":$priority}"""

    private fun policy(
        definition: Map<String, ArgumentValue> = mapOf("max-length" to ArgumentValue.Whole(500)),
        pattern: String = "^orders", applyTo: String = "queues", priority: Int = 0,
    ) = MigrationAction.DeclarePolicy("limits", pattern, definition, applyTo = applyTo, priority = priority)

    private fun same(body: String, wanted: MigrationAction = policy()) = assertEquals(PolicyComparison.SAME, ConflictCheck.comparePolicy(body, wanted), body)
    private fun different(body: String, wanted: MigrationAction = policy()) = assertEquals(PolicyComparison.DIFFERENT, ConflictCheck.comparePolicy(body, wanted), body)

    @Test
    fun `an equal policy is the same, the broker's vhost and name keys are ignored`() {
        same(broker())
        same(broker(extra = ""))
    }

    @Test
    fun `each field that differs makes it different`() {
        different(broker(pattern = "^orders-2"))
        different(broker(applyTo = "all"))
        different(broker(priority = "1"))
        different(broker(definition = """{"max-length":501}"""))
    }

    @Test
    fun `a definition value 5 against the text 5 is different, in both directions`() {
        different(broker(definition = """{"max-length":"500"}"""))
        different(broker(definition = """{"overflow":"drop-head"}"""), policy(mapOf("overflow" to ArgumentValue.Whole(5))))
        different(broker(definition = """{"max-length":5}"""), policy(mapOf("max-length" to ArgumentValue.Text("5"))))
    }

    @Test
    fun `a missing key and an extra key are different`() {
        different(broker(definition = """{}"""))
        different(broker(definition = """{"max-length":500,"overflow":"reject-publish"}"""))
        different(broker(definition = """{"max-length":500}"""), policy(mapOf("max-length" to ArgumentValue.Whole(500), "overflow" to ArgumentValue.Text("drop-head"))))
        different(broker(definition = """{"other":500}"""))
    }

    @Test
    fun `whole and decimal numbers with the same value are equal`() {
        same(broker(definition = """{"max-length":500.0}"""))
        same(broker(definition = """{"max-length":5E2}"""))
        same(broker(definition = """{"ratio":0.5}"""), policy(mapOf("ratio" to ArgumentValue.Decimal(0.5))))
        same(broker(definition = """{"ratio":1}"""), policy(mapOf("ratio" to ArgumentValue.Decimal(1.0))))
        different(broker(definition = """{"ratio":0.5}"""), policy(mapOf("ratio" to ArgumentValue.Decimal(0.25))))
    }

    @Test
    fun `large whole numbers are compared exactly, not through a double`() {
        val big = 9_007_199_254_740_993L // 2^53 + 1: not representable as a double
        same(broker(definition = """{"n":$big}"""), policy(mapOf("n" to ArgumentValue.Whole(big))))
        different(broker(definition = """{"n":9007199254740992}"""), policy(mapOf("n" to ArgumentValue.Whole(big))))
    }

    @Test
    fun `key order is irrelevant, in the definition and nested`() {
        same(
            broker(definition = """{"b":2,"a":1}"""),
            policy(mapOf("a" to ArgumentValue.Whole(1), "b" to ArgumentValue.Whole(2))),
        )
        same(
            broker(definition = """{"ha":{"y":2,"x":1}}"""),
            policy(mapOf("ha" to ArgumentValue.Table(mapOf("x" to ArgumentValue.Whole(1), "y" to ArgumentValue.Whole(2))))),
        )
    }

    @Test
    fun `list order matters and nested values are compared typed`() {
        val wanted = policy(mapOf("nodes" to ArgumentValue.Items(listOf(ArgumentValue.Text("a"), ArgumentValue.Whole(1)))))
        same(broker(definition = """{"nodes":["a",1]}"""), wanted)
        different(broker(definition = """{"nodes":[1,"a"]}"""), wanted)
        different(broker(definition = """{"nodes":["a","1"]}"""), wanted)
        different(broker(definition = """{"nodes":["a"]}"""), wanted)
    }

    @Test
    fun `flags, texts and null are compared by type`() {
        val wanted = policy(mapOf("f" to ArgumentValue.Flag(true), "t" to ArgumentValue.Text("x"), "n" to ArgumentValue.Nothing))
        same(broker(definition = """{"f":true,"t":"x","n":null}"""), wanted)
        different(broker(definition = """{"f":"true","t":"x","n":null}"""), wanted)
        different(broker(definition = """{"f":false,"t":"x","n":null}"""), wanted)
        different(broker(definition = """{"f":true,"t":"x","n":"null"}"""), wanted)
    }

    @Test
    fun `an operator policy is compared the same way`() {
        val op = MigrationAction.DeclareOperatorPolicy("op", "^orders", mapOf("max-length" to ArgumentValue.Whole(500)), applyTo = "queues")
        same(broker(), op)
        different(broker(definition = """{"max-length":400}"""), op)
        different(broker(applyTo = "all"), op)
    }

    @Test
    fun `an empty definition equals an empty definition`() {
        same(broker(definition = "{}"), policy(emptyMap()))
        different(broker(definition = """{"a":1}"""), policy(emptyMap()))
    }

    @Test
    fun `a body that is not a policy object is different, never an exception`() {
        for (body in listOf("", "not json", "[]", "null", "42", "{}", """{"pattern":1}""", """{"pattern":"^orders"}""", """{"pattern":"^orders","apply-to":"queues","priority":0}""", """{"pattern":"^orders","apply-to":"queues","priority":"0","definition":{"max-length":500}}""", """{"pattern":"^orders","apply-to":"queues","priority":0,"definition":[1]}""")) {
            different(body)
        }
    }

    @Test
    fun `only policies can be compared`() {
        assertThrows(IllegalArgumentException::class.java) { ConflictCheck.comparePolicy(broker(), MigrationAction.DeclareQueue("q")) }
    }
}
