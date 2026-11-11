package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
import dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
import dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.SecretValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-12a: the runner decides per action. A broker-level action needs the administrator tag and Full control; the configure permission and the V1 vhost do not apply to it. */
class AccessRunnerRulesTest {
    private val admin = setOf("administrator")
    private val needsTag = "Creating virtual hosts, users and permissions needs the administrator tag"
    private val needsFull = "Creating virtual hosts, users and permissions needs the level Full control"
    private val vhostAction = MigrationAction.DeclareVhost("/orders")
    private val userAction = MigrationAction.DeclareUser("svc", SecretValue.of("h"), "rabbit_password_hashing_sha256", listOf("management"))
    private val perm = MigrationAction.SetPermissions("/orders", "svc")
    private val topic = MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic")
    private val queue = MigrationAction.DeclareQueue("orders", vhost = "/")
    private val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor())

    private fun file(vararg a: MigrationAction, version: String = "0001") = MigrationFile(version, "access", a.toList(), "{}", "c-$version")
    private fun dry(f: MigrationFile, configure: Boolean = true, level: dev.caerbannog.core.connections.TopologyControlLevel = FULL_CONTROL, tags: Set<String> = admin, vhost: String = "/") =
        runner.dryRun(f, configure, level, vhost, tags)

    @Test
    fun `an administrator at Full control may apply every access action, whatever the virtual host of the connection`() {
        val r = dry(file(vastCheck(), userAction, perm, topic), vhost = "/")
        assertTrue(r.canApply, "blocked: ${r.actions.map { it.reason }}")
        assertTrue(r.actions.all { it.allowed })
        assertEquals(null, r.blockedReason)
        assertTrue(dry(file(perm), vhost = "uat").canApply, "the V1 vhost rule must not apply to a broker-level action")
    }

    private fun vastCheck() = vhostAction

    @Test
    fun `without the administrator tag every access action is blocked with the tag reason`() {
        val r = dry(file(vhostAction, userAction, perm, topic), tags = setOf("management", "policymaker"))
        assertFalse(r.canApply)
        assertTrue(r.actions.all { !it.allowed && it.reason == needsTag }, r.actions.map { it.reason }.toString())
        assertEquals(needsTag, r.blockedReason)
        assertEquals(needsTag, dry(file(userAction), tags = emptySet()).blockedReason)
    }

    @Test
    fun `below Full control every access action is blocked with the level reason`() {
        val r = dry(file(vhostAction, userAction), level = DECLARE_AND_EDIT)
        assertFalse(r.canApply)
        assertTrue(r.actions.all { it.reason == needsFull }, r.actions.map { it.reason }.toString())
        assertEquals(needsFull, r.blockedReason)
    }

    @Test
    fun `with neither the tag nor the level the tag is named first`() {
        assertEquals(needsTag, dry(file(userAction), level = DECLARE_AND_EDIT, tags = emptySet()).blockedReason)
    }

    @Test
    fun `Read-only blocks everything as before, an administrator or not`() {
        val r = dry(file(vhostAction, queue), level = READ_ONLY)
        assertFalse(r.canApply)
        assertTrue(r.actions.none { it.allowed })
        assertEquals("Topology control level is Read-only", r.blockedReason)
    }

    @Test
    fun `a mixed file is checked action by action - the configure permission blocks the topology action only`() {
        val r = dry(file(userAction, queue), configure = false)
        val byName = r.actions.associate { it.action.actionName to it }
        assertTrue(byName.getValue("declareUser").allowed, "the configure permission must not apply to a broker-level action")
        assertFalse(byName.getValue("declareQueue").allowed)
        assertEquals("User has no configure permission on this virtual host", byName.getValue("declareQueue").reason)
        assertFalse(r.canApply)
    }

    @Test
    fun `a mixed file without the tag keeps the topology rules for the topology action`() {
        val r = dry(file(userAction, queue), tags = emptySet())
        val byName = r.actions.associate { it.action.actionName to it }
        assertFalse(byName.getValue("declareUser").allowed)
        assertTrue(byName.getValue("declareQueue").allowed, "a topology action does not need the administrator tag")
    }

    @Test
    fun `a topology action still obeys the V1 vhost rule`() {
        val r = dry(file(MigrationAction.DeclareQueue("q", vhost = "other")), vhost = "/")
        assertFalse(r.canApply)
    }

    // ---- apply ----

    @Test
    fun `applyPending of an access file works for an administrator without the configure permission, where the early return used to block`() = runTest {
        val executor = FakeTopologyMigrationExecutor()
        val r = MigrationRunner(InMemoryMigrationHistoryRepository(), executor)
        val result = r.applyPending("new-eu", listOf(file(vhostAction, userAction, perm)), hasConfigurePermission = false, controlLevel = FULL_CONTROL, vhost = "/", userTags = admin)
        assertIs<MigrationApplyResult.Success>(result, "blocked: $result")
        assertEquals(3, executor.executedActions.size)
    }

    @Test
    fun `applyPending is Blocked without the tag, below Full control and at Read-only, and sends nothing`() = runTest {
        for ((level, tags, reason) in listOf(
            Triple(FULL_CONTROL, emptySet<String>(), needsTag), Triple(DECLARE_AND_EDIT, admin, needsFull), Triple(READ_ONLY, admin, "Topology control level is Read-only"),
        )) {
            val executor = FakeTopologyMigrationExecutor()
            val result = MigrationRunner(InMemoryMigrationHistoryRepository(), executor)
                .applyPending("new-eu", listOf(file(userAction)), true, level, "/", tags)
            assertEquals(MigrationApplyResult.Blocked(reason), result, "level $level tags $tags")
            assertEquals(emptyList(), executor.executedActions)
        }
    }
}
