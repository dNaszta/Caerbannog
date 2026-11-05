package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.RefreshTarget
import dev.caerbannog.core.streams.StreamListViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M2-06a step 2 (brief item 6): the connection control follows the SHOWN screen. `ConnectionControlModel` takes an optional `shownTarget:
 * StateFlow<RefreshTarget>`; its "Updated N s ago" is that target's `updatedAtMillis`, its Refresh now is that target's `refreshNow`.
 * Changing the flow's value (the composition root switches it when the user opens or leaves a stream) is enough: no tick is needed
 * to see the new age. The interval stays ONE app-wide value: `setRefreshInterval` still goes through the list view model to the settings.
 * Without the parameter (null) the control behaves as in M2-05 (the list): the existing ConnectionControlModelTest covers that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionControlRefreshTargetTest {

    private class FakeTarget(updated: Long?) : RefreshTarget {
        val updatedFlow = MutableStateFlow(updated)
        override val updatedAtMillis: StateFlow<Long?> = updatedFlow
        var refreshes = 0
        override fun refreshNow() { refreshes++ }
    }

    private class Rig(val test: TestScope, initial: RefreshTarget) {
        val h = Harness()
        val settings = FakeAppSettings(RefreshInterval.Every5)
        val connections = ConnectionsViewModel(Repo(h.uat, h.prod), h.secrets, Reach(), Ca(), h.session, h.active)
        val streamList = StreamListViewModel(h.active, settings, test.backgroundScope, RefreshClock { test.testScheduler.currentTime }, connections::connect)
        val target = MutableStateFlow(initial)
        val model = ConnectionControlModel(
            connections, h.active, streamList, test.backgroundScope, RefreshClock { test.testScheduler.currentTime },
            onOpenConnections = { }, shownTarget = target,
        )
        val note: String? get() = model.state.value.refresh.note
        fun settle() = test.runCurrent()
        fun pass(seconds: Int) {
            test.advanceTimeBy(seconds * 1000L)
            test.runCurrent()
        }
    }

    private class Repo(vararg seed: ConnectionProfile) : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>().also { m -> seed.forEach { m[it.name] = it } }
        override fun list(): List<ConnectionProfile> = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult { profiles[profile.name] = profile; return SaveConnectionResult.Saved(profile) }
        override fun update(profile: ConnectionProfile): SaveConnectionResult { profiles[profile.name] = profile; return SaveConnectionResult.Saved(profile) }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class Reach : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class Ca : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    @Test
    fun `Updated N s ago is the age of the shown target, not of the stream list`() = runTest {
        val a = FakeTarget(updated = 2_000)
        val r = Rig(this, a)
        r.model.connect("uat-eu")
        r.settle()
        r.pass(10) // clock 10 s; the list was never shown, so its own age is null

        r.model.tick()
        r.settle()
        assertEquals("Updated 8 s ago", r.note)
    }

    @Test
    fun `switching the shown target switches the age at once`() = runTest {
        val a = FakeTarget(updated = 2_000)
        val b = FakeTarget(updated = 9_000)
        val r = Rig(this, a)
        r.model.connect("uat-eu")
        r.settle()
        r.pass(10)
        r.model.tick()
        r.settle()
        assertEquals("Updated 8 s ago", r.note)

        r.target.value = b
        r.settle()
        assertEquals("Updated 1 s ago", r.note)
    }

    @Test
    fun `a target that updates moves the note`() = runTest {
        val a = FakeTarget(updated = 0)
        val r = Rig(this, a)
        r.model.connect("uat-eu")
        r.settle()
        r.pass(10)
        r.model.tick()
        r.settle()
        assertEquals("Updated 10 s ago", r.note)

        a.updatedFlow.value = 10_000
        r.settle()
        assertEquals("Updated 0 s ago", r.note)
    }

    @Test
    fun `a target that never loaded gives no age`() = runTest {
        val r = Rig(this, FakeTarget(updated = null))
        r.model.connect("uat-eu")
        r.settle()
        r.pass(10)
        r.model.tick()
        r.settle()
        assertNull(r.note)
    }

    @Test
    fun `Refresh now reaches the shown target only`() = runTest {
        val a = FakeTarget(null)
        val b = FakeTarget(null)
        val r = Rig(this, a)
        r.h.gateway.catalogOf("uat-eu").addStream(stream("orders", messages = 3))
        r.model.connect("uat-eu")
        r.streamList.setShown(true)
        r.settle()
        val listCalls = r.h.gateway.catalogOf("uat-eu").calls.count { it == "listStreams" }

        r.model.refreshNow()
        r.settle()
        assertEquals(1, a.refreshes)
        assertEquals(0, b.refreshes)

        r.target.value = b
        r.settle()
        r.model.refreshNow()
        r.settle()
        assertEquals(1, a.refreshes)
        assertEquals(1, b.refreshes)
        assertEquals(listCalls, r.h.gateway.catalogOf("uat-eu").calls.count { it == "listStreams" }, "the list is not refreshed while another screen is the target")
    }

    @Test
    fun `the interval stays one app-wide setting written through the control whatever is shown`() = runTest {
        val r = Rig(this, FakeTarget(null))
        r.model.connect("uat-eu")
        r.settle()

        r.model.setRefreshInterval(RefreshInterval.Every30)
        r.settle()

        assertEquals(RefreshInterval.Every30, r.settings.streamListRefresh)
        assertEquals(RefreshInterval.Every30, r.model.state.value.refresh.selected)
    }
}
