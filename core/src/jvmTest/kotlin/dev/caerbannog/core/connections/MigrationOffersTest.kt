package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-06c: the popup row "Migrate from 3.x" (`offersMigration`), the card pill rule of F8 (`cardPill`) and the event that opens the screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationOffersTest {
    private val legacy = ConnectionProfile(name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc")
    private val other = ConnectionProfile(name = "other-eu", host = "other", virtualHost = "/", username = "svc")
    private val source = PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource("3.13.7"))
    private val checked = PermissionCheckStatus.Done(PermissionCheckResult.Checked(true, true))

    private fun rows(current: CurrentConnection?, link: LinkState) =
        connectionControlState(listOf(legacy, other), current, link, RefreshInterval.Every5, null, 0).rows

    // ---- offersMigration -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `only the current row of a connected 3x connection offers Migrate from 3x`() {
        val rows = rows(CurrentConnection("legacy-eu", source), LinkState.Connected("legacy-eu"))
        assertTrue(rows.first { it.name == "legacy-eu" }.offersMigration)
        assertFalse(rows.first { it.name == "other-eu" }.offersMigration)
        assertEquals("Migrate from 3.x", ConnectionControlTexts.MIGRATE_FROM_3X)
    }

    @Test
    fun `no other link state, no 4x result and no unfinished check offers it`() {
        listOf(LinkState.Disconnected("legacy-eu"), LinkState.Connecting("legacy-eu"), LinkState.Failed("legacy-eu", "x"), LinkState.Disconnected()).forEach {
            assertFalse(rows(CurrentConnection("legacy-eu", source), it).first { r -> r.name == "legacy-eu" }.offersMigration, "$it")
        }
        listOf(checked, PermissionCheckStatus.Checking, null).forEach {
            assertFalse(rows(CurrentConnection("legacy-eu", it), LinkState.Connected("legacy-eu")).first { r -> r.name == "legacy-eu" }.offersMigration, "$it")
        }
        assertTrue(rows(null, LinkState.Disconnected()).none { it.offersMigration })
    }

    @Test
    fun `the default of the row is no offer, so every existing row stays as it was`() {
        assertFalse(ConnectionRowState("x", ChipIcon.Link, "Connected", RowAction.Disconnect, current = true).offersMigration)
    }

    // ---- cardPill (F8, owner decision B) ------------------------------------------------------------------------------------------------------

    @Test
    fun `the 3x pill shows only while the connection is connected`() {
        val pill = permissionPill(source)
        assertEquals("RabbitMQ 3.x: migration source only", pill.text)
        assertEquals(pill, cardPill(source, connected = true))
        assertNull(cardPill(source, connected = false), "after Disconnect, while Connecting and while Failed the card shows no pill")
    }

    @Test
    fun `every other status keeps its pill, connected or not`() {
        listOf(
            checked, PermissionCheckStatus.Done(PermissionCheckResult.DeclaredByUser(true, false, PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD))),
            PermissionCheckStatus.Done(PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD)), PermissionCheckStatus.Checking, null,
        ).forEach { status ->
            listOf(true, false).forEach { connected -> assertEquals(permissionPill(status), cardPill(status, connected), "$status connected=$connected") }
        }
    }

    // ---- the event and the model ---------------------------------------------------------------------------------------------------------------

    private class Rig(val test: kotlinx.coroutines.test.TestScope, var opened: Int = 0) {
        val h = Harness().also { it.secrets.store("legacy-eu", PASSWORD) }
        val repo = object : ConnectionProfileRepository {
            private val profiles = linkedMapOf("legacy-eu" to ConnectionProfile(name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc"))
            override fun list() = profiles.values.toList()
            override fun add(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
            override fun update(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
            override fun delete(name: String) {}
        }
        val connections = ConnectionsViewModel(
            repo, h.secrets,
            object : BrokerReachabilityChecker { override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable },
            object : CaCertificateValidator { override fun validate(filePath: String) = CaCertificateValidationResult.Valid },
            h.session, h.active,
        )
        val streamList = StreamListViewModel(h.active, FakeAppSettings(RefreshInterval.Every5), test.backgroundScope, RefreshClock { test.testScheduler.currentTime }, connections::connect)
        val model = ConnectionControlModel(
            connections, h.active, streamList, test.backgroundScope, RefreshClock { test.testScheduler.currentTime },
            onOpenConnections = {}, onOpenMigrateFromThree = { opened++ },
        )
    }

    @Test
    fun `the popup entry calls the constructor lambda once, the default event does nothing`() = runTest {
        val r = Rig(this); runCurrent()
        r.model.openMigrateFromThree(); runCurrent()
        assertEquals(1, r.opened)
        val bare = object : ConnectionControlEvents {
            override fun connect(name: String) {}
            override fun disconnect(name: String) {}
            override fun retry(name: String) {}
            override fun setRefreshInterval(interval: RefreshInterval) {}
            override fun refreshNow() {}
            override fun openConnections() {}
        }
        bare.openMigrateFromThree()
        assertEquals(1, r.opened)
    }

    @Test
    fun `after Connect to a 3x broker the model's row offers the migration, after Disconnect it does not`() = runTest {
        val r = Rig(this); runCurrent()
        r.h.gateway.brokerRuns("legacy-eu", "3.13.7")
        r.model.connect("legacy-eu"); runCurrent()
        assertTrue(r.model.state.value.rows.single().offersMigration)
        r.model.disconnect("legacy-eu"); runCurrent()
        assertFalse(r.model.state.value.rows.single().offersMigration)
    }

    @Test
    fun `a 4x broker's row never offers it`() = runTest {
        val r = Rig(this); runCurrent()
        r.h.gateway.brokerRuns("legacy-eu", "4.1.0")
        r.model.connect("legacy-eu"); runCurrent()
        assertFalse(r.model.state.value.rows.single().offersMigration)
    }
}
