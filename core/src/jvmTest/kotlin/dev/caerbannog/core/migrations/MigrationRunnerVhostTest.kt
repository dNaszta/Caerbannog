package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M5-08a scope 5 (spec V1): the runner blocks every action whose virtual host is not the connection's, in the dry run AND in apply, before
 * anything is sent; the level and permission checks are unchanged; a failing action is reported with its position (`index` is 1-based).
 * A vhost name is echoed only when short and plain (as the M8-04 echo rule); otherwise the wording stays the same without the name.
 */
class MigrationRunnerVhostTest {

    private class Recording(var failAt: Int? = null, val reason: String = "the broker did not answer") : TopologyMigrationExecutor {
        val sent = mutableListOf<MigrationAction>()
        override suspend fun execute(action: MigrationAction): ActionResult {
            sent += action
            return if (failAt == sent.size) ActionResult.Failed(reason) else ActionResult.Success
        }
    }

    private val executor = Recording()
    private val history = InMemoryMigrationHistoryRepository()
    private val runner = MigrationRunner(history, executor)

    private fun migration(version: String, vararg actions: MigrationAction): MigrationFile {
        val raw = MigrationParser.exportToJson(version, "vhost test", actions.toList())
        return MigrationFile(version, "vhost test", actions.toList(), raw, MigrationParser.computeChecksum(raw))
    }

    private fun queue(name: String, vhost: String) = MigrationAction.DeclareQueue(name, "quorum", vhost = vhost)

    // ---- dry run -------------------------------------------------------------------------------------------------------

    @Test
    fun `dry run blocks every action of another virtual host and cannot apply`() {
        val dry = runner.dryRun(migration("0001", queue("a", "/orders"), queue("b", "/orders")), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertFalse(dry.canApply)
        assertTrue(dry.blockedReason?.contains("virtual host") == true, "blockedReason: ${dry.blockedReason}")
        for (row in dry.actions) {
            assertFalse(row.allowed, row.action.description)
            assertTrue(row.reason?.contains("virtual host") == true, "reason: ${row.reason}")
        }
    }

    @Test
    fun `dry run names both virtual hosts when they are short and plain`() {
        val dry = runner.dryRun(migration("0001", queue("a", "orders")), true, TopologyControlLevel.FULL_CONTROL, vhost = "billing")
        assertEquals("The action belongs to virtual host orders; this connection is for billing", dry.actions.single().reason)
    }

    @Test
    fun `the wider echo set is named, dash and dot and the vhost slash`() {
        val toSlash = runner.dryRun(migration("0001", queue("a", "order-events")), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertEquals("The action belongs to virtual host order-events; this connection is for /", toSlash.actions.single().reason)
        val fromSlash = runner.dryRun(migration("0001", queue("a", "/")), true, TopologyControlLevel.FULL_CONTROL, vhost = "orders.v2")
        assertEquals("The action belongs to virtual host /; this connection is for orders.v2", fromSlash.actions.single().reason)
    }

    @Test
    fun `dry run allows the actions of the connection's own virtual host`() {
        val dry = runner.dryRun(migration("0001", queue("a", "/orders"), queue("b", "/orders")), true, TopologyControlLevel.FULL_CONTROL, vhost = "/orders")
        assertTrue(dry.canApply, "blocked: ${dry.blockedReason}")
        assertTrue(dry.actions.all { it.allowed })
    }

    @Test
    fun `dry run blocks only the action of the other virtual host in a mixed file`() {
        val dry = runner.dryRun(migration("0001", queue("a", "/"), queue("b", "/orders")), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertFalse(dry.canApply)
        assertEquals(listOf(true, false), dry.actions.map { it.allowed })
    }

    @Test
    fun `an unusual virtual host name is never echoed, the wording keeps naming virtual hosts`() {
        val hostile = listOf("a\"quoted", "line\nbreak", "<script>alert(1)</script>", "x".repeat(65), "tab\there", "back\\slash", "a b", "/orders", "..")
        for (name in hostile) {
            val dry = runner.dryRun(migration("0001", queue("a", name)), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
            val reason = dry.actions.single().reason ?: error("not blocked for ${name.take(20)}")
            assertTrue(reason.contains("virtual host"), reason)
            assertFalse(reason.contains(name), "the reason echoes the hostile name: $reason")
            assertFalse(dry.blockedReason.orEmpty().contains(name))
        }
        val hostileConnection = runner.dryRun(migration("0001", queue("a", "/")), true, TopologyControlLevel.FULL_CONTROL, vhost = "evil\"<b>")
        assertFalse(hostileConnection.actions.single().reason.orEmpty().contains("evil"), hostileConnection.actions.single().reason)
    }

    // ---- apply ---------------------------------------------------------------------------------------------------------

    @Test
    fun `apply is blocked for another virtual host, nothing is sent and nothing is recorded`() = runBlocking {
        val result = runner.applyPending("uat-eu", listOf(migration("0001", queue("a", "/orders"))), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        val blocked = assertIs<MigrationApplyResult.Blocked>(result)
        assertTrue(blocked.reason.contains("virtual host"), blocked.reason)
        assertTrue(executor.sent.isEmpty(), "sent: ${executor.sent}")
        assertTrue(history.getAppliedMigrations("uat-eu").isEmpty())
    }

    @Test
    fun `a file with one action of another virtual host sends none of its actions`() = runBlocking {
        val result = runner.applyPending("uat-eu", listOf(migration("0001", queue("a", "/"), queue("b", "/orders"))), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertIs<MigrationApplyResult.Blocked>(result)
        assertTrue(executor.sent.isEmpty(), "the allowed first action was sent before the second was found blocked: ${executor.sent}")
    }

    @Test
    fun `apply on the connection's own virtual host sends every action and records the migration`() = runBlocking {
        val file = migration("0001", queue("a", "/orders"), queue("b", "/orders"))
        val result = runner.applyPending("uat-eu", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/orders")
        assertEquals(MigrationApplyResult.Success(listOf("0001")), result)
        assertEquals(file.up, executor.sent)
        assertEquals(mapOf("0001" to file.checksum), history.getAppliedMigrations("uat-eu"))
    }

    // ---- level and permission checks stay -----------------------------------------------------------------------------

    @Test
    fun `no configure permission still wins, in the dry run and in apply`() = runBlocking {
        val file = migration("0001", queue("a", "/orders"))
        val dry = runner.dryRun(file, false, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertEquals("The user has no configure permission on this connection", dry.blockedReason)
        assertEquals(MigrationApplyResult.Blocked("The user has no configure permission on this connection"), runner.applyPending("uat-eu", listOf(file), false, TopologyControlLevel.FULL_CONTROL, vhost = "/"))
    }

    @Test
    fun `read-only level still blocks, on the right virtual host too`() = runBlocking {
        val file = migration("0001", queue("a", "/"))
        assertEquals("Topology control level is Read-only", runner.dryRun(file, true, TopologyControlLevel.READ_ONLY, vhost = "/").blockedReason)
        assertEquals(MigrationApplyResult.Blocked("Topology control level is Read-only"), runner.applyPending("uat-eu", listOf(file), true, TopologyControlLevel.READ_ONLY, vhost = "/"))
        assertTrue(executor.sent.isEmpty())
    }

    @Test
    fun `a delete still needs Full control on the right virtual host`() = runBlocking {
        val file = migration("0001", MigrationAction.DeleteQueue("old", "/"))
        val dry = runner.dryRun(file, true, TopologyControlLevel.DECLARE_AND_EDIT, vhost = "/")
        assertFalse(dry.canApply)
        assertEquals("Destructive actions require Full control level", dry.actions.single().reason)
        assertIs<MigrationApplyResult.Blocked>(runner.applyPending("uat-eu", listOf(file), true, TopologyControlLevel.DECLARE_AND_EDIT, vhost = "/"))
        assertTrue(executor.sent.isEmpty())
    }

    @Test
    fun `a delete on another virtual host is blocked even with Full control`() = runBlocking {
        val file = migration("0001", MigrationAction.DeleteQueue("old", "/orders"))
        assertFalse(runner.dryRun(file, true, TopologyControlLevel.FULL_CONTROL, vhost = "/").canApply)
        assertIs<MigrationApplyResult.Blocked>(runner.applyPending("uat-eu", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/"))
        assertTrue(executor.sent.isEmpty())
    }

    // ---- ActionFailed carries the position ------------------------------------------------------------------------------

    @Test
    fun `a failing second action of three is reported with index 2 of 3 and stops the run`() = runBlocking {
        executor.failAt = 2
        val file = migration("0003", queue("a", "/"), queue("b", "/"), queue("c", "/"))
        val failed = assertIs<MigrationApplyResult.ActionFailed>(runner.applyPending("uat-eu", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/"))
        assertEquals("0003", failed.version)
        assertEquals(file.up[1], failed.action)
        assertEquals(2, failed.index)
        assertEquals(3, failed.total)
        assertEquals("the broker did not answer", failed.reason)
        assertEquals(file.up.take(2), executor.sent, "the third action must never be sent")
        assertTrue(history.getAppliedMigrations("uat-eu").isEmpty(), "a failed migration is not recorded")
    }

    @Test
    fun `the first and the last action are numbered 1 and 3 of 3`() = runBlocking {
        for (position in listOf(1, 3)) {
            val rec = Recording(failAt = position)
            val file = migration("0003", queue("a", "/"), queue("b", "/"), queue("c", "/"))
            val failed = assertIs<MigrationApplyResult.ActionFailed>(MigrationRunner(InMemoryMigrationHistoryRepository(), rec).applyPending("p", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/"))
            assertEquals(position to 3, failed.index to failed.total)
        }
    }

    @Test
    fun `an earlier migration stays recorded when a later one fails, and the failed one counts from 1`() = runBlocking {
        executor.failAt = 3 // the first action of the second migration
        val first = migration("0001", queue("a", "/"), queue("b", "/"))
        val second = migration("0002", queue("c", "/"), queue("d", "/"))
        val failed = assertIs<MigrationApplyResult.ActionFailed>(runner.applyPending("uat-eu", listOf(second, first), true, TopologyControlLevel.FULL_CONTROL, vhost = "/"))
        assertEquals(Triple("0002", 1, 2), Triple(failed.version, failed.index, failed.total))
        assertEquals(setOf("0001"), history.getAppliedMigrations("uat-eu").keys)
    }
}
