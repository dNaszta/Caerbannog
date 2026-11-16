package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.TopologyControlLevel.DECLARE_AND_EDIT
import dev.caerbannog.core.connections.TopologyControlLevel.FULL_CONTROL
import dev.caerbannog.core.connections.TopologyControlLevel.READ_ONLY
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-05, the pure extraction: the rules inside `MigrationRunner.dryRun` and `dryRunPerAction` become `MigrationDryRunRules.evaluate(actions,
 * hasConfigurePermission, controlLevel, vhost, userTags)`, so the planner can use them without a runner, a history or an executor. The result
 * is read through `actions` (each with `action`, `allowed`, `reason`), `canApply` and `blockedReason` only.
 *
 * The two paths have DIFFERENT orders of precedence and both must survive, which is why they are pinned here by text and, in the matrix, by
 * equality with what the runner answers for the same file:
 *  - a topology-only file: no configure permission beats Read-only, then Read-only, then the virtual host, then destructive;
 *  - a file with a broker-level action: Read-only first, then administrator and Full control for the broker-level action, then configure,
 *    then the virtual host, then destructive.
 */
class MigrationDryRunRulesTest {

    private val declare = MigrationAction.DeclareQueue("orders")
    private val delete = MigrationAction.DeleteQueue("old")
    private val elsewhere = MigrationAction.DeclareQueue("billing", vhost = "orders")
    private val access = MigrationAction.DeclareVhost("team")

    private fun evaluate(
        actions: List<MigrationAction>,
        configure: Boolean = true,
        level: TopologyControlLevel = FULL_CONTROL,
        vhost: String = "/",
        tags: Set<String> = emptySet(),
    ) = MigrationDryRunRules.evaluate(actions, configure, level, vhost, tags)

    private fun reasons(actions: List<MigrationAction>, configure: Boolean = true, level: TopologyControlLevel = FULL_CONTROL, tags: Set<String> = emptySet()) =
        evaluate(actions, configure, level, tags = tags).actions.map { it.reason }

    // ---- topology-only file: no configure beats Read-only ----

    @Test
    fun `a topology-only file with no configure permission and Read-only says no configure, for every action`() {
        val result = evaluate(listOf(declare, delete, elsewhere), configure = false, level = READ_ONLY)
        assertEquals(List(3) { "User has no configure permission on this virtual host" }, result.actions.map { it.reason })
        assertEquals("The user has no configure permission on this connection", result.blockedReason)
        assertFalse(result.canApply)
    }

    @Test
    fun `a topology-only file with Read-only and configure says Read-only, before the virtual host and before destructive`() {
        val result = evaluate(listOf(declare, delete, elsewhere), level = READ_ONLY)
        assertEquals(List(3) { "Topology control level is Read-only" }, result.actions.map { it.reason })
        assertEquals("Topology control level is Read-only", result.blockedReason)
        assertFalse(result.canApply)
    }

    @Test
    fun `a topology-only file checks the virtual host before destructive, and keeps the others allowed`() {
        val other = MigrationAction.DeleteQueue("x", vhost = "orders")
        val result = evaluate(listOf(declare, other, delete), level = DECLARE_AND_EDIT)
        assertEquals(
            listOf(null, "The action belongs to virtual host orders; this connection is for /", "Destructive actions require Full control level"),
            result.actions.map { it.reason },
        )
        assertEquals(listOf(true, false, false), result.actions.map { it.allowed })
        assertEquals("Some actions belong to another virtual host than this connection's", result.blockedReason)
    }

    @Test
    fun `a topology-only file blocked only by the level says so, and a clean one can be applied`() {
        val blocked = evaluate(listOf(declare, delete), level = DECLARE_AND_EDIT)
        assertEquals("Some actions are blocked by control level", blocked.blockedReason)
        val clean = evaluate(listOf(declare, delete), level = FULL_CONTROL)
        assertTrue(clean.canApply)
        assertEquals(null, clean.blockedReason)
        assertEquals(listOf(true, true), clean.actions.map { it.allowed })
    }

    @Test
    fun `a virtual host written with its leading slash is not echoed by today's rule, the pure move keeps that`() {
        // echoableName allows only "/" or plain characters; "/orders" (the usual form of a 3.x name) falls back to the generic wording.
        val slashed = MigrationAction.DeclareQueue("q", vhost = "/orders")
        assertEquals(listOf("The action belongs to another virtual host than this connection's"), reasons(listOf(slashed), level = DECLARE_AND_EDIT))
    }

    @Test
    fun `a virtual host that cannot be echoed is not named`() {
        val odd = MigrationAction.DeclareQueue("q", vhost = "bad host\n<b>")
        assertEquals(
            listOf("The action belongs to another virtual host than this connection's"),
            reasons(listOf(odd), level = DECLARE_AND_EDIT),
        )
    }

    // ---- file with a broker-level action: Read-only first ----

    @Test
    fun `a file with a broker-level action says Read-only first, even without configure permission`() {
        val result = evaluate(listOf(access, declare), configure = false, level = READ_ONLY, tags = setOf("administrator"))
        assertEquals(List(2) { "Topology control level is Read-only" }, result.actions.map { it.reason })
        assertEquals("Topology control level is Read-only", result.blockedReason)
    }

    @Test
    fun `a broker-level action needs the administrator tag, then Full control, the configure permission does not matter to it`() {
        assertEquals(
            listOf("Creating virtual hosts, users and permissions needs the administrator tag"),
            reasons(listOf(access), configure = false, level = FULL_CONTROL),
        )
        assertEquals(
            listOf("Creating virtual hosts, users and permissions needs the level Full control"),
            reasons(listOf(access), configure = false, level = DECLARE_AND_EDIT, tags = setOf("administrator")),
        )
        assertEquals(listOf<String?>(null), reasons(listOf(access), configure = false, level = FULL_CONTROL, tags = setOf("administrator")))
    }

    @Test
    fun `in a file with a broker-level action a topology action keeps its rules, configure first, then the virtual host, then destructive`() {
        val admin = setOf("administrator")
        assertEquals(
            listOf(null, "User has no configure permission on this virtual host"),
            reasons(listOf(access, declare), configure = false, level = FULL_CONTROL, tags = admin),
        )
        assertEquals(
            listOf(
                "Creating virtual hosts, users and permissions needs the level Full control",
                "The action belongs to virtual host orders; this connection is for /",
                "Destructive actions require Full control level",
            ),
            reasons(listOf(access, elsewhere, delete), level = DECLARE_AND_EDIT, tags = admin),
        )
    }

    @Test
    fun `the blocked reason of a file with a broker-level action follows the first blocked action`() {
        val admin = setOf("administrator")
        assertEquals("The user has no configure permission on this connection", evaluate(listOf(access, declare), configure = false, tags = admin).blockedReason)
        assertEquals("Some actions belong to another virtual host than this connection's", evaluate(listOf(access, elsewhere), tags = admin).blockedReason)
        assertEquals("Some actions are blocked by control level", evaluate(listOf(delete, access), level = DECLARE_AND_EDIT, tags = admin).blockedReason)
        assertEquals("Creating virtual hosts, users and permissions needs the administrator tag", evaluate(listOf(access)).blockedReason)
    }

    @Test
    fun `no actions at all is nothing blocked`() {
        val result = evaluate(emptyList())
        assertTrue(result.canApply)
        assertEquals(emptyList(), result.actions)
    }

    // ---- the whole matrix equals the runner (the pure move changes nothing) ----

    @Test
    fun `evaluate gives exactly what MigrationRunner dryRun gives, over every combination`() {
        val files = listOf(
            listOf(declare), listOf(delete), listOf(elsewhere), listOf(declare, delete, elsewhere),
            listOf(access), listOf(access, declare), listOf(access, elsewhere, delete), listOf(declare, MigrationAction.DeleteStream("s")),
            listOf(MigrationAction.SetPermissions("orders", "u"), declare),
        )
        val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor())
        var compared = 0
        for (up in files) for (configure in listOf(true, false)) for (level in TopologyControlLevel.entries) for (vhost in listOf("/", "orders"))
            for (tags in listOf(emptySet(), setOf("administrator"), setOf("monitoring"))) {
                val file = MigrationFile("0001", "d", up, rawJson = "{}", checksum = "c")
                val expected = runner.dryRun(file, configure, level, vhost, tags)
                val actual = MigrationDryRunRules.evaluate(up, configure, level, vhost, tags)
                val where = "actions=${up.map { it.description }} configure=$configure level=$level vhost=$vhost tags=$tags"
                assertEquals(expected.actions, actual.actions, "actions: $where")
                assertEquals(expected.canApply, actual.canApply, "canApply: $where")
                assertEquals(expected.blockedReason, actual.blockedReason, "blockedReason: $where")
                compared++
            }
        assertEquals(files.size * 2 * TopologyControlLevel.entries.size * 2 * 3, compared)
    }
}
