package dev.caerbannog.core.access

import dev.caerbannog.core.access.fakes.FakeAccessGateway
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.LinkState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M10-02 step 2 (test developer): every state of the Who am I tab and the rules around it (brief step 3; spec "Access view (M10)"; scenarios
 * `22_access_who_am_i_and_permissions.feature` tagged `@m10-02`; mockup `docs/ux/Access.dc.html` frames "Who am I: ..."). Virtual time, fakes only.
 * Contract: [AccessHarness]. The view model is a compile stub today, so these fail on assertions until it is built.
 */
class AccessViewModelTest {

    // ---------------------------------------------------------------- before and during the connection

    @Test
    fun `before any connection the screen says Not connected and nothing is called`() = runTest {
        val s = AccessHarness(this)
        s.vm.setShown(true)
        s.settle()
        assertIs<AccessScreen.NotConnected>(s.state.screen)
        assertEquals(AccessTexts.NOT_CONNECTED, s.state.message)
        assertNull(s.state.readOnlyNote)
        assertEquals(emptyList(), s.requests())
        assertTrue(s.state.offered, "nothing is held: the drawer item is there")
    }

    @Test
    fun `while Connect is waiting the screen says Connecting and nothing is called`() = runTest {
        val s = AccessHarness(this)
        val gate = s.h.gateway.holdOpens("prod-eu")
        s.vm.setShown(true)
        val connecting = launch { s.h.active.connect(s.profile) }
        s.h.gateway.awaitOpenStarted()
        s.settle()
        assertIs<AccessScreen.Connecting>(s.state.screen)
        assertEquals(AccessTexts.CONNECTING, s.state.message)
        assertEquals(emptyList(), s.requests(), "no request before the connection is held")
        gate.release()
        connecting.join()
    }

    @Test
    fun `a read that is still running shows loading rows with the loading text, never a blank`() = runTest {
        val s = AccessHarness(this)
        val gate = s.access.holdNext("whoami")
        s.open()
        assertIs<AccessScreen.Loading>(s.state.screen)
        assertEquals(AccessTexts.LOADING, s.state.message)
        assertEquals(AccessTexts.READ_ONLY_NOTE, s.state.readOnlyNote)
        assertEquals(VhostsStatus.Loading, s.state.vhostsStatus)
        assertNull(s.state.user)
        gate.release()
        s.settle()
        assertIs<AccessScreen.Loaded>(s.state.screen)
    }

    // ---------------------------------------------------------------- loaded

    @Test
    fun `loaded shows the user, the tags in the broker's order, and the read-only note`() = runTest {
        val s = AccessHarness(this)
        s.access.user = "ops-admin"
        s.access.tags = listOf("administrator", "monitoring")
        s.open()
        assertIs<AccessScreen.Loaded>(s.state.screen)
        assertNull(s.state.message)
        assertEquals("ops-admin", s.state.user)
        assertEquals(listOf("administrator", "monitoring"), s.state.tags)
        assertFalse(s.state.tokenUser)
        assertEquals(AccessTexts.READ_ONLY_NOTE, s.state.readOnlyNote)
        assertNull(s.state.action)
        assertFalse(s.state.stale)
        assertNull(s.state.connectionBanner)
        assertNull(s.state.notEncrypted)
        assertNull(s.state.leaveNotice)
    }

    @Test
    fun `the virtual hosts show the connection's own first with its note, then the others alphabetical`() = runTest {
        val s = AccessHarness(this)
        s.access.listedVhosts = listOf("/staging", "/orders", "/", "/billing")
        s.open()
        assertEquals(VhostsStatus.Listed, s.state.vhostsStatus)
        assertEquals(
            listOf(
                VhostRowView("/orders", AccessTexts.CONNECTION_VHOST_NOTE),
                VhostRowView("/", null),
                VhostRowView("/billing", null),
                VhostRowView("/staging", null),
            ),
            s.state.vhosts,
        )
        assertNull(s.state.vhostsMessage)
    }

    @Test
    fun `when the connection's own virtual host is not in the broker's list nothing is marked`() = runTest {
        val s = AccessHarness(this)
        s.access.listedVhosts = listOf("/billing", "/")
        s.open()
        assertEquals(listOf("/", "/billing"), s.state.vhosts.map { it.name })
        assertTrue(s.state.vhosts.all { it.note == null })
    }

    @Test
    fun `a token user is flagged from is_internal_user false and shown as the broker reports it`() = runTest {
        val s = AccessHarness(this)
        s.access.user = "jdoe"
        s.access.internalUser = false
        s.open()
        assertEquals("jdoe", s.state.user)
        assertTrue(s.state.tokenUser)
        assertEquals(listOf("monitoring"), s.state.tags)
        assertEquals("as the broker reports it", AccessTexts.TOKEN_USER_NOTE)
    }

    @Test
    fun `a first read costs exactly two reads, whoami and the virtual host list`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals(setOf("whoami", "vhosts"), s.requests().toSet())
        assertEquals(2, s.requests().size)
    }

    @Test
    fun `the time of the last good read is published for the Updated N s ago text`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals(testScheduler.currentTime, s.vm.updatedAtMillis.value)
    }

    // ---------------------------------------------------------------- empty, locked, errors

    @Test
    fun `a broker that lists no virtual host says so and the user and tags still show`() = runTest {
        val s = AccessHarness(this)
        s.access.listedVhosts = emptyList()
        s.open()
        assertIs<AccessScreen.Loaded>(s.state.screen)
        assertEquals("svc-orders", s.state.user)
        assertEquals(listOf("monitoring"), s.state.tags)
        assertEquals(VhostsStatus.Empty, s.state.vhostsStatus)
        assertEquals(AccessTexts.NO_VHOSTS, s.state.vhostsMessage)
        assertEquals(emptyList(), s.state.vhosts)
    }

    @Test
    fun `a user without a management tag is locked with the approved message and no data`() = runTest {
        val s = AccessHarness(this)
        s.access.hasManagementTag = false
        s.open()
        assertIs<AccessScreen.Locked>(s.state.screen)
        assertEquals(AccessTexts.LOCKED_TITLE, s.state.message)
        assertEquals(AccessTexts.LOCKED_DETAIL, s.state.detail)
        assertNull(s.state.user)
        assertEquals(emptyList(), s.state.tags)
        assertEquals(emptyList(), s.state.vhosts)
        assertEquals(VhostsStatus.None, s.state.vhostsStatus)
        assertNull(s.state.readOnlyNote, "the lock frame has no read-only note")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "the broker answered: the connection is not lost")
        assertNull(s.state.connectionBanner)
    }

    @Test
    fun `a virtual host list that cannot be read keeps the user and tags and says why, with Retry`() = runTest {
        val s = AccessHarness(this)
        s.access.vhostsFailure = AccessResult.Unexpected("HTTP 500")
        s.open()
        assertIs<AccessScreen.Loaded>(s.state.screen)
        assertEquals("svc-orders", s.state.user)
        assertEquals(listOf("monitoring"), s.state.tags)
        assertEquals(VhostsStatus.Unreadable, s.state.vhostsStatus)
        assertEquals(AccessTexts.VHOSTS_ERROR, s.state.vhostsMessage)
        assertEquals("HTTP 500", s.state.vhostsReason)
        assertEquals(emptyList(), s.state.vhosts)
        assertEquals(AccessTexts.RETRY, s.state.action)
    }

    @Test
    fun `Retry after an unreadable virtual host list reads again and clears the error`() = runTest {
        val s = AccessHarness(this)
        s.access.vhostsFailure = AccessResult.Unexpected("HTTP 500")
        s.open()
        s.access.vhostsFailure = null
        s.vm.retry()
        s.settle()
        assertEquals(VhostsStatus.Listed, s.state.vhostsStatus)
        assertNull(s.state.vhostsMessage)
        assertNull(s.state.vhostsReason)
        assertNull(s.state.action)
        assertEquals(emptyList(), s.reconnects, "the link was fine: Retry only reads again")
    }

    @Test
    fun `a whoami that fails shows the mapped reason only, with Retry, and keeps the connection`() = runTest {
        val s = AccessHarness(this)
        s.access.whoamiFailure = AccessResult.Unexpected("HTTP 500")
        s.open()
        assertEquals(AccessScreen.Failed("HTTP 500"), s.state.screen, "the mapped reason is the 'Broker text'")
        assertEquals(AccessTexts.USER_ERROR, s.state.message)
        assertEquals("The user could not be read.", AccessTexts.USER_ERROR)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertNull(s.state.user)
        assertEquals(emptyList(), s.state.tags)
        assertEquals(emptyList(), s.state.vhosts, "no virtual host list is shown")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value)
    }

    @Test
    fun `a refused sign-in on a read is reported as refused without echoing anything and is not a lost connection`() = runTest {
        val s = AccessHarness(this)
        s.access.refusesSignIn = true
        s.open()
        assertEquals(AccessScreen.Failed(AccessTexts.SIGN_IN_REFUSED), s.state.screen)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "the broker answered; D2 is for an unreachable broker")
        assertFalse(s.state.toString().contains("S3cret-PW-4711"), "no password anywhere in the state")
    }

    // ---------------------------------------------------------------- mid-session refusals (architect decision 8)

    @Test
    fun `whoami ok then virtual hosts say no management tag locks the whole screen and drops the user`() = runTest {
        val s = AccessHarness(this)
        s.access.vhostsFailure = AccessResult.NoManagementTag
        s.open()
        assertIs<AccessScreen.Locked>(s.state.screen)
        assertEquals(AccessTexts.LOCKED_TITLE, s.state.message)
        assertNull(s.state.user, "the earlier user may be stale: it must go")
        assertEquals(emptyList(), s.state.tags)
        assertEquals(emptyList(), s.state.vhosts)
    }

    @Test
    fun `a tag removed while the data is shown locks the screen on the next read and drops what was shown`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals("svc-orders", s.state.user)
        s.access.hasManagementTag = false
        s.pass(5)
        assertIs<AccessScreen.Locked>(s.state.screen)
        assertNull(s.state.user)
        assertEquals(emptyList(), s.state.vhosts)
        assertFalse(s.state.stale)
    }

    @Test
    fun `whoami ok then virtual hosts are refused keeps the last data stale with the refusal banner and Retry`() = runTest {
        val s = AccessHarness(this)
        s.open()
        s.access.vhostsFailure = AccessResult.NotAuthorised
        s.pass(5)
        assertIs<AccessScreen.Loaded>(s.state.screen)
        assertEquals("svc-orders", s.state.user, "the last data stays")
        assertEquals(listOf("/orders", "/", "/billing"), s.state.vhosts.map { it.name })
        assertTrue(s.state.stale, "dimmed and marked stale")
        assertEquals(AccessTexts.SIGN_IN_REFUSED, s.state.refusalBanner)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertNull(s.state.connectionBanner, "the broker answered: this is not a lost connection")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value)
    }

    // ---------------------------------------------------------------- RabbitMQ 3.x answered by the gateway (NotOffered)

    @Test
    fun `a gateway that answers NotOffered makes the view leave with the one-line notice and never shows a failure`() = runTest {
        val s = AccessHarness(this)
        s.access.notOffered = true
        s.open()
        s.pass(60)
        assertEquals(AccessTexts.NEEDS_4X, s.state.leaveNotice)
        assertFalse(s.state.offered)
        assertFalse(s.state.screen is AccessScreen.Failed, "NotOffered is not a failure: ${s.state.screen}")
        assertFalse(s.state.message.orEmpty().contains("4.x"), "the notice appears only as leaveNotice")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "not a lost connection")
    }

    // ---------------------------------------------------------------- D2: lost connection

    @Test
    fun `an unreachable broker on a tick keeps the data stale with the banner and Retry and never reconnects by itself`() = runTest {
        val s = AccessHarness(this)
        s.open()
        val vhosts = s.state.vhosts

        s.access.unreachable = true
        s.pass(5)

        assertEquals(LinkState.Failed("prod-eu", "Connection lost"), s.h.active.state.value)
        assertEquals("svc-orders", s.state.user, "the last data stays")
        assertEquals(vhosts, s.state.vhosts)
        assertTrue(s.state.stale)
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, s.state.connectionBanner)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertIs<AccessScreen.Loaded>(s.state.screen)
        assertEquals(emptyList<Any>(), s.h.gateway.heldNow)

        s.pass(600)
        assertEquals(1, s.h.gateway.openCalls.size, "no automatic reconnect")
        assertEquals(emptyList(), s.reconnects)
    }

    @Test
    fun `Retry after a lost connection reconnects and reads fresh data`() = runTest {
        val s = AccessHarness(this)
        s.open()
        s.access.unreachable = true
        s.pass(5)

        s.access.unreachable = false
        s.access.tags = listOf("administrator")
        s.vm.retry()
        s.settle()

        assertEquals(listOf("prod-eu"), s.reconnects)
        assertEquals(listOf("administrator"), s.state.tags)
        assertFalse(s.state.stale)
        assertNull(s.state.connectionBanner)
        assertNull(s.state.action)
    }

    @Test
    fun `an unreachable broker on the very first read is a lost connection with nothing to show`() = runTest {
        val s = AccessHarness(this)
        s.access.unreachable = true
        s.open()
        assertEquals(LinkState.Failed("prod-eu", "Connection lost"), s.h.active.state.value)
        assertIs<AccessScreen.Failed>(s.state.screen)
        assertNull(s.state.user)
        assertEquals(AccessTexts.RETRY, s.state.action)
    }

    @Test
    fun `a disconnect clears everything and says Not connected`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals("svc-orders", s.state.user, "the data was there before the disconnect")
        s.h.active.disconnect()
        s.settle()
        assertIs<AccessScreen.NotConnected>(s.state.screen)
        assertNull(s.state.user)
        assertEquals(emptyList(), s.state.vhosts)
    }

    // ---------------------------------------------------------------- Not encrypted

    @Test
    fun `a profile with TLS off shows Not encrypted, also while the data is stale`() = runTest {
        val s = AccessHarness(this, tls = false)
        s.open()
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, s.state.notEncrypted)
        assertEquals("Not encrypted", s.state.notEncrypted)
        s.access.unreachable = true
        s.pass(5)
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, s.state.notEncrypted)
        assertTrue(s.state.stale)
    }

    @Test
    fun `a profile with TLS on shows no Not encrypted`() = runTest {
        val s = AccessHarness(this, tls = true)
        s.open()
        assertNull(s.state.notEncrypted)
    }

    // ---------------------------------------------------------------- D3: only while shown, and the timer

    @Test
    fun `nothing is read while the screen is not shown, not at connect and not on the timer`() = runTest {
        val s = AccessHarness(this)
        s.connect()
        s.pass(600)
        assertEquals(emptyList(), s.requests())
        s.vm.refreshNow()
        s.settle()
        assertEquals(emptyList(), s.requests(), "a manual refresh of a hidden screen does nothing")
    }

    @Test
    fun `the screen reads again on every tick of the app-wide interval while shown and stops when hidden`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals(2, s.requests().size)
        s.pass(5)
        assertEquals(4, s.requests().size, "one more read per tick")
        s.vm.setShown(false)
        s.settle()
        val after = s.requests().size
        s.pass(600)
        assertEquals(after, s.requests().size, "hidden: no call")
    }

    @Test
    fun `a manual refresh while a read is running does nothing, otherwise it reads now`() = runTest {
        val s = AccessHarness(this)
        val gate = s.access.holdNext("whoami")
        s.open()
        s.vm.refreshNow()
        s.settle()
        assertEquals(1, s.requests().count { it == "whoami" }, "one read at a time")
        gate.release()
        s.settle()
        s.vm.refreshNow()
        s.settle()
        assertEquals(2, s.requests().count { it == "whoami" })
    }

    // ---------------------------------------------------------------- switching connection

    @Test
    fun `switching to another connection clears the old data at once and shows the new user`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals("svc-orders", s.state.user)
        val other: FakeAccessGateway = s.h.gateway.accessOf("uat-eu")
        other.user = "svc-billing"
        other.tags = listOf("management")
        other.listedVhosts = listOf("/billing")

        val gate = s.h.gateway.holdOpens("uat-eu")
        val connecting = launch { s.h.active.connect(s.h.uat) }
        s.h.gateway.awaitOpenStarted()
        s.settle()
        assertNull(s.state.user, "the previous connection's user must not show while another connects")
        assertEquals(emptyList(), s.state.vhosts)
        gate.release()
        connecting.join()
        s.settle()
        assertEquals("svc-billing", s.state.user)
        assertEquals(listOf("/billing"), s.state.vhosts.map { it.name })
    }

    @Test
    fun `a late answer of a superseded connection is dropped`() = runTest {
        val s = AccessHarness(this)
        val late = s.access.holdNext("whoami", ignoreCancellation = true)
        s.open()
        val other = s.h.gateway.accessOf("uat-eu")
        other.user = "svc-billing"
        s.h.active.connect(s.h.uat)
        s.settle()
        late.release()
        s.settle()
        assertEquals("svc-billing", s.state.user, "the answer of the old connection must not overwrite the new one")
        assertNotEquals("svc-orders", s.state.user)
    }

    // ---------------------------------------------------------------- RabbitMQ 3.x (owner decision AQ6)

    @Test
    fun `a 3-x connection is not offered and the Access view sends nothing to it, even when shown`() = runTest {
        val s = AccessHarness(this)
        s.h.gateway.brokerRuns("prod-eu", "3.13.7")
        s.open()
        s.pass(600)
        assertFalse(s.state.offered, "the drawer has no Access item on a 3.x connection")
        assertEquals(emptyList(), s.requests(), "no request to a 3.x broker, ever")
    }

    @Test
    fun `a 4-x connection is offered`() = runTest {
        val s = AccessHarness(this)
        s.h.gateway.brokerRuns("prod-eu", "4.1.0")
        s.open()
        assertTrue(s.state.offered)
    }

    @Test
    fun `a connection that becomes 3-x while Access is shown leaves with the one-line notice, clears the data and asks nothing more`() = runTest {
        val s = AccessHarness(this)
        s.open()
        assertEquals("svc-orders", s.state.user)
        s.forgetRequests()

        val other = s.h.gateway.accessOf("uat-eu")
        s.h.gateway.brokerRuns("uat-eu", "3.13.7")
        s.h.active.connect(s.h.uat)
        s.settle()
        s.pass(600)

        assertFalse(s.state.offered)
        assertEquals(AccessTexts.NEEDS_4X, s.state.leaveNotice)
        assertEquals("The Access view needs a 4.x broker.", s.state.leaveNotice)
        assertNull(s.state.user, "the data of the 4.x connection is gone")
        assertEquals(emptyList(), other.requests.toList(), "nothing was sent to the 3.x broker by the Access view")
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `the notice is one-shot and appears only if Access was open`() = runTest {
        val s = AccessHarness(this)
        s.open()
        s.h.gateway.brokerRuns("uat-eu", "3.13.7")
        s.h.active.connect(s.h.uat)
        s.settle()
        assertEquals(AccessTexts.NEEDS_4X, s.state.leaveNotice)
        s.vm.noticeShown()
        s.settle()
        assertNull(s.state.leaveNotice, "shown once, then gone")

        val closed = AccessHarness(this)
        closed.connect()
        closed.h.gateway.brokerRuns("uat-eu", "3.13.7")
        closed.h.active.connect(closed.h.uat)
        closed.settle()
        assertNull(closed.state.leaveNotice, "Access was not open: no notice")
        assertFalse(closed.state.offered)
    }

    // ---------------------------------------------------------------- close

    @Test
    fun `after close the screen reads nothing more`() = runTest {
        val s = AccessHarness(this)
        s.open()
        s.vm.close()
        s.settle()
        s.forgetRequests()
        s.pass(600)
        assertEquals(emptyList(), s.requests())
    }
}
