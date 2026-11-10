package dev.caerbannog.core.connections

import dev.caerbannog.core.migrate3x.FetchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-02a: a connection to a 3.x broker becomes a migration source after Connect; the version is carried by the held connection, the
 * session and the source. The checker says the user may do everything on purpose: the broker's rights must not matter.
 */
class MigrationSourceConnectionTest {

    private val legacy = ConnectionProfile(
        name = "legacy-eu", host = "legacy", virtualHost = "/", username = "svc", topologyControlLevel = TopologyControlLevel.FULL_CONTROL,
    )

    private fun harness(): Harness = Harness().also { it.secrets.store("legacy-eu", PASSWORD) }

    @Test
    fun `a 3x connection is marked a migration source and stays locked for a user with every right at full control`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.checker.result = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
        h.active.connect(legacy)
        assertIs<LinkState.Connected>(h.active.state.value)
        val result = assertNotNull(h.session.permissions("legacy-eu"))
        assertEquals(PermissionCheckResult.MigrationSource("3.13.7"), result)
        assertFalse(result.topologyUnlocked)
        assertFalse(result.publishingEnabled)
        assertEquals(PermissionPill("RabbitMQ 3.x: migration source only", PillTone.WARNING), permissionPill(h.session.state.value.current?.status))
        assertEquals(setOf(ConnectionFeature.MigrateFromThreeX), availableFeatures(result))
    }

    @Test
    fun `the source carries the version and a definitions source, and no stream capability changes`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.active.connect(legacy)
        val source = assertNotNull(h.active.source)
        assertEquals("3.13.7", source.brokerVersion)
        assertNotNull(source.definitionsSource, "a 3.x source can be analysed live")
        assertNull(source.migrationExecutor)
        assertEquals("This broker runs RabbitMQ 3.x: it is a migration source only", source.migrationUnavailableReason)
    }

    @Test
    fun `a 4x connection is not marked and has no definitions source`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.brokerRuns("uat-eu", "4.1.0")
        val checked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = false)
        h.checker.result = checked
        h.active.connect(h.uat)
        assertEquals(checked, h.session.permissions("uat-eu"))
        val source = assertNotNull(h.active.source)
        assertEquals("4.1.0", source.brokerVersion)
        assertNull(source.definitionsSource)
    }

    @Test
    fun `an unreadable version is neither marked nor analysable`() = runBlocking<Unit> {
        val h = Harness()
        h.gateway.versionCannotBeRead("uat-eu")
        val checked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
        h.checker.result = checked
        h.active.connect(h.uat)
        assertEquals(checked, h.session.permissions("uat-eu"), "unknown must keep today's behaviour")
        val source = assertNotNull(h.active.source)
        assertNull(source.brokerVersion)
        assertNull(source.definitionsSource)
        assertNull(source.migrationExecutor)
        assertEquals("The broker version could not be read, so migrations are not offered", source.migrationUnavailableReason)
    }

    @Test
    fun `a connection the gateway gave no version for behaves as before`() = runBlocking<Unit> {
        val h = Harness()
        h.active.connect(h.uat)
        assertEquals(PermissionCheckResult.Checked(true, true), h.session.permissions("uat-eu"))
        assertNull(assertNotNull(h.active.source).definitionsSource)
    }

    @Test
    fun `the version is read once when the connection opens and not again by marking or analysing`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.active.connect(legacy)
        assertEquals(1, h.gateway.overviewReads)
        assertNotNull(h.active.source?.definitionsSource, "a 3.x connection has a definitions source").fetch()
        assertEquals(1, h.gateway.overviewReads, "analysing must not read the overview again")
    }

    @Test
    fun `a connect that was superseded does not mark the session and its source reads nothing`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        val gate = h.gateway.holdOpens("legacy-eu")
        val first = async(start = CoroutineStart.UNDISPATCHED) { h.active.connect(legacy) }
        h.gateway.awaitOpenStarted()
        h.active.connect(h.uat)
        gate.release()
        first.await()
        assertEquals(LinkState.Connected("uat-eu"), h.active.state.value)
        assertFalse(h.session.permissions("legacy-eu") is PermissionCheckResult.MigrationSource, "a superseded connect must not write to the session")
        assertTrue(h.gateway.heldNow.none { it.profileName == "legacy-eu" }, "the superseded connection is closed")
    }

    @Test
    fun `the source of a closed 3x connection reads nothing`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.active.connect(legacy)
        val old = assertNotNull(h.active.source)
        h.active.connect(h.uat)
        val outcome = assertNotNull(old.definitionsSource, "a 3.x connection has a definitions source").fetch()
        assertTrue(outcome !is FetchResult.Fetched, "a superseded source must not return data: $outcome")
        assertEquals(emptyList(), h.gateway.threeXReadsOf("legacy-eu").calls, "nothing may reach the broker after the connection is gone")
    }

    @Test
    fun `no password reaches the session state, the link state or the marked result`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.active.connect(legacy)
        assertNotNull(h.active.source?.definitionsSource, "a 3.x connection has a definitions source").fetch()
        val text = h.exposedText() + h.session.permissions("legacy-eu") + h.active.source.toString() + h.gateway.threeXReadsOf("legacy-eu").calls
        assertFalse(PASSWORD in text, "password found in observable text")
    }

    @Test
    fun `an older mark does not overwrite a check that is running, nor a forgotten connection`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val checker = object : PermissionChecker {
            var first = true
            override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
                if (!first) gate.await()
                first = false
                return PermissionCheckResult.Checked(true, true)
            }
        }
        val session = ConnectionSession(checker, MapSecrets(mapOf("legacy-eu" to PASSWORD)))
        session.open(legacy)
        val second = async(start = CoroutineStart.UNDISPATCHED) { session.open(legacy) }
        assertEquals(PermissionCheckStatus.Checking, session.state.value.statuses["legacy-eu"])
        session.markMigrationSource("legacy-eu", "3.13.7")
        assertEquals(PermissionCheckStatus.Checking, session.state.value.statuses["legacy-eu"], "a newer check running must win")
        gate.complete(Unit)
        second.await()
        assertEquals(PermissionCheckResult.Checked(true, true), session.permissions("legacy-eu"), "the newer result stays")
        session.forget("legacy-eu")
        session.markMigrationSource("legacy-eu", "3.13.7")
        assertNull(session.permissions("legacy-eu"), "forget wins")
        assertNull(session.state.value.statuses["legacy-eu"])
    }

    @Test
    fun `marking replaces only a finished result and keeps current in step`() = runBlocking<Unit> {
        val session = ConnectionSession(object : PermissionChecker {
            override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(true, true)
        }, MapSecrets(mapOf("legacy-eu" to PASSWORD)))
        session.markMigrationSource("legacy-eu", "3.13.7")
        assertNull(session.permissions("legacy-eu"), "nothing to replace: not checked")
        session.select("legacy-eu")
        session.open(legacy)
        session.markMigrationSource("legacy-eu", "3.13.7")
        val state = session.state.value
        assertEquals(PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource("3.13.7")), state.statuses["legacy-eu"])
        assertEquals(state.statuses["legacy-eu"], state.current?.status, "current.status always equals the status of that name")
    }

    @Test
    fun `a 3x connection keeps its mark until the next check`() = runBlocking<Unit> {
        val h = harness()
        h.gateway.brokerRuns("legacy-eu", "3.13.7")
        h.active.connect(legacy)
        h.active.disconnect()
        // The mark belongs to this check; a fresh connect checks again and marks again.
        h.active.connect(legacy)
        assertEquals(PermissionCheckResult.MigrationSource("3.13.7"), h.session.permissions("legacy-eu"))
        assertEquals(2, h.gateway.overviewReads, "one overview read per connect")
    }
}
