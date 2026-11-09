package dev.caerbannog.core.connections

import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** M5-08a R1 (F3): a connection without an executor stays without one through the guard, and the fixed reason is passed on to the screens as it is. */
class StreamSourceMigrationAccessTest {

    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"
    private val unknown = "The broker version could not be read, so migrations are not offered"

    private object Noop : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Success
    }

    @Test
    fun `a null executor stays null and the reason reaches the source`() = runBlocking<Unit> {
        for (reason in listOf(threeX, unknown)) {
            val h = Harness()
            h.gateway.setMigrationUnavailable("uat-eu", reason)
            h.active.connect(h.uat)
            val source = assertNotNull(h.active.source)
            assertNull(source.migrationExecutor, "no stand-in executor may appear for [$reason]")
            assertEquals(reason, source.migrationUnavailableReason)
        }
    }

    @Test
    fun `a connection with an executor has no reason`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.setMigrationExecutor("uat-eu", Noop)
        h.active.connect(h.uat)
        val source = assertNotNull(h.active.source)
        assertNotNull(source.migrationExecutor)
        assertNull(source.migrationUnavailableReason)
    }

    @Test
    fun `the reason is a value of the source, not of the connection that is current later`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.setMigrationUnavailable("uat-eu", threeX)
        h.gateway.setMigrationExecutor("prod-eu", Noop)
        h.active.connect(h.uat)
        val old = assertNotNull(h.active.source)
        h.active.connect(h.prod)
        assertEquals(threeX, old.migrationUnavailableReason)
        assertNull(old.migrationExecutor)
        assertNull(h.active.source?.migrationUnavailableReason)
    }

    @Test
    fun `without anything set the held connection has neither executor nor reason`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        assertNull(h.active.source?.migrationExecutor)
        assertNull(h.active.source?.migrationUnavailableReason)
    }
}
