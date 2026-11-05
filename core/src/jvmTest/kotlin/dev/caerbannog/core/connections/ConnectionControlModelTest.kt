package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2-05b step 2: `ConnectionControlModel`, the holder behind the chip and the popup, run against the real core classes
 * (ConnectionsViewModel, ActiveConnection, StreamListViewModel) and a fake broker, in VIRTUAL time on the same test scheduler as
 * the 05a thread test style: nothing is awaited by sleeping, `settle()` is `runCurrent()`.
 *
 * Asynchrony (05a review note): every event hops onto the scope, so its effect is read from `state` after `settle()`, never
 * assumed on return. Contract: ConnectionControl.kt (`ConnectionControlModel(connections, active, streamList, scope, clock,
 * onOpenConnections)`, `state`, `tick()`, the six events of `ConnectionControlEvents`, `close()`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControlModelTest {

    private class Repo(vararg seed: ConnectionProfile) : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>().also { m -> seed.forEach { m[it.name] = it } }
        override fun list(): List<ConnectionProfile> = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult {
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class Reach : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private class Rig(val test: TestScope, profiles: Boolean = true) {
        val h = Harness()
        val settings = FakeAppSettings(RefreshInterval.Every5)
        val repo = if (profiles) Repo(h.uat, h.prod) else Repo()
        val connections = ConnectionsViewModel(repo, h.secrets, Reach(), Ca(), h.session, h.active)
        val streamList = StreamListViewModel(
            active = h.active,
            settings = settings,
            scope = test.backgroundScope,
            clock = RefreshClock { test.testScheduler.currentTime },
            reconnect = connections::connect,
        )
        var opened = 0
        val model = ConnectionControlModel(
            connections, h.active, streamList, test.backgroundScope, RefreshClock { test.testScheduler.currentTime },
            onOpenConnections = { opened++ },
        )
        val state: ConnectionControlState get() = model.state.value
        fun row(name: String) = state.rows.first { it.name == name }
        fun settle() = test.runCurrent()
        fun pass(seconds: Int) {
            test.advanceTimeBy(seconds * 1000L)
            test.runCurrent()
        }
    }

    @Test
    fun `at the start nothing is current, the chip says No connection and every profile offers Connect`() = runTest {
        val r = Rig(this)
        r.settle()

        assertEquals(ChipState.NoConnection, r.state.chip.kind)
        assertEquals(listOf("uat-eu", "prod-eu"), r.state.rows.map { it.name })
        assertTrue(r.state.rows.all { it.action == RowAction.Connect })
        assertFalse(r.state.refresh.enabled)
    }

    @Test
    fun `Connect on a row connects it, the chip shows the name, Connected and the pill, the row offers Disconnect`() = runTest {
        val r = Rig(this)
        r.settle()

        r.model.connect("uat-eu")
        r.settle()

        assertEquals(LinkState.Connected("uat-eu"), r.h.active.state.value)
        assertEquals(ChipState.Connected, r.state.chip.kind)
        assertEquals("uat-eu", r.state.chip.text)
        assertEquals("Can change topology", r.state.chip.pill?.text)
        assertEquals(RowAction.Disconnect, r.row("uat-eu").action)
        assertEquals(RowAction.Connect, r.row("prod-eu").action)
    }

    @Test
    fun `Connect on another profile disconnects the held one first and holds exactly one connection`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.model.connect("prod-eu")
        r.settle()

        assertEquals("prod-eu", r.state.chip.text)
        assertEquals(ChipState.Connected, r.state.chip.kind)
        assertEquals("Disconnected", r.row("uat-eu").stateText)
        assertEquals(RowAction.Connect, r.row("uat-eu").action)
        assertEquals(listOf("prod-eu"), r.h.gateway.heldNow.map { it.profileName })
        val log = r.h.gateway.log.toList()
        assertTrue(log.indexOf("close:uat-eu#1") < log.indexOf("opened:prod-eu#2"), "the first connection closed before the second opened: $log")
    }

    @Test
    fun `Disconnect releases the connection, the chip says Disconnected and the row offers Connect`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.model.disconnect("uat-eu")
        r.settle()

        assertEquals(emptyList(), r.h.gateway.heldNow)
        assertEquals(ChipState.Disconnected, r.state.chip.kind)
        assertEquals("uat-eu", r.state.chip.text)
        assertEquals(RowAction.Connect, r.row("uat-eu").action)
        assertFalse(r.state.refresh.enabled)
    }

    @Test
    fun `Disconnect of a profile that is not the held one does nothing`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.model.disconnect("prod-eu")
        r.settle()

        assertEquals(LinkState.Connected("uat-eu"), r.h.active.state.value)
        assertEquals(1, r.h.gateway.heldNow.size)
    }

    @Test
    fun `a failed connect shows Failed with the reason, the row offers Retry, and Retry connects again`() = runTest {
        val r = Rig(this)
        r.h.gateway.failWith("uat-eu", FakeConnectionGateway.Failure.CREDENTIALS_REFUSED)
        r.model.connect("uat-eu")
        r.settle()

        assertEquals(ChipState.Failed, r.state.chip.kind)
        assertEquals("Credentials were refused", r.state.chip.tooltip)
        assertEquals("Failed: Credentials were refused", r.row("uat-eu").stateText)
        assertEquals(RowAction.Retry, r.row("uat-eu").action)

        r.h.gateway.succeed("uat-eu")
        r.model.retry("uat-eu")
        r.settle()

        assertEquals(ChipState.Connected, r.state.chip.kind)
        assertEquals(RowAction.Disconnect, r.row("uat-eu").action)
    }

    @Test
    fun `while connecting the chip says Connecting and the row has no action`() = runTest {
        val r = Rig(this)
        val gate = r.h.gateway.holdOpens("uat-eu")
        r.model.connect("uat-eu")
        r.settle()

        assertEquals(ChipState.Connecting, r.state.chip.kind)
        assertEquals(RowAction.None, r.row("uat-eu").action)
        assertEquals("Connecting", r.row("uat-eu").stateText)

        gate.release()
        r.settle()
        assertEquals(ChipState.Connected, r.state.chip.kind)
    }

    @Test
    fun `a lost connection turns the chip to Failed Connection lost and offers Retry, no reconnect by itself`() = runTest {
        val r = Rig(this)
        r.h.gateway.catalogOf("uat-eu").addStream(stream("orders", messages = 3))
        r.model.connect("uat-eu")
        r.streamList.setShown(true)
        r.settle()

        r.h.gateway.catalogOf("uat-eu").unreachable = true
        r.pass(5)

        assertEquals(ChipState.Failed, r.state.chip.kind)
        assertEquals("Connection lost", r.state.chip.tooltip)
        assertEquals(RowAction.Retry, r.row("uat-eu").action)
        val opens = r.h.gateway.openCalls.size
        r.pass(600)
        assertEquals(opens, r.h.gateway.openCalls.size, "no automatic reconnect (D2)")
    }

    @Test
    fun `after Connection lost no connect reaches the broker and no new link state appears until connect or retry (D2)`() = runTest {
        val r = Rig(this)
        r.h.gateway.catalogOf("uat-eu").addStream(stream("orders", messages = 3))
        r.model.connect("uat-eu")
        r.streamList.setShown(true)
        r.settle()
        val seen = mutableListOf<LinkState>()
        backgroundScope.launch { r.h.active.state.collect { seen += it } }
        r.settle()
        val opensBeforeLoss = r.h.gateway.openCalls.size

        r.h.gateway.catalogOf("uat-eu").unreachable = true
        r.pass(5)
        val lost = LinkState.Failed("uat-eu", ConnectionFailureReason.CONNECTION_LOST)
        assertEquals(lost, r.h.active.state.value)
        // The loss itself is the only new state: no Connecting follows it, not even once.
        assertEquals(listOf(LinkState.Connected("uat-eu"), lost), seen.toList(), "link states up to and including the loss")
        val opens = r.h.gateway.openCalls.size
        assertEquals(opensBeforeLoss, opens, "no connect call right after the loss")
        val log = r.h.gateway.log.toList()
        val seenAtLoss = seen.toList()

        // The broker is reachable again; time passes (many refresh intervals, many ticks), the user does nothing.
        r.h.gateway.catalogOf("uat-eu").unreachable = false
        repeat(20) {
            r.pass(60)
            r.model.tick()
            r.settle()
        }

        assertEquals(opens, r.h.gateway.openCalls.size, "no connect call after Connection lost without connect or retry (D2)")
        assertEquals(log, r.h.gateway.log.toList(), "the broker saw nothing after Connection lost")
        assertEquals(seenAtLoss, seen.toList(), "no new link state (not even Connecting) without connect or retry")
        assertEquals(ChipState.Failed, r.state.chip.kind)
        assertEquals(RowAction.Retry, r.row("uat-eu").action)

        // Control: the explicit Retry is what reconnects.
        r.model.retry("uat-eu")
        r.settle()
        assertEquals(opens + 1, r.h.gateway.openCalls.size)
        assertEquals(LinkState.Connected("uat-eu"), r.h.active.state.value)
    }

    @Test
    fun `deleting the current profile puts the chip back to No connection and drops its row`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.connections.requestDelete("uat-eu")
        r.connections.confirmDelete()
        r.settle()

        assertEquals(ChipState.NoConnection, r.state.chip.kind)
        assertEquals(listOf("prod-eu"), r.state.rows.map { it.name })
    }

    @Test
    fun `no saved profile shows No saved connections`() = runTest {
        val r = Rig(this, profiles = false)
        r.settle()

        assertEquals("No saved connections", r.state.emptyText)
        assertEquals(emptyList(), r.state.rows)
    }

    @Test
    fun `a profile added later appears as a row`() = runTest {
        val r = Rig(this, profiles = false)
        r.settle()

        r.connections.addConnection(ConnectionProfileInput(name = "new-eu", host = "h", virtualHost = "/", username = "u", password = null))
        r.settle()

        assertEquals(listOf("new-eu"), r.state.rows.map { it.name })
        assertNull(r.state.emptyText)
    }

    // --- refresh section ---

    @Test
    fun `setRefreshInterval writes the setting and the selection follows`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.model.setRefreshInterval(RefreshInterval.Every30)
        r.settle()

        assertEquals(RefreshInterval.Every30, r.settings.streamListRefresh)
        assertEquals(RefreshInterval.Every30, r.state.refresh.selected)
        assertEquals(RefreshInterval.Every30, r.streamList.state.value.refreshInterval)
    }

    @Test
    fun `the interval can be chosen while not connected, the section is only disabled`() = runTest {
        val r = Rig(this)
        r.settle()
        assertFalse(r.state.refresh.enabled)

        r.model.setRefreshInterval(RefreshInterval.Off)
        r.settle()

        // The control is disabled in the UI, but the event itself is harmless: the value is a per-app setting.
        assertEquals(RefreshInterval.Off, r.state.refresh.selected)
    }

    @Test
    fun `Refresh now loads the list once more and the note says Updated 0 s ago`() = runTest {
        val r = Rig(this)
        r.h.gateway.catalogOf("uat-eu").addStream(stream("orders", messages = 3))
        r.model.connect("uat-eu")
        r.streamList.setShown(true)
        r.settle()
        val before = r.h.gateway.catalogOf("uat-eu").calls.count { it == "listStreams" }

        r.pass(3)
        r.model.refreshNow()
        r.settle()

        assertEquals(before + 1, r.h.gateway.catalogOf("uat-eu").calls.count { it == "listStreams" })
        assertEquals("Updated 0 s ago", r.state.refresh.note)
    }

    @Test
    fun `Connected before any load says Not loaded yet`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        assertEquals("Not loaded yet", r.state.refresh.note, "the list was never shown, so nothing was loaded")
    }

    @Test
    fun `the age grows with tick and is read from the clock, not from a timer in the model`() = runTest {
        val r = Rig(this)
        r.h.gateway.catalogOf("uat-eu").addStream(stream("orders", messages = 3))
        r.model.connect("uat-eu")
        r.streamList.setShown(true)
        r.settle()
        assertEquals("Updated 0 s ago", r.state.refresh.note)

        r.streamList.setRefreshInterval(RefreshInterval.Off)
        r.settle()
        r.test.testScheduler.advanceTimeBy(12_000)
        r.model.tick()
        r.settle()

        assertEquals("Updated 12 s ago", r.state.refresh.note)
    }

    @Test
    fun `the model makes no broker call of its own - connecting and ticking never list streams while the list is not shown`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()
        r.model.tick()
        r.pass(60)

        assertEquals(emptyList(), r.h.gateway.catalogOf("uat-eu").calls.filter { it != "capabilities" }.toList())
    }

    // --- navigation and lifetime ---

    @Test
    fun `openConnections calls the navigation callback once per call and touches no connection`() = runTest {
        val r = Rig(this)
        r.model.connect("uat-eu")
        r.settle()

        r.model.openConnections()
        r.settle()

        assertEquals(1, r.opened)
        assertEquals(LinkState.Connected("uat-eu"), r.h.active.state.value)
    }

    @Test
    fun `after close the state no longer follows`() = runTest {
        val r = Rig(this)
        r.settle()
        r.model.close()

        r.model.connect("uat-eu")
        r.settle()

        assertEquals(ChipState.NoConnection, r.state.chip.kind)
    }

    @Test
    fun `no password or secret is in any chip or row text after a failed connect`() = runTest {
        val r = Rig(this)
        r.h.gateway.failWith("uat-eu", FakeConnectionGateway.Failure.CREDENTIALS_REFUSED)
        r.model.connect("uat-eu")
        r.settle()

        assertFalse(r.state.toString().contains(PASSWORD), r.state.toString())
    }
}
