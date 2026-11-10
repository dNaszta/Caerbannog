package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.migrate3x.fakes.ScriptedDefinitionsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * M8-06c: what the Migrate from 3.x screen may read live. The pure rule [liveSourceOf] (Connected link + held source + session status Done(MigrationSource))
 * and the real [HeldLiveSourceProvider] over the real `ActiveConnection` and `ConnectionSession` with a fake gateway: it follows connect, disconnect and switch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSourceProviderTest {
    private val migration = SessionState(current = CurrentConnection("legacy-eu", PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource("3.13.7"))))
    private val checked = SessionState(current = CurrentConnection("legacy-eu", PermissionCheckStatus.Done(PermissionCheckResult.Checked(true, true))))
    private val definitions = ScriptedDefinitionsSource("legacy-eu")

    private fun streamSource() = StreamSource(FakeStreamCatalog(), FakeStreamOffsets(), "/", FakeTopologyCatalog(), FakeStreamMessageReader(), definitionsSource = definitions)

    // ---- the pure rule ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a connected 3x connection with its source gives that source's definitions source`() {
        assertSame(definitions, liveSourceOf(LinkState.Connected("legacy-eu"), streamSource(), migration))
    }

    @Test
    fun `a 4x connection is not a live source`() {
        assertNull(liveSourceOf(LinkState.Connected("legacy-eu"), streamSource(), checked))
    }

    @Test
    fun `no other link state is a live source, whatever the session says`() {
        listOf(LinkState.Disconnected("legacy-eu"), LinkState.Disconnected(), LinkState.Connecting("legacy-eu"), LinkState.Failed("legacy-eu", "x")).forEach {
            assertNull(liveSourceOf(it, streamSource(), migration), "$it")
        }
    }

    @Test
    fun `without a held source, or before the check has finished, there is no live source`() {
        assertNull(liveSourceOf(LinkState.Connected("legacy-eu"), null, migration))
        assertNull(liveSourceOf(LinkState.Connected("legacy-eu"), streamSource(), SessionState()))
        listOf(null, PermissionCheckStatus.Checking).forEach {
            assertNull(liveSourceOf(LinkState.Connected("legacy-eu"), streamSource(), SessionState(current = CurrentConnection("legacy-eu", it))), "$it")
        }
        assertNull(liveSourceOf(LinkState.Connected("legacy-eu"), streamSource(), SessionState(current = CurrentConnection("legacy-eu", PermissionCheckStatus.Done(PermissionCheckResult.Unreadable(UnreadableCause.NO_PASSWORD))))))
    }

    // ---- the provider over the real connection ----------------------------------------------------------------------------------------------

    private class Rig(val test: TestScope) {
        val h = Harness().also { it.secrets.store("legacy-eu", PASSWORD) }
        val legacy = ConnectionProfile(name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc")
        val provider = HeldLiveSourceProvider(h.active, h.session, test.backgroundScope)

        init {
            h.gateway.brokerRuns("legacy-eu", "3.13.7")
            h.gateway.brokerRuns("uat-eu", "4.1.0")
        }

        suspend fun connect(profile: ConnectionProfile) {
            h.active.connect(profile)
            test.runCurrent()
        }
    }

    @Test
    fun `the provider offers nothing at the start`() = runTest {
        val r = Rig(this); runCurrent()
        assertNull(r.provider.current.value)
    }

    @Test
    fun `the provider follows a 3x connection that connects and disconnects`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.legacy)
        val source = assertNotNull(r.provider.current.value, "a connected 3.x broker is a live source")
        assertEquals("legacy-eu", source.connectionName)
        assertSame(r.h.active.source?.definitionsSource, source)
        r.h.active.disconnect(); runCurrent()
        assertNull(r.provider.current.value, "after Disconnect the live source is gone")
    }

    @Test
    fun `a 4x connection is never offered, and switching from 3x to 4x withdraws the offer`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.h.uat)
        assertNull(r.provider.current.value)
        r.connect(r.legacy)
        assertNotNull(r.provider.current.value)
        r.connect(r.h.uat)
        assertNull(r.provider.current.value, "only the held connection counts: the 4.x one is held now")
    }

    @Test
    fun `while the 3x connection is connecting there is no live source, and a new connection of the same profile is a new source`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.legacy)
        val first = assertNotNull(r.provider.current.value)
        val gate = r.h.gateway.holdOpens("legacy-eu")
        val connecting = launch { r.h.active.connect(r.legacy) }
        runCurrent()
        assertEquals(LinkState.Connecting("legacy-eu"), r.h.active.state.value)
        assertNull(r.provider.current.value, "no source while Connecting")
        gate.release(); connecting.join(); runCurrent()
        val second = assertNotNull(r.provider.current.value)
        assertNotSame(first, second, "a reconnect is a new source, so the screen does not read through the old one")
    }

    @Test
    fun `a failed connect leaves no live source`() = runTest {
        val r = Rig(this); runCurrent()
        r.connect(r.legacy)
        r.h.gateway.failWith("legacy-eu", dev.caerbannog.core.connections.fakes.FakeConnectionGateway.Failure.CREDENTIALS_REFUSED)
        r.connect(r.legacy)
        assertNull(r.provider.current.value)
    }
}
