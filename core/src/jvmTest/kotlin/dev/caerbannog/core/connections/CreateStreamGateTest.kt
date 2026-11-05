package dev.caerbannog.core.connections

import dev.caerbannog.core.streams.StreamListTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-05c step 2: the rule behind the Create stream button (brief item 10, spec "Rules": no configure permission, no topology
 * change). Enabled only while the link is Connected AND the permission check answered with topology unlocked (confirmed or
 * declared, the same `topologyUnlocked` the pill shows). Disabled: the reason is the pill text, so the tooltip and the Topology
 * locked pill say the same thing; with no connection the reason is "Not connected". The button does nothing in M2-05 (the dialog is M3).
 */
class CreateStreamGateTest {

    private fun done(result: PermissionCheckResult) = CurrentConnection("prod-eu", PermissionCheckStatus.Done(result))
    private val connected = LinkState.Connected("prod-eu")
    private val unreadable = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)

    @Test
    fun `a connection the broker confirmed as unlocked enables it`() {
        assertEquals(CreateStreamGate.Enabled, createStreamGate(done(PermissionCheckResult.Checked(true, true)), connected))
    }

    @Test
    fun `a connection the user declared as unlocked enables it, as the pill does`() {
        assertEquals(CreateStreamGate.Enabled, createStreamGate(done(PermissionCheckResult.DeclaredByUser(true, false, unreadable)), connected))
    }

    @Test
    fun `no configure permission disables it and the reason is the pill text`() {
        assertEquals(CreateStreamGate.Disabled("Topology locked"), createStreamGate(done(PermissionCheckResult.Checked(false, true)), connected))
    }

    @Test
    fun `a declared lock disables it with the declared pill text`() {
        assertEquals(
            CreateStreamGate.Disabled("Topology locked (declared)"),
            createStreamGate(done(PermissionCheckResult.DeclaredByUser(false, true, unreadable)), connected),
        )
    }

    @Test
    fun `permissions that could not be read keep it disabled (safest mode)`() {
        assertEquals(CreateStreamGate.Disabled("Topology locked"), createStreamGate(done(unreadable), connected))
    }

    @Test
    fun `no connection disables it with Not connected`() {
        assertEquals(CreateStreamGate.Disabled(StreamListTexts.NOT_CONNECTED), createStreamGate(null, LinkState.Disconnected()))
    }

    @Test
    fun `a disconnected connection disables it even though its permissions were unlocked`() {
        val gate = createStreamGate(done(PermissionCheckResult.Checked(true, true)), LinkState.Disconnected("prod-eu"))
        assertEquals(CreateStreamGate.Disabled(StreamListTexts.NOT_CONNECTED), gate)
    }

    @Test
    fun `connecting and failed disable it, and the permission is not looked at before the link is up`() {
        for (link in listOf(LinkState.Connecting("prod-eu"), LinkState.Failed("prod-eu", ConnectionFailureReason.CONNECTION_LOST))) {
            val gate = createStreamGate(done(PermissionCheckResult.Checked(true, true)), link)
            assertTrue(gate is CreateStreamGate.Disabled && gate.reason.isNotBlank(), "link $link gave $gate")
        }
    }

    @Test
    fun `permissions not checked yet or still checking disable it`() {
        for (status in listOf(null, PermissionCheckStatus.Checking)) {
            val gate = createStreamGate(CurrentConnection("prod-eu", status), connected)
            assertTrue(gate is CreateStreamGate.Disabled && gate.reason.isNotBlank(), "status $status gave $gate")
        }
    }
}
