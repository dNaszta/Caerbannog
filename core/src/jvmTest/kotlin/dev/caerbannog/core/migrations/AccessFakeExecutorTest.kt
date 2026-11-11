package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.SecretValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-12a: the fake executor is an honest 4.x broker for the access items: identical = no-op, different = Conflict, nothing is overwritten, no hash in a reason. */
class AccessFakeExecutorTest {
    private val sha = "rabbit_password_hashing_sha256"
    private val executor = FakeTopologyMigrationExecutor()
    private fun user(hash: String = "H1", tags: List<String> = listOf("management")) = MigrationAction.DeclareUser("svc", SecretValue.of(hash), sha, tags)

    private fun conflict(result: ActionResult): String {
        val failed = assertIs<ActionResult.Failed>(result, "expected a Conflict but got $result")
        assertEquals(FailureKind.Conflict, failed.kind)
        return failed.reason
    }

    // ---- users ----

    @Test
    fun `a missing user is created, the same user again is a no-op`() = runTest {
        assertEquals(ActionResult.Success, executor.execute(user()))
        assertTrue(executor.users.getValue("svc").hash.matches(SecretValue.of("H1")), "the user was not created")
        assertEquals(ActionResult.Success, executor.execute(user()))
        assertEquals(1, executor.users.size)
    }

    @Test
    fun `a different hash is a conflict that names the password hash and shows no hash, and nothing is overwritten`() = runTest {
        executor.existingUser("svc", "OLD-HASH", sha, listOf("management"))
        val reason = conflict(executor.execute(user("NEW-HASH")))
        assertTrue(reason.contains("svc") && reason.contains("password hash"), reason)
        assertFalse(reason.contains("OLD-HASH") || reason.contains("NEW-HASH"), reason)
        assertTrue(executor.users.getValue("svc").hash.matches(SecretValue.of("OLD-HASH")), "the existing hash was overwritten")
    }

    @Test
    fun `different tags are a conflict that names the tags, the same tags in another order are not`() = runTest {
        executor.existingUser("svc", "H1", sha, listOf("management", "monitoring"))
        assertEquals(ActionResult.Success, executor.execute(user(tags = listOf("monitoring", "management"))))
        val reason = conflict(executor.execute(user(tags = listOf("administrator"))))
        assertTrue(reason.contains("tags"), reason)
    }

    // ---- permissions ----

    @Test
    fun `permissions are created, identical ones are a no-op, other patterns are a conflict that names the patterns`() = runTest {
        val p = MigrationAction.SetPermissions("/orders", "svc", "^a", "^b", "^c")
        assertEquals(ActionResult.Success, executor.execute(p))
        assertEquals(p, executor.permissions["/orders" to "svc"], "the permissions were not stored")
        assertEquals(ActionResult.Success, executor.execute(p))
        val reason = conflict(executor.execute(p.copy(read = "^other")))
        assertTrue(reason.contains("patterns"), reason)
        assertEquals(p, executor.permissions["/orders" to "svc"], "the permissions were overwritten")
    }

    @Test
    fun `existing permissions with other patterns are a conflict`() = runTest {
        executor.existingPermissions("/orders", "svc", "^x", "^x", "^x")
        conflict(executor.execute(MigrationAction.SetPermissions("/orders", "svc", ".*", ".*", ".*")))
    }

    @Test
    fun `topic permissions follow the same rule`() = runTest {
        val t = MigrationAction.SetTopicPermissions("/orders", "svc", "amq.topic", "^w", "^r")
        assertEquals(ActionResult.Success, executor.execute(t))
        assertEquals(ActionResult.Success, executor.execute(t))
        assertTrue(conflict(executor.execute(t.copy(write = "^other"))).contains("patterns"))
    }

    // ---- virtual hosts (D5 = B) ----

    @Test
    fun `a missing vhost is created with the carried settings, an identical one is a no-op`() = runTest {
        val v = MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "quorum")
        assertEquals(ActionResult.Success, executor.execute(v))
        assertEquals("Orders", executor.vhosts["/orders"]?.description, "the vhost was not created")
        assertEquals(ActionResult.Success, executor.execute(v))
    }

    @Test
    fun `a carried setting that differs is a conflict naming the setting, values never echoed, and the vhost is unchanged`() = runTest {
        executor.existingVhost("/orders", description = "Something else", tags = listOf("prod"), defaultQueueType = "classic")
        assertEquals("The virtual host /orders exists with a different description", conflict(executor.execute(MigrationAction.DeclareVhost("/orders", "Orders", listOf("prod"), "classic"))))
        assertEquals("The virtual host /orders exists with different tags", conflict(executor.execute(MigrationAction.DeclareVhost("/orders", "Something else", listOf("dev"), "classic"))))
        assertEquals("The virtual host /orders exists with a different default queue type", conflict(executor.execute(MigrationAction.DeclareVhost("/orders", "Something else", listOf("prod"), "quorum"))))
        assertEquals("Something else", executor.vhosts["/orders"]?.description)
    }

    @Test
    fun `tags are compared as a set`() = runTest {
        executor.existingVhost("/orders", tags = listOf("a", "b"))
        assertEquals(ActionResult.Success, executor.execute(MigrationAction.DeclareVhost("/orders", tags = listOf("b", "a"))))
    }

    @Test
    fun `settings the action does not carry are not compared`() = runTest {
        executor.existingVhost("/orders", description = "Orders", tags = listOf("prod"), defaultQueueType = "quorum")
        assertEquals(ActionResult.Success, executor.execute(MigrationAction.DeclareVhost("/orders")))
        assertEquals("Orders", executor.vhosts["/orders"]?.description)
    }

    @Test
    fun `the default virtual host is never created or compared`() = runTest {
        val before = executor.vhosts["/"]?.description
        assertEquals(ActionResult.Success, executor.execute(MigrationAction.DeclareVhost("/", "Production", listOf("prod"), "quorum")))
        assertEquals(before, executor.vhosts["/"]?.description, "the default virtual host was changed")
        assertEquals(1, executor.vhosts.keys.count { it == "/" })
    }
}
