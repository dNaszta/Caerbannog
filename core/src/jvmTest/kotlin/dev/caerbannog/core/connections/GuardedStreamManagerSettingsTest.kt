package dev.caerbannog.core.connections

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-14c step 2b: the guard that wraps a held connection's stream manager must FORWARD the settings. Without this test a wrapper that calls
 * `inner.createStream(vhost, name)` still compiles (the parameter has a default) and silently drops the retention and the leader locator of every
 * stream the executor creates through a real connection. Once the connection is no longer current nothing reaches the manager, with or without settings.
 */
class GuardedStreamManagerSettingsTest {

    private val settings = StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, segmentSizeBytes = 100_000_000, leaderLocator = "client-local")

    @Test
    fun `the settings reach the inner manager while the connection is current`() = runTest {
        val inner = FakeStreamManager()
        val guarded = GuardedStreamManager(inner) { true }
        assertEquals(StreamManagementResult.Success, guarded.createStream("/", "audit", settings))
        assertEquals(listOf(Triple("/", "audit", settings)), inner.createdWithSettings)
        assertEquals(StreamManagementResult.Success, guarded.createStream("/", "plain"))
        assertEquals(StreamSettings.NONE, inner.createdWithSettings.last().third)
    }

    @Test
    fun `nothing reaches the inner manager once the connection is not current`() = runTest {
        val inner = FakeStreamManager()
        val guarded = GuardedStreamManager(inner) { false }
        assertTrue(guarded.createStream("/", "audit", settings) is StreamManagementResult.Failed)
        assertTrue(inner.createdWithSettings.isEmpty() && inner.createdStreams.isEmpty())
    }
}
