package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
import dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
import dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
import dev.caerbannog.core.forwarding.fakes.FakeShovelGateway
import dev.caerbannog.core.forwarding.fakes.ShovelCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-07: the safeguards in code. Everything is seen through `FakeShovelGateway.calls`; the fake replaces an existing shovel like the real broker (spike S-F). */
class ForwardingServiceTest {
    private val request = Fwd.request()
    private val dry = ShovelCall.DryRun

    private fun service(gateway: FakeShovelGateway, level: TopologyControlLevel = DECLARE_AND_EDIT, unlocked: Boolean = true) =
        ForwardingService(gateway) { Fwd.context(level, unlocked) }

    // ---- prepare ----

    @Test
    fun `prepare sends one dry run and nothing else, and returns the plan and the confirmation`() = runTest {
        val gateway = Fwd.gateway()
        val ready = assertIs<Prepared.Ready>(service(gateway).prepare(request))
        assertEquals(listOf(dry), gateway.calls)
        assertEquals(120, ready.facts.source.ready)
        assertTrue(ready.plan.canStart)
        assertEquals("orders", ready.confirmation.requiredText)
    }

    @Test
    fun `a locked connection sends no request at all, at Read-only and without the configure permission`() = runTest {
        for ((level, unlocked) in listOf(READ_ONLY to true, DECLARE_AND_EDIT to false, FULL_CONTROL to false)) {
            val gateway = Fwd.gateway()
            val blocked = assertIs<Prepared.Blocked>(service(gateway, level, unlocked).prepare(request))
            assertEquals(listOf(ForwardingBlock.TopologyLocked), blocked.plan.blocks)
            assertEquals(null, blocked.facts, "no request, so no facts")
            assertEquals(emptyList(), gateway.calls, "a request was sent at $level unlocked=$unlocked")
        }
    }

    @Test
    fun `a block found by the dry run comes back with the facts`() = runTest {
        val gateway = Fwd.gateway().apply { noDestinationQueue("/", "orders") }
        val blocked = assertIs<Prepared.Blocked>(service(gateway).prepare(request))
        assertEquals(listOf(ForwardingBlock.DestinationMissing), blocked.plan.blocks)
        assertEquals(120, blocked.facts?.source?.ready)
    }

    @Test
    fun `a dry run that fails is a probe failure and nothing starts`() = runTest {
        val gateway = FakeShovelGateway() // the source is not known: unreachable
        val failed = assertIs<Prepared.ProbeFailed>(service(gateway).prepare(request))
        assertEquals(DryRunOutcome.SourceUnreachable("legacy-eu"), failed.outcome)
        assertTrue(gateway.calls.none { it is ShovelCall.Start })
    }

    // ---- start ----

    @Test
    fun `a typed name that is not exactly the source queue name starts nothing and sends nothing`() = runTest {
        for (typed in listOf("order", "Orders", "orders ", " orders", "", "orders-v2")) {
            val gateway = Fwd.gateway()
            assertEquals(StartResult.NotConfirmed, service(gateway).start(request, typed), "typed \"$typed\"")
            assertEquals(emptyList(), gateway.calls, "typed \"$typed\"")
        }
    }

    @Test
    fun `start repeats the dry run first, then sends exactly one start with the request`() = runTest {
        val gateway = Fwd.gateway()
        assertEquals(StartResult.Started, service(gateway).start(request, "orders"))
        assertEquals(listOf(dry, ShovelCall.Start(request)), gateway.calls)
        assertEquals(AckMode.ON_CONFIRM, (gateway.calls.last() as ShovelCall.Start).request.ackMode)
        assertEquals(ShovelRef("/", "caerbannog-migrate-orders"), (gateway.calls.last() as ShovelCall.Start).request.shovel)
        assertEquals(emptyList(), gateway.replaced)
    }

    @Test
    fun `a shovel that appeared after the dry run is never overwritten`() = runTest {
        val gateway = Fwd.gateway().apply { appearAfterDryRun(Fwd.status(request, ShovelRunState.RUNNING)) }
        val svc = service(gateway)
        assertIs<Prepared.Ready>(svc.prepare(request)) // the first dry run does not see it
        val result = assertIs<StartResult.Blocked>(svc.start(request, "orders"))
        assertTrue(result.plan.blocks.single() is ForwardingBlock.ShovelExists)
        assertTrue(gateway.calls.none { it is ShovelCall.Start }, "a start was sent over an existing shovel: ${gateway.calls}")
        assertEquals(emptyList(), gateway.replaced)
    }

    @Test
    fun `an existing shovel is never overwritten even when the caller never prepared`() = runTest {
        val gateway = Fwd.gateway().apply { existingShovel(Fwd.status(request, ShovelRunState.RUNNING)) }
        assertIs<StartResult.Blocked>(service(gateway).start(request, "orders"))
        assertEquals(emptyList(), gateway.replaced)
        assertTrue(gateway.calls.none { it is ShovelCall.Start })
    }

    @Test
    fun `start while any block exists sends no start`() = runTest {
        val cases = mapOf<String, (FakeShovelGateway) -> Unit>(
            "destination missing" to { it.noDestinationQueue("/", "orders") },
            "plugins off" to { it.plugins(false) },
            "no policymaker tag" to { it.userTags("monitoring") },
            "cannot read status" to { it.userTags("policymaker") },
        )
        for ((name, script) in cases) {
            val gateway = Fwd.gateway().also(script)
            val result = service(gateway).start(request, "orders")
            assertIs<StartResult.Blocked>(result, name)
            assertTrue(gateway.calls.none { it is ShovelCall.Start }, "$name: ${gateway.calls}")
        }
    }

    @Test
    fun `a locked connection sends no request at start either`() = runTest {
        val gateway = Fwd.gateway()
        val result = assertIs<StartResult.Blocked>(service(gateway, READ_ONLY).start(request, "orders"))
        assertEquals(listOf(ForwardingBlock.TopologyLocked), result.plan.blocks)
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `a dry run that fails inside start is a probe failure and nothing starts`() = runTest {
        val gateway = Fwd.gateway().apply { failDryRun(DryRunOutcome.DestinationUnreachable) }
        assertEquals(StartResult.ProbeFailed(DryRunOutcome.DestinationUnreachable), service(gateway).start(request, "orders"))
        assertTrue(gateway.calls.none { it is ShovelCall.Start })
    }

    @Test
    fun `a refused start is reported with its reason and is not retried, never with a plain address`() = runTest {
        val gateway = Fwd.gateway().apply { startOutcome(StartOutcome.Refused("the AMQP TLS endpoint cannot be used")) }
        assertEquals(StartResult.Refused("the AMQP TLS endpoint cannot be used"), service(gateway).start(request, "orders"))
        val starts = gateway.calls.filterIsInstance<ShovelCall.Start>()
        assertEquals(1, starts.size, "a second start was sent")
        assertEquals("amqps", starts.single().request.source.address.scheme)
    }

    @Test
    fun `a start that is not permitted, unreachable or failed is a failure`() = runTest {
        for (outcome in listOf(StartOutcome.NotPermitted, StartOutcome.Unreachable, StartOutcome.Failed("boom"))) {
            val gateway = Fwd.gateway().apply { startOutcome(outcome) }
            assertIs<StartResult.Failed>(service(gateway).start(request, "orders"), "$outcome")
        }
    }

    // ---- poll ----

    @Test
    fun `a poll reads the status once and applies the progress rules`() = runTest {
        val gateway = Fwd.gateway().apply { statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.RUNNING, forwarded = 40, remaining = 80))) }
        val state = service(gateway).poll(request, ForwardingState.Started())
        assertEquals(ForwardingState.Running(40, 80), state)
        assertEquals(listOf(ShovelCall.Status(request.shovel)), gateway.calls)
    }

    @Test
    fun `a terminated shovel is removed once at Declare and edit and reported failed with its reason`() = runTest {
        val gateway = Fwd.gateway().apply {
            statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.TERMINATED, reason = "failed to connect to source")))
        }
        val state = service(gateway, DECLARE_AND_EDIT).poll(request, ForwardingState.Started())
        assertEquals(ForwardingState.Failed("failed to connect to source", cleanedUp = true), state)
        assertEquals(listOf(ShovelCall.Status(request.shovel), ShovelCall.Remove(request.shovel)), gateway.calls)
    }

    @Test
    fun `when the cleanup remove fails the state says it is not cleaned up`() = runTest {
        val gateway = Fwd.gateway().apply {
            statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.TERMINATED, reason = "r")))
            removeOutcome(RemoveOutcome.Failed("boom"))
        }
        assertEquals(ForwardingState.Failed("r", cleanedUp = false), service(gateway).poll(request, ForwardingState.Started()))
    }

    @Test
    fun `a status read that fails keeps the previous state and removes nothing`() = runTest {
        for (read in listOf(StatusOutcome.NotPermitted, StatusOutcome.Unreachable, StatusOutcome.Failed("x"))) {
            val gateway = Fwd.gateway().apply { statusScript(read) }
            val before = ForwardingState.Running(5, 10)
            assertEquals(before, service(gateway).poll(request, before))
            assertTrue(gateway.calls.none { it is ShovelCall.Remove })
        }
    }

    @Test
    fun `the first absent poll after start is not finished, the second is, and a finished shovel is never removed`() = runTest {
        val gateway = Fwd.gateway().apply { statusScript(StatusOutcome.Absent, StatusOutcome.Absent) }
        val svc = service(gateway)
        val first = svc.poll(request, ForwardingState.Started())
        assertEquals(ForwardingState.Started(1), first)
        assertEquals(ForwardingState.Finished, svc.poll(request, first))
        assertTrue(gateway.calls.none { it is ShovelCall.Remove })
    }

    // ---- step 2b, ruling 5 ----

    @Test
    fun `a poll after Finished or Failed returns the state and sends no request`() = runTest {
        for (final in listOf(ForwardingState.Finished, ForwardingState.Failed("r", true))) {
            val gateway = Fwd.gateway()
            assertEquals(final, service(gateway).poll(request, final))
            assertEquals(emptyList(), gateway.calls, "a request after $final")
        }
    }

    @Test
    fun `the cleanup uses the gate of creating - a locked connection sends no remove and the state says not cleaned up`() = runTest {
        for ((level, unlocked) in listOf(READ_ONLY to true, DECLARE_AND_EDIT to false)) {
            val gateway = Fwd.gateway().apply { statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.TERMINATED, reason = "boom"))) }
            val state = service(gateway, level, unlocked).poll(request, ForwardingState.Started())
            assertEquals(ForwardingState.Failed("boom", cleanedUp = false), state, "level $level unlocked=$unlocked")
            assertTrue(gateway.calls.none { it is ShovelCall.Remove }, "a remove was sent on a locked connection")
        }
    }

    @Test
    fun `a cleanup remove that is anything but Removed gives not cleaned up`() = runTest {
        val outcomes = listOf(RemoveOutcome.NotFound, RemoveOutcome.NotPermitted, RemoveOutcome.Unreachable, RemoveOutcome.Failed("x"))
        for (outcome in outcomes) {
            val gateway = Fwd.gateway().apply {
                statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.TERMINATED, reason = "boom")))
                removeOutcome(outcome)
            }
            assertEquals(ForwardingState.Failed("boom", cleanedUp = false), service(gateway).poll(request, ForwardingState.Started()), "$outcome")
        }
    }

    @Test
    fun `a terminated shovel without a reason is reported with the fixed text`() = runTest {
        val gateway = Fwd.gateway().apply { statusScript(StatusOutcome.Listed(Fwd.status(request, ShovelRunState.TERMINATED, reason = null))) }
        assertEquals("The shovel stopped without giving a reason", ForwardingTexts.NO_REASON)
        assertEquals(ForwardingState.Failed(ForwardingTexts.NO_REASON, cleanedUp = true), service(gateway).poll(request, ForwardingState.Started()))
    }

    // ---- remove ----

    @Test
    fun `the user's remove needs Full control, below it no request is sent`() = runTest {
        val gateway = Fwd.gateway()
        assertEquals(RemoveResult.NeedsFullControl, service(gateway, DECLARE_AND_EDIT).remove(request.shovel))
        assertEquals(RemoveResult.NeedsFullControl, service(gateway, READ_ONLY).remove(request.shovel))
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `at Full control the remove deletes the shovel only, and no other call kind exists`() = runTest {
        val gateway = Fwd.gateway().apply { existingShovel(Fwd.status(request)) }
        assertEquals(RemoveResult.Removed, service(gateway, FULL_CONTROL).remove(request.shovel))
        assertEquals(listOf(ShovelCall.Remove(request.shovel)), gateway.calls)
        assertTrue(request.shovel !in gateway.shovels)
    }

    @Test
    fun `Full control without the configure permission does not remove`() = runTest {
        val gateway = Fwd.gateway()
        assertEquals(RemoveResult.NeedsFullControl, service(gateway, FULL_CONTROL, unlocked = false).remove(request.shovel))
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `a remove the broker fails is a failure with no secret`() = runTest {
        val gateway = Fwd.gateway().apply { removeOutcome(RemoveOutcome.Failed("boom")) }
        assertIs<RemoveResult.Failed>(service(gateway, FULL_CONTROL).remove(request.shovel))
    }

    // ---- the whole run, and the sentinel ----

    @Test
    fun `the typed source password appears in no result, state, call or confirmation`() = runTest {
        val typed = Fwd.request(credentials = Fwd.creds())
        val gateway = Fwd.gateway().apply { statusScript(StatusOutcome.Listed(Fwd.status(typed, ShovelRunState.TERMINATED, reason = "failed to connect"))) }
        val svc = service(gateway)
        val prepared = svc.prepare(typed)
        val started = svc.start(typed, "orders")
        val polled = svc.poll(typed, ForwardingState.Started())
        val seen = listOf(prepared, started, polled, gateway.calls, typed, typed.source, typed.source.address, typed.source.credentials, (prepared as? Prepared.Ready)?.confirmation, (prepared as? Prepared.Ready)?.confirmation?.lines)
        assertTrue(prepared is Prepared.Ready, "the premise: the run was prepared, got $prepared")
        seen.forEach { assertTrue(!it.toString().contains(Fwd.SENTINEL), "the password is in: $it") }
    }
}
