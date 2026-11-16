package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanNamingTest {
    private val k = NamingStyle.KEBAB
    private val s = NamingStyle.SNAKE
    private val d = NamingStyle.DOT
    private val c = NamingStyle.CAMEL
    private val n = NamingStyle.NONE

    @Test
    fun `labels`() {
        assertEquals(listOf("kebab-case", "snake_case", "dot.separated", "camelCase", "No convention"), NamingStyle.entries.map { it.label })
        assertEquals(listOf("dln", "dlq", "dlx", "retention"), DerivedWord.entries.map { it.word })
    }

    @Test
    fun `derived joins the word in the style`() {
        val table = listOf(
            k to "orders-dln", s to "orders_dln", d to "orders.dln", c to "ordersDln", n to "orders-dln",
        )
        for ((style, expected) in table) assertEquals(expected, PlanNaming.derived("orders", DerivedWord.DLN, style), style.label)
        assertEquals("orders-retention", PlanNaming.derived("orders", DerivedWord.RETENTION, k))
        assertEquals("ordersDlq", PlanNaming.derived("orders", DerivedWord.DLQ, c))
        assertEquals("orders.dlx", PlanNaming.derived("orders", DerivedWord.DLX, d))
    }

    @Test
    fun `derived keeps the base as typed`() {
        assertEquals("Orders_EU-dln", PlanNaming.derived("Orders_EU", DerivedWord.DLN, k))
        assertEquals("Orders_EU_dln", PlanNaming.derived("Orders_EU", DerivedWord.DLN, s))
        assertEquals("OrdersEUDln", PlanNaming.derived("OrdersEU", DerivedWord.DLN, c))
    }

    @Test
    fun `follows kebab-case`() {
        for (ok in listOf("orders", "orders-eu", "orders-2", "a1-b2-c3")) assertTrue(PlanNaming.follows(ok, k), ok)
        for (bad in listOf("Orders", "orders_eu", "orders.eu", "ordersEu", "orders eu", "-orders", "orders-", "orders--eu")) assertFalse(PlanNaming.follows(bad, k), bad)
    }

    @Test
    fun `follows snake_case`() {
        for (ok in listOf("orders", "orders_eu", "orders_2")) assertTrue(PlanNaming.follows(ok, s), ok)
        for (bad in listOf("Orders_eu", "orders-eu", "orders.eu", "ordersEu", "_orders", "orders_", "orders__eu")) assertFalse(PlanNaming.follows(bad, s), bad)
    }

    @Test
    fun `follows dot separated`() {
        for (ok in listOf("orders", "orders.eu", "orders.2")) assertTrue(PlanNaming.follows(ok, d), ok)
        for (bad in listOf("Orders.eu", "orders-eu", "orders_eu", "ordersEu", ".orders", "orders.", "orders..eu")) assertFalse(PlanNaming.follows(bad, d), bad)
    }

    @Test
    fun `follows camelCase`() {
        for (ok in listOf("orders", "ordersEu", "orders1Eu", "ordersEuWest")) assertTrue(PlanNaming.follows(ok, c), ok)
        for (bad in listOf("Orders", "OrdersEu", "orders-eu", "orders_eu", "orders.eu", "1orders")) assertFalse(PlanNaming.follows(bad, c), bad)
    }

    @Test
    fun `no convention accepts anything`() {
        for (any in listOf("", "Orders_EU", "..", "a b", "\u0000")) assertTrue(PlanNaming.follows(any, n), any)
    }

    @Test
    fun `example rebuilds a name in the style`() {
        val table = listOf(
            Triple("Orders_EU", k, "orders-eu"),
            Triple("orders_eu", k, "orders-eu"),
            Triple("ordersEu", k, "orders-eu"),
            Triple("ordersEu", d, "orders.eu"),
            Triple("orders-eu", c, "ordersEu"),
            Triple("orders-eu", s, "orders_eu"),
            Triple("Orders EU", s, "orders_eu"),
            Triple("orders.eu", k, "orders-eu"),
            Triple("ordersEU", k, "orders-eu"),
            Triple("order2Eu", k, "order2-eu"),
            Triple("Orders_EU", c, "ordersEu"),
        )
        for ((name, style, expected) in table) assertEquals(expected, PlanNaming.example(name, style), "$name as ${style.label}")
    }

    @Test
    fun `example is null when no word can be built`() {
        for (name in listOf("", "---", "...", "_ _", "   ")) assertNull(PlanNaming.example(name, k), "\"$name\"")
    }

    @Test
    fun `hint says what is wrong and gives the example`() {
        assertEquals("This name does not follow kebab-case, for example orders-eu", PlanNaming.hint("Orders_EU", k))
        assertEquals("This name does not follow snake_case, for example orders_eu", PlanNaming.hint("orders-eu", s))
        assertEquals("This name does not follow dot.separated, for example orders.eu", PlanNaming.hint("ordersEu", d))
        assertEquals("This name does not follow camelCase, for example ordersEu", PlanNaming.hint("orders-eu", c))
    }

    @Test
    fun `hint is null for an empty name, for no convention and for a name that follows`() {
        assertNull(PlanNaming.hint("", k))
        assertNull(PlanNaming.hint("Orders_EU", n))
        assertNull(PlanNaming.hint("orders-eu", k))
    }

    @Test
    fun `hint has no example when none can be built`() {
        assertEquals("This name does not follow kebab-case", PlanNaming.hint("___", k))
    }

    @Test
    fun `baseOf finds the base of a derived name in any style`() {
        for (name in listOf("orders-dln", "orders_dln", "orders.dln", "ordersDln")) assertEquals("orders", PlanNaming.baseOf(name, DerivedWord.DLN), name)
        assertEquals("orders", PlanNaming.baseOf("orders-dlq", DerivedWord.DLQ))
        assertEquals("a-b", PlanNaming.baseOf("a-b-retention", DerivedWord.RETENTION))
    }

    @Test
    fun `baseOf is null when the name is not derived`() {
        assertNull(PlanNaming.baseOf("orders", DerivedWord.DLN))
        assertNull(PlanNaming.baseOf("orders-dlq", DerivedWord.DLN))
        assertNull(PlanNaming.baseOf("ordersdln", DerivedWord.DLN))
        assertNull(PlanNaming.baseOf("dln", DerivedWord.DLN))
        assertNull(PlanNaming.baseOf("-dln", DerivedWord.DLN))
        assertNull(PlanNaming.baseOf("", DerivedWord.DLN))
    }

    @Test
    fun `derived then baseOf is the identity in every style`() {
        for (style in NamingStyle.entries) for (word in DerivedWord.entries) {
            assertEquals("orders", PlanNaming.baseOf(PlanNaming.derived("orders", word, style), word), "${style.label} ${word.word}")
        }
    }

    @Test
    fun `hostile names never throw and a hint never holds a control character`() {
        val hostile = listOf("", "x".repeat(1000), "a-".repeat(500), "\u0000\u0007\n\t", "a\u0000b", "___---...", "😀", "A".repeat(1000) + "b")
        for (name in hostile) for (style in NamingStyle.entries) {
            PlanNaming.follows(name, style)
            PlanNaming.example(name, style)
            PlanNaming.baseOf(name, DerivedWord.DLN)
            PlanNaming.derived(name, DerivedWord.DLX, style)
            val hint = PlanNaming.hint(name, style)
            assertTrue(hint == null || hint.none { it < ' ' }, "hint for hostile name holds a control character: $hint")
            assertTrue(hint == null || hint.length < 400, "a hint stays short for a long name")
        }
    }
}
