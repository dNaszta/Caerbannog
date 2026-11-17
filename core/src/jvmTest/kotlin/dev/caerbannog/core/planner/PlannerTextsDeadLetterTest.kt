package dev.caerbannog.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-09 step 2, written first: the texts of the dead-lettering recipe, word for word. Decided by the owner (spec O19, 2026-11-17) and the labels and safety net
 * the architect proposed in the brief ("Texts"); every sentence is pinned as written, the echo rule is checked for the two sentences that carry a name, and the words the
 * brief forbids are not in any of them. RED until `PlannerTexts` has them.
 */
class PlannerTextsDeadLetterTest {
    @Test
    fun `the decided sentences, word for word`() {
        assertEquals("If the dead-letter target cannot take a message, RabbitMQ drops it.", PlannerTexts.DEAD_LETTER_DEFAULT_NOTE)
        assertEquals(
            "RabbitMQ keeps a message it cannot pass on and retries. Retries can take minutes, so do not rely on a quick delivery.",
            PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE,
        )
        assertEquals(
            "While the queue is full, publishers are refused. A quorum queue holds one message more than its limit.",
            PlannerTexts.DEAD_LETTER_FULL_QUEUE_NOTE,
        )
        assertEquals(
            "Messages that are rejected, expire, are dropped by the queue's length limit or are returned too often are dead-lettered.",
            PlannerTexts.DEAD_LETTER_SUMMARY_CAUSES,
        )
    }

    @Test
    fun `the own-argument sentence names the queue and its exchange`() {
        assertEquals(
            "The queue orders names its own dead-letter exchange legacy-dlx. RabbitMQ uses that one and ignores this policy's exchange.",
            PlannerTexts.deadLetterOwnArgument("orders", "legacy-dlx"),
        )
    }

    @Test
    fun `names the echo rule refuses are left out and the own-argument sentence stays whole`() {
        // Only the case the brief states: the names are refused. A refused name beside an echoable one is not pinned (open point for the architect).
        val whole = "The queue names its own dead-letter exchange. RabbitMQ uses that one and ignores this policy's exchange."
        assertEquals(whole, PlannerTexts.deadLetterOwnArgument("or ders", "le\"gacy"))
        assertEquals(whole, PlannerTexts.deadLetterOwnArgument("<b>x</b>", "<i>y</i>"))
        assertEquals(whole, PlannerTexts.deadLetterOwnArgument("a".repeat(65), "x y"))
        assertEquals(whole, PlannerTexts.deadLetterOwnArgument("", ""))
    }

    @Test
    fun `the summary sentence about the queue, and its fallback`() {
        assertEquals("The queue orders itself is not changed.", PlannerTexts.deadLetterQueueUntouched("orders"))
        assertEquals("The queue itself is not changed.", PlannerTexts.deadLetterQueueUntouched("or ders"))
        assertEquals("The queue itself is not changed.", PlannerTexts.deadLetterQueueUntouched("..\nx"))
        assertEquals("The queue itself is not changed.", PlannerTexts.deadLetterQueueUntouched(""))
    }

    @Test
    fun `the labels and the safety net the brief proposes, as written`() {
        assertEquals("Exchange, dead-letter queue, binding and policy", PlannerTexts.DEAD_LETTER_KIND_DESCRIPTION)
        assertEquals("A dead-letter queue", PlannerTexts.DEAD_LETTER_TARGET_QUEUE)
        assertEquals("A stream", PlannerTexts.DEAD_LETTER_TARGET_STREAM)
        assertEquals("At most once", PlannerTexts.DEAD_LETTER_AT_MOST_ONCE)
        assertEquals("At least once", PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE)
        assertEquals("""Tick "Publishers are refused when the queue is full" to use At least once""", PlannerTexts.DEAD_LETTER_TICK_REQUIRED)
        assertEquals("Dead-lettering does not work for a stream. Choose a queue.", PlannerTexts.DEAD_LETTER_SOURCE_IS_STREAM)
        assertEquals("Choose or type the queue", PlannerTexts.DEAD_LETTER_QUEUE_MISSING)
        assertEquals("dead letters", PlannerTexts.DEAD_LETTER_EDGE_LABEL)
    }

    @Test
    fun `the kind is built and keeps its place between Policy and MQTT route`() {
        assertEquals(listOf("Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue", "MQTT route"), PlannerTexts.WIZARD_KINDS)
        assertEquals(PlannerTexts.WIZARD_KINDS, PlannerTexts.BUILT_KINDS, "every kind of the wizard has a form now")
    }

    @Test
    fun `no text promises a time, a guarantee or the raw death annotation`() {
        val texts = listOf(
            PlannerTexts.DEAD_LETTER_DEFAULT_NOTE, PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE, PlannerTexts.DEAD_LETTER_FULL_QUEUE_NOTE, PlannerTexts.DEAD_LETTER_SUMMARY_CAUSES,
            PlannerTexts.DEAD_LETTER_KIND_DESCRIPTION, PlannerTexts.DEAD_LETTER_TICK_REQUIRED, PlannerTexts.DEAD_LETTER_SOURCE_IS_STREAM, PlannerTexts.DEAD_LETTER_QUEUE_MISSING,
            PlannerTexts.deadLetterOwnArgument("orders", "x"), PlannerTexts.deadLetterQueueUntouched("orders"),
        )
        for (t in texts) {
            assertFalse(Regex("\\d+\\s*(minutes?|min|seconds?|s)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t), "a number of minutes in \"$t\"")
            assertTrue(listOf("guarantee", "never lost", "x-opt-deaths", "reason").none { t.contains(it, ignoreCase = true) }, "a forbidden word in \"$t\"")
            assertTrue(t.none { it in "<>`*" }, "markup in \"$t\"")
        }
        assertEquals(1, Regex("minutes").findAll(PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE).count(), "the one sentence about timing, and it names no number")
    }
}
