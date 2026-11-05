package dev.caerbannog.core.topology

import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.UnreadableCause
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-08b step 2: why the screen is read-only (brief T5, R6; mockup `banner-reasons`). Order of [TopologyState.banners], the screen draws the first
 * (owner decision O1 = b, 2026-11-05: the level the user can act on comes first): 1 connection level Read-only, 2 no configure permission on the
 * connection's virtual host, 3 declared not confirmed or permissions unreadable, 4 another virtual host. The state carries EVERY reason that applies.
 * On another vhost the broker and declared reasons are hidden (R6). Scenarios:
 * "The topology screen says the connection is set to read-only", "Another virtual host says its permissions were not checked", and the core half of the
 * two `02` text scenarios (the Compose half is 08c). The screen is read-only in M2 in every case: a banner only says why. Contract: [TopologyHarness].
 */
class TopologyViewModelBannersTest {

    private val unlocked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    private val locked = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)
    private val declared = PermissionCheckResult.DeclaredByUser(
        topologyUnlocked = true, publishingEnabled = true, unreadable = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED),
    )

    private fun kinds(s: TopologyHarness) = s.state.banners.map { it.kind }

    private suspend fun TopologyHarness.opened(): TopologyHarness = apply {
        quorumQueues(1)
        open()
    }

    @Test
    fun `a verified unlocked connection above the Read-only level has no banner`() = runTest {
        val s = TopologyHarness(this, permission = unlocked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        assertEquals(emptyList(), s.state.banners)
    }

    @Test
    fun `no configure permission says so with the user and the vhost, the exact wording`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        assertEquals(listOf(TopologyBannerKind.NoConfigure), kinds(s))
        assertEquals(
            "Topology is locked on this connection. The permission check found no configure permission for svc on virtual host /, " +
                "so queues, exchanges and bindings are read-only.",
            s.state.banners.single().headline,
        )
        assertEquals(TopologyTexts.noConfigure("svc", "/"), s.state.banners.single().headline)
    }

    @Test
    fun `the connection level Read-only is a banner of its own, whatever the broker allows`() = runTest {
        val s = TopologyHarness(this, permission = unlocked, level = TopologyControlLevel.READ_ONLY).opened()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel), kinds(s))
        assertEquals("This connection is set to read-only.", s.state.banners.single().headline)
        assertEquals(TopologyTexts.READ_ONLY_HEADLINE, s.state.banners.single().headline)
        assertTrue(s.state.banners.single().detail!!.contains("Read-only"), "the detail names the level")
    }

    @Test
    fun `a higher level than Read-only has no level banner`() = runTest {
        for (level in listOf(TopologyControlLevel.DECLARE_AND_EDIT, TopologyControlLevel.FULL_CONTROL)) {
            val s = TopologyHarness(this, permission = unlocked, level = level).opened()
            assertEquals(emptyList(), s.state.banners, "$level")
        }
    }

    @Test
    fun `owner decision O1, a Read-only level and a broker reason are both reasons, the level first`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.READ_ONLY).opened()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel, TopologyBannerKind.NoConfigure), kinds(s))
    }

    @Test
    fun `the level comes first before a declaration and before unreadable permissions too`() = runTest {
        val a = TopologyHarness(this, permission = declared, level = TopologyControlLevel.READ_ONLY).opened()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel, TopologyBannerKind.DeclaredNotConfirmed), kinds(a))
        val b = TopologyHarness(this, permission = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED), level = TopologyControlLevel.READ_ONLY).opened()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel, TopologyBannerKind.NotReadable), kinds(b), "a non-administrator on a default profile: the level the user can act on is first")
    }

    @Test
    fun `a declaration the broker could not confirm says so, the broker still enforces its own`() = runTest {
        val s = TopologyHarness(this, permission = declared, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        assertEquals(listOf(TopologyBannerKind.DeclaredNotConfirmed), kinds(s))
        val text = s.state.banners.single().let { it.headline + " " + (it.detail ?: "") }
        assertTrue(text.contains("could not confirm"), text)
        assertTrue(text.contains("still enforces its own"), text)
        assertTrue(text.contains("svc"), "it names the user: $text")
    }

    @Test
    fun `permissions that could not be read and no declaration are a banner with the reason`() = runTest {
        val unreadable = PermissionCheckResult.Unreadable(UnreadableCause.TIMEOUT)
        val s = TopologyHarness(this, permission = unreadable, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        assertEquals(listOf(TopologyBannerKind.NotReadable), kinds(s))
        val text = s.state.banners.single().let { it.headline + " " + (it.detail ?: "") }
        assertTrue(text.contains(unreadable.reason), text)
    }

    @Test
    fun `the order is level, no configure, declared or unreadable, other vhost, and on another vhost the broker reasons are hidden`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.READ_ONLY).opened()
        s.topology.declareVhost("billing")
        s.pass(5)
        s.vm.selectVhost("billing")
        s.settle()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel, TopologyBannerKind.OtherVhost), kinds(s), "the broker reason is only for the connection's own vhost (R6)")
    }

    @Test
    fun `another vhost says permissions were checked on the connection's vhost only, the exact wording`() = runTest {
        val s = TopologyHarness(this, permission = unlocked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        s.topology.declareVhost("billing")
        s.pass(5)
        assertEquals(emptyList(), s.state.banners, "on the connection's own vhost there is nothing to say")

        s.vm.selectVhost("billing")
        s.settle()

        assertEquals(listOf(TopologyBannerKind.OtherVhost), kinds(s))
        assertEquals(
            "Permissions were checked on virtual host / only. This virtual host is shown read-only.",
            s.state.banners.single().headline,
        )
        assertEquals(TopologyTexts.otherVhost("/"), s.state.banners.single().headline)
    }

    @Test
    fun `the other vhost banner names the connection's vhost, not the chosen one`() = runTest {
        val s = TopologyHarness(this, vhost = "billing", permission = unlocked, level = TopologyControlLevel.DECLARE_AND_EDIT)
        s.topology.declareVhost("/")
        s.quorumQueues(1)
        s.open()
        s.vm.selectVhost("/")
        s.settle()
        assertEquals("Permissions were checked on virtual host billing only. This virtual host is shown read-only.", s.state.banners.single().headline)
    }

    @Test
    fun `a declaration is about the connection's vhost too, so on another vhost only the other vhost banner shows`() = runTest {
        val s = TopologyHarness(this, permission = declared, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        s.topology.declareVhost("billing")
        s.pass(5)
        s.vm.selectVhost("billing")
        s.settle()
        assertEquals(listOf(TopologyBannerKind.OtherVhost), kinds(s))
    }

    @Test
    fun `coming back to the connection's vhost brings its reasons back`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        s.topology.declareVhost("billing")
        s.pass(5)
        s.vm.selectVhost("billing")
        s.settle()
        s.vm.selectVhost("/")
        s.settle()
        assertEquals(listOf(TopologyBannerKind.NoConfigure), kinds(s))
    }

    @Test
    fun `a changed permission result or level reaches the banners without a reload`() = runTest {
        val s = TopologyHarness(this, permission = unlocked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        assertEquals(emptyList(), s.state.banners)
        s.context.value = TopologyContext(s.profile.copy(topologyControlLevel = TopologyControlLevel.READ_ONLY), unlocked)
        s.settle()
        assertEquals(listOf(TopologyBannerKind.ReadOnlyLevel), kinds(s))
        s.context.value = TopologyContext(s.profile, locked)
        s.settle()
        assertEquals(listOf(TopologyBannerKind.NoConfigure), kinds(s))
    }

    @Test
    fun `no context and no result have no banner, the data still loads`() = runTest {
        val s = TopologyHarness(this).opened()
        s.context.value = null
        s.settle()
        assertEquals(emptyList(), s.state.banners)
        assertEquals(1, s.state.queues.size)
        s.context.value = TopologyContext(s.profile, null)
        s.settle()
        assertEquals(emptyList(), s.state.banners, "a check that has not run is not a reason")
    }

    @Test
    fun `banners come with every screen state of a connected user, also an unavailable vhost and a stale list`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.DECLARE_AND_EDIT).opened()
        s.topology.unreachable = true
        s.pass(5)
        assertTrue(s.state.stale)
        assertEquals(listOf(TopologyBannerKind.NoConfigure), kinds(s))
    }

    @Test
    fun `before the first connection there is no banner`() = runTest {
        val s = TopologyHarness(this, permission = locked, level = TopologyControlLevel.READ_ONLY)
        s.settle()
        assertEquals(emptyList(), s.state.banners)
    }
}
