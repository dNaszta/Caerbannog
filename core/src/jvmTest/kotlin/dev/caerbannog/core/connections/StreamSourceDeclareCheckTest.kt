package dev.caerbannog.core.connections

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeDeclareCheck
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-05: `HeldConnection.declareCheck` (default null) is exposed through `StreamSource.declareCheck`, null exactly when `migrationExecutor` is null
 * (a 3.x source, an unreadable version), and wrapped by the same "still current" guard as `GuardedMigrationExecutor`: once the connection is no
 * longer the current one nothing reaches the broker.
 */
class StreamSourceDeclareCheckTest {

    private val orders = MigrationAction.DeclareQueue("orders")

    private fun Harness.connectWithCheck(check: FakeDeclareCheck) {
        gateway.setMigrationExecutor("uat-eu", dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor())
        gateway.setDeclareCheck("uat-eu", check)
        runBlocking { active.connect(uat) }
    }

    @Test
    fun `a held connection has no check unless it brings one`() {
        val h = Harness()
        runBlocking { h.active.connect(h.uat) }
        assertNull(h.active.source?.declareCheck)
        assertNull(h.active.source?.migrationExecutor)
    }

    @Test
    fun `the source of a connection with a check exposes it while current, and refuses after disconnect with a fixed reason`() = runBlocking {
        val h = Harness()
        val check = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        h.connectWithCheck(check)
        val exposed = assertNotNull(h.active.source?.declareCheck, "the source must carry the held connection's check")
        assertEquals<BrokerFinding>(BrokerFinding.Same, exposed.find(orders))
        assertEquals(1, check.calls.size)

        h.active.disconnect()
        assertEquals<BrokerFinding>(BrokerFinding.Unreadable("Connection closed"), exposed.find(orders))
        assertEquals(1, check.calls.size, "a disconnected source reached the check")
    }

    @Test
    fun `a source superseded by another connection refuses, and the new one has none`() = runBlocking {
        val h = Harness()
        val first = FakeDeclareCheck().answerNamed("orders", BrokerFinding.Same)
        h.connectWithCheck(first)
        val old = assertNotNull(h.active.source?.declareCheck)
        h.active.connect(h.prod)
        assertEquals<BrokerFinding>(BrokerFinding.Unreadable("Connection closed"), old.find(orders))
        assertTrue(first.calls.isEmpty())
        assertNull(h.active.source?.declareCheck, "prod brought no check: a connection without one stays null")
    }

    @Test
    fun `a 3x connection or one whose version cannot be read has no check, whatever was scripted`() = runBlocking {
        for (mode in listOf("3x", "unreadable")) {
            val h = Harness()
            h.gateway.setMigrationExecutor("uat-eu", dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor())
            h.gateway.setDeclareCheck("uat-eu", FakeDeclareCheck())
            if (mode == "3x") h.gateway.brokerRuns("uat-eu", "3.13.7") else h.gateway.versionCannotBeRead("uat-eu")
            h.active.connect(h.uat)
            assertNull(h.active.source?.migrationExecutor, mode)
            assertNull(h.active.source?.declareCheck, "$mode: the check is null exactly when the executor is null")
        }
    }
}
