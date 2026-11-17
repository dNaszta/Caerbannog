package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals

/** Changing the type of a header row: the value follows what the new type can hold (the form view calls this and decides nothing). */
class HeaderMatchWithTypeTest {
    @Test
    fun `a boolean starts at true`() {
        assertEquals(HeaderMatch("a", HeaderValueType.FLAG, "true"), HeaderMatch("a", HeaderValueType.TEXT, "x").withType(HeaderValueType.FLAG))
    }

    @Test
    fun `leaving a boolean clears the chosen word`() {
        assertEquals(HeaderMatch("a", HeaderValueType.WHOLE, ""), HeaderMatch("a", HeaderValueType.FLAG, "false").withType(HeaderValueType.WHOLE))
    }

    @Test
    fun `text and number keep what was typed, the same type changes nothing`() {
        assertEquals(HeaderMatch("a", HeaderValueType.WHOLE, "12"), HeaderMatch("a", HeaderValueType.TEXT, "12").withType(HeaderValueType.WHOLE))
        val same = HeaderMatch("a", HeaderValueType.FLAG, "false")
        assertEquals(same, same.withType(HeaderValueType.FLAG))
    }
}
