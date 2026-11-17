package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M9-01b: the two pure rules the forms use (S-H of docs/tasks/M9-00c-spike-results.md) and the two fixed texts decided by the owner on 2026-11-16. */
class BrokerNameRulesTest {
    @Test
    fun `the reserved prefix is the lower-case amq dot`() {
        for (name in listOf("amq.", "amq.orders", "amq.gen-x", "amq.dln")) assertTrue(BrokerNameRules.reservedPrefix(name), name)
        for (name in listOf("", "amq", "AMQ.x", "Amq.x", "amq-x", "xamq.x", " amq.x", "am", "amq,x")) assertFalse(BrokerNameRules.reservedPrefix(name), "\"$name\"")
    }

    @Test
    fun `a queue name is too long above 255 bytes, not above 255 characters`() {
        assertFalse(BrokerNameRules.queueNameTooLong(""))
        assertFalse(BrokerNameRules.queueNameTooLong("a".repeat(255)))
        assertTrue(BrokerNameRules.queueNameTooLong("a".repeat(256)))
        assertFalse(BrokerNameRules.queueNameTooLong("é".repeat(127) + "a"))
        assertTrue(BrokerNameRules.queueNameTooLong("é".repeat(128)))
        assertFalse(BrokerNameRules.queueNameTooLong("a".repeat(251) + "\uD83D\uDE00"))
        assertTrue(BrokerNameRules.queueNameTooLong("a".repeat(252) + "\uD83D\uDE00"))
    }

    @Test
    fun `the two texts are the decided wording`() {
        assertEquals("Names that start with \"amq.\" are reserved", PlannerTexts.NAME_RESERVED)
        assertEquals("A queue name can be at most 255 bytes long", PlannerTexts.QUEUE_NAME_TOO_LONG)
    }
}
