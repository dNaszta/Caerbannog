package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M9-05: the port [DeclareCheck] and its answer [BrokerFinding], and the honesty of [FakeDeclareCheck] (core-testing), which every core and
 * acceptance test of the check stands on: it answers only what is scripted, records every call, and never invents a default.
 */
class DeclareCheckTest {

    private val orders = MigrationAction.DeclareQueue("orders")
    private val events = MigrationAction.DeclareExchange("events")

    @Test
    fun `a finding is one of four, and only Unreadable carries a reason`() {
        val all: List<BrokerFinding> = listOf(BrokerFinding.Absent, BrokerFinding.Same, BrokerFinding.Different, BrokerFinding.Unreadable("the broker did not answer"))
        val words = all.map {
            when (it) { // exhaustive on purpose: a fifth finding must be decided here
                BrokerFinding.Absent -> "absent"
                BrokerFinding.Same -> "same"
                BrokerFinding.Different -> "different"
                is BrokerFinding.Unreadable -> "unreadable:${it.reason}"
            }
        }
        assertEquals(listOf("absent", "same", "different", "unreadable:the broker did not answer"), words)
        assertEquals(BrokerFinding.Unreadable("a"), BrokerFinding.Unreadable("a"))
        assertNotEquals<BrokerFinding>(BrokerFinding.Unreadable("a"), BrokerFinding.Unreadable("b"))
    }

    @Test
    fun `the fake answers what was scripted for the exact action, and records the call`() = runBlocking {
        val check = FakeDeclareCheck().answer(orders, BrokerFinding.Same)
        assertEquals<BrokerFinding>(BrokerFinding.Same, check.find(orders))
        assertEquals(listOf<MigrationAction>(orders), check.calls)
    }

    @Test
    fun `the fake answers by name for any action about that name, a probe included`() = runBlocking {
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Absent)
        assertEquals<BrokerFinding>(BrokerFinding.Absent, check.find(MigrationAction.DeclareExchange("events", type = "topic")))
        assertEquals<BrokerFinding>(BrokerFinding.Absent, check.find(MigrationAction.DeleteExchange("events")))
    }

    @Test
    fun `an exact answer wins over a name`() = runBlocking {
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Absent).answer(orders, BrokerFinding.Different)
        assertEquals<BrokerFinding>(BrokerFinding.Different, check.find(orders))
    }

    @Test
    fun `a call nobody scripted throws and says which action, and is still recorded`() {
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        val failure = assertFailsWith<IllegalStateException> { runBlocking { check.find(events) } }
        assertTrue(failure.message.orEmpty().contains("Declare direct exchange events"), failure.message)
        assertEquals(listOf<MigrationAction>(events), check.calls)
    }

    @Test
    fun `a binding has no name to script by, only an exact answer`() {
        val binding = MigrationAction.DeclareBinding("events", "orders", "k")
        val check = FakeDeclareCheck().answerNamed("events", BrokerFinding.Same).answerNamed("orders", BrokerFinding.Same)
        assertFailsWith<IllegalStateException> { runBlocking { check.find(binding) } }
        check.answer(binding, BrokerFinding.Absent)
        assertEquals<BrokerFinding>(BrokerFinding.Absent, runBlocking { check.find(binding) })
    }

    @Test
    fun `a scripted block can hold the read, and the call is recorded before it is answered`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val check = FakeDeclareCheck().answerWith("orders") { gate.await(); BrokerFinding.Same }
        val call = async(start = CoroutineStart.UNDISPATCHED) { check.find(orders) }
        yield()
        assertEquals(listOf<MigrationAction>(orders), check.calls, "recorded while still waiting")
        assertTrue(call.isActive)
        gate.complete(Unit)
        assertEquals<BrokerFinding>(BrokerFinding.Same, call.await())
    }
}
