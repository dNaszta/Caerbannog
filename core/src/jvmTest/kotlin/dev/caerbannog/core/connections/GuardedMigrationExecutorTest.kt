package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * M5-08a scope 4: `HeldConnection.migrationExecutor` (default null) is exposed through `StreamSource` behind a guard that refuses once the
 * connection is no longer the current one (the pattern of GuardedStreamManager). Nothing a screen holds may reach a closed connection.
 */
class GuardedMigrationExecutorTest {

    private class Counting : TopologyMigrationExecutor {
        val calls = mutableListOf<MigrationAction>()
        override suspend fun execute(action: MigrationAction): ActionResult { calls += action; return ActionResult.Success }
    }

    private val action = MigrationAction.DeclareQueue("orders")

    @Test
    fun `while the connection is current the call reaches the executor`() = runBlocking {
        val inner = Counting()
        assertEquals<ActionResult>(ActionResult.Success, GuardedMigrationExecutor(inner) { true }.execute(action))
        assertEquals(listOf<MigrationAction>(action), inner.calls)
    }

    @Test
    fun `once the connection is not current the call is refused with a fixed reason and the executor is never called`() = runBlocking {
        val inner = Counting()
        var current = true
        val guarded = GuardedMigrationExecutor(inner) { current }
        current = false
        assertEquals<ActionResult>(ActionResult.Failed("Connection closed"), guarded.execute(action))
        assertTrue(inner.calls.isEmpty(), "the closed connection's executor was called")
    }

    @Test
    fun `a held connection has no migration executor unless it brings one`() {
        val held = FakeConnectionGateway().let { gw -> runBlocking { (gw.open(ConnectionProfile("uat-eu", "h", "/", "svc")) as OpenResult.Opened).connection } }
        assertNull(held.migrationExecutor)
    }

    @Test
    fun `the source of a connection with an executor exposes it, and refuses after disconnect or a switch`() = runBlocking {
        val h = Harness()
        val executor = Counting()
        h.gateway.setMigrationExecutor("uat-eu", executor)
        h.active.connect(h.uat)
        val source = assertNotNull(h.active.source)
        val exposed = assertNotNull(source.migrationExecutor, "the source must carry the held connection's executor")
        assertEquals<ActionResult>(ActionResult.Success, exposed.execute(action))
        assertEquals(1, executor.calls.size)

        h.active.disconnect()
        assertEquals<ActionResult>(ActionResult.Failed("Connection closed"), exposed.execute(action))
        assertEquals(1, executor.calls.size, "a disconnected source reached the executor")
    }

    @Test
    fun `a source superseded by another connection refuses`() = runBlocking {
        val h = Harness()
        val first = Counting()
        h.gateway.setMigrationExecutor("uat-eu", first)
        h.active.connect(h.uat)
        val old = assertNotNull(h.active.source?.migrationExecutor)
        h.active.connect(h.prod)
        assertEquals<ActionResult>(ActionResult.Failed("Connection closed"), old.execute(action))
        assertTrue(first.calls.isEmpty())
        assertNull(h.active.source?.migrationExecutor, "prod has no executor: a connection without one stays null")
    }

    @Test
    fun `a connection without an executor gives a source without one, never a refusing stand-in that looks usable`() = runBlocking {
        val h = Harness()
        h.active.connect(h.uat)
        assertNull(h.active.source?.migrationExecutor)
        assertSame(null, h.active.source?.migrationExecutor)
    }
}
