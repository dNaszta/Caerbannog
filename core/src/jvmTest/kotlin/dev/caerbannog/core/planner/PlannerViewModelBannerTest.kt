package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * M9-02: the one banner of the planner, the first that applies, in this order (mockup states 12a to 12g, spec "Topology planner (M9)"):
 * 1 the connection cannot run migrations (3.x, version unknown), 2 the draft cannot be read, 3 Read-only level, 4 no configure permission,
 * 5 not connected, 6 a folder problem, 7 the plan's virtual host is not the connection's.
 */
class PlannerViewModelBannerTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun close() = closers.forEach { it() }

    private val reason3x = "This broker runs RabbitMQ 3.x: it is a migration source only"

    private fun rig(
        context: PlannerContext = PlannerContext(),
        folder: String? = PlannerRig.FOLDER,
        prepare: PlannerRig.() -> Unit = {},
    ): PlannerRig = PlannerRig(folderPath = folder, context = context).also { closers += it::close; it.prepare(); it.restart() }

    @Test
    fun `no banner when everything is fine`() {
        assertNull(rig().state.banner)
    }

    @Test
    fun `1 a connection that cannot run migrations shows the planner is not offered with the reason`() {
        val banner = assertIs<PlannerBanner.NotOffered>(rig(PlannerContext(unavailableReason = reason3x)).state.banner)
        assertEquals("The planner is not offered on this connection", banner.title)
        assertEquals(reason3x, banner.detail)
    }

    @Test
    fun `an unreadable version is the same banner with its reason`() {
        val text = "The broker version could not be read, so migrations are not offered"
        assertEquals(text, assertIs<PlannerBanner.NotOffered>(rig(PlannerContext(unavailableReason = text)).state.banner).detail)
    }

    @Test
    fun `2 an unreadable draft shows the path and the reason`() {
        val r = rig { store.putUnreadable() }
        val banner = assertIs<PlannerBanner.DraftUnreadable>(r.state.banner)
        assertEquals("The saved draft cannot be read", banner.title)
        assertEquals(r.store.path, banner.path)
        assertEquals("The file is not a valid plan", banner.detail)
    }

    @Test
    fun `3 the Read-only level says you can plan, check and save`() {
        val banner = assertIs<PlannerBanner.ReadOnlyLevel>(rig(PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY)).state.banner)
        assertEquals("This connection is set to Read-only. You can plan, check and save; applying needs the level Declare and edit.", banner.title)
    }

    @Test
    fun `4 no configure permission names the connection`() {
        val banner = assertIs<PlannerBanner.NoConfigurePermission>(rig(PlannerContext(hasConfigurePermission = false)).state.banner)
        assertEquals(
            "The permission check found that this user cannot change topology on uat-eu. You can plan, check and save, then apply the migration on a connection that allows it.",
            banner.title,
        )
    }

    @Test
    fun `5 not connected says you can plan and save`() {
        val banner = assertIs<PlannerBanner.NotConnected>(rig(PlannerContext(connectionName = null)).state.banner)
        assertEquals("Not connected. You can plan and save; connect to a RabbitMQ 4 broker to check the plan.", banner.title)
    }

    @Test
    fun `6 no folder chosen asks for one and says the plan stays a draft`() {
        val banner = assertIs<PlannerBanner.FolderProblem>(rig(folder = null).state.banner)
        assertEquals("Choose a migrations folder to save this plan. Until then it stays a draft on this machine.", banner.title)
    }

    @Test
    fun `6 an unavailable folder shows its fixed reason and path`() {
        FolderProblem.entries.forEach { problem ->
            val r = rig { folder.unavailable = problem }
            val banner = assertIs<PlannerBanner.FolderProblem>(r.state.banner, "$problem")
            assertEquals(problem.message, banner.title)
            assertEquals(PlannerRig.FOLDER, banner.detail, "the path the Migrations screen shows too")
        }
    }

    @Test
    fun `6 a read-only folder says so`() {
        val banner = assertIs<PlannerBanner.FolderProblem>(rig { folder.readOnly = true }.state.banner)
        assertEquals("The folder is read-only", banner.title)
    }

    @Test
    fun `7 a plan for another virtual host says where it can be applied`() {
        val r = rig(PlannerContext(vhost = "/")) { store.putDraft("", listOf(MigrationAction.DeclareQueue("orders", vhost = "/orders"))) }
        val banner = assertIs<PlannerBanner.VhostMismatch>(r.state.banner)
        assertEquals(
            "This plan is for the virtual host /orders; uat-eu is connected to /. It can be saved here and applied on a connection for /orders.",
            banner.title,
        )
    }

    @Test
    fun `no vhost banner when not connected, the plan is for the vhost it says`() {
        val r = rig(PlannerContext(connectionName = null, vhost = "/")) { store.putDraft("", listOf(MigrationAction.DeclareQueue("orders", vhost = "/orders"))) }
        assertIs<PlannerBanner.NotConnected>(r.state.banner)
    }

    // ---- the order: each pair, the earlier one wins ----

    private val unreadable: PlannerRig.() -> Unit = { store.putUnreadable() }

    @Test
    fun `3x wins over an unreadable draft`() {
        assertIs<PlannerBanner.NotOffered>(rig(PlannerContext(unavailableReason = reason3x), prepare = unreadable).state.banner)
    }

    @Test
    fun `an unreadable draft wins over Read-only`() {
        assertIs<PlannerBanner.DraftUnreadable>(rig(PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY), prepare = unreadable).state.banner)
    }

    @Test
    fun `Read-only wins over no configure permission`() {
        val ctx = PlannerContext(controlLevel = TopologyControlLevel.READ_ONLY, hasConfigurePermission = false)
        assertIs<PlannerBanner.ReadOnlyLevel>(rig(ctx).state.banner)
    }

    @Test
    fun `no configure permission wins over not connected and a folder problem`() {
        assertIs<PlannerBanner.NoConfigurePermission>(rig(PlannerContext(hasConfigurePermission = false), folder = null).state.banner)
    }

    @Test
    fun `not connected wins over a folder problem`() {
        assertIs<PlannerBanner.NotConnected>(rig(PlannerContext(connectionName = null), folder = null).state.banner)
    }

    @Test
    fun `a folder problem wins over a virtual host that differs`() {
        val r = rig(folder = null) { store.putDraft("", listOf(MigrationAction.DeclareQueue("orders", vhost = "/orders"))) }
        assertIs<PlannerBanner.FolderProblem>(r.state.banner)
    }

    @Test
    fun `the banner follows the folder when it is chosen`() {
        val r = rig(folder = null)
        assertIs<PlannerBanner.FolderProblem>(r.state.banner)
        r.folders.store.folder = PlannerRig.FOLDER
        assertNull(r.state.banner)
    }
}
