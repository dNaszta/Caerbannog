package dev.caerbannog.core.access

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionNotes
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.streams.RefreshInterval
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M10-03 step 2 (test developer): every state of the Permissions tab and the rules around it (brief docs/tasks/M10-03-permissions-tab.md "Rulings" 1 to 10 and
 * "Decision PD-1"; spec "Access view (M10)", "Broker-read permission", "Declared permissions"; mockup `docs/ux/Access.dc.html` frames `perm-*`; scenarios of
 * `22_access_who_am_i_and_permissions.feature` tagged `@m10-03`). Virtual time, fakes only. Contract: [PermissionsHarness]. Red until the view model is built.
 */
class PermissionsViewModelTest {

    // ---------------------------------------------------------------- before and during the connection, nothing without the tab

    @Test
    fun `before any connection the tab says Not connected and nothing is called`() = runTest {
        val s = PermissionsHarness(this)
        s.vm.setShown(true)
        s.settle()
        assertIs<PermissionsScreen.NotConnected>(s.state.screen)
        assertEquals(AccessTexts.NOT_CONNECTED, s.state.message)
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `a connected broker is not asked anything while the tab is not shown`() = runTest {
        val s = PermissionsHarness(this)
        s.connect()
        s.pass(60)
        assertEquals(emptyList(), s.requests(), "the Permissions tab reads only while it is shown (D3)")
    }

    @Test
    fun `a read that is still running shows loading, never a blank page`() = runTest {
        val s = PermissionsHarness(this)
        val gate = s.access.holdNext("whoami")
        s.open()
        assertIs<PermissionsScreen.Loading>(s.state.screen)
        assertEquals(PermissionsTexts.LOADING, s.state.message)
        assertEquals(emptyList(), s.state.rows)
        gate.release()
        s.settle()
        assertFalse(s.state.screen is PermissionsScreen.Loading)
    }

    // ---------------------------------------------------------------- the lock is decided by the tags, with no request (ruling 1)

    @Test
    fun `a user without the administrator tag is locked and no permission request is sent`() = runTest {
        val s = PermissionsHarness(this, declared = DeclaredPermissions(configure = true, write = false))
        s.access.user = "svc-explorer"
        s.access.tags = listOf("monitoring", "management")
        s.open()
        s.pass(120)
        assertIs<PermissionsScreen.Locked>(s.state.screen)
        assertEquals(PermissionsTexts.LOCKED_TITLE, s.state.message)
        assertEquals(PermissionsTexts.lockedDetail("svc-explorer"), s.state.detail)
        assertEquals(emptyList(), s.permissionRequests(), "nothing is guessed from other calls and the call would only fail (S-B: 401)")
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.state.topicRows)
        assertFalse(s.state.canChooseUser, "the user chooser is drawn for an administrator only")
        assertEquals(emptyList(), s.state.users)
        assertNull(s.state.readLabel, "no broker-read label on a locked frame")
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "the broker was not asked: the connection is not lost")
    }

    @Test
    fun `a user with no tag at all and a user the broker calls Not management user are locked too`() = runTest {
        val s = PermissionsHarness(this)
        s.access.tags = emptyList()
        s.open()
        assertIs<PermissionsScreen.Locked>(s.state.screen)
        assertEquals(emptyList(), s.permissionRequests())
        val t = PermissionsHarness(this)
        t.access.hasManagementTag = false
        t.open()
        assertIs<PermissionsScreen.Locked>(t.state.screen)
        assertEquals(emptyList(), t.permissionRequests())
    }

    @Test
    fun `a token user without the administrator tag is locked, not shown the token text`() = runTest {
        val s = PermissionsHarness(this)
        s.access.user = "jdoe"
        s.access.internalUser = false
        s.access.tags = listOf("monitoring")
        s.open()
        assertIs<PermissionsScreen.Locked>(s.state.screen)
        assertEquals(emptyList(), s.permissionRequests())
    }

    @Test
    fun `the locked frame names the user and says nothing is guessed`() = runTest {
        val s = PermissionsHarness(this)
        s.access.user = "svc-explorer"
        s.access.tags = listOf("monitoring")
        s.open()
        assertEquals("The broker gives the permission patterns only to an administrator, and svc-explorer has no administrator tag. Nothing is guessed from other calls.", PermissionsTexts.lockedDetail("svc-explorer"))
        assertTrue("administrator" in s.state.detail.orEmpty(), "detail was ${s.state.detail}")
    }

    // ---------------------------------------------------------------- declaration (ruling 9)

    @Test
    fun `a locked frame shows the declaration labelled as declared, with read always not declared`() = runTest {
        val s = PermissionsHarness(this, declared = DeclaredPermissions(configure = false, write = true))
        s.access.tags = listOf("monitoring")
        s.open()
        assertIs<PermissionsScreen.Locked>(s.state.screen)
        assertEquals(
            DeclarationView(
                label = "Declared by you, not confirmed by the broker",
                configure = "no",
                write = "yes",
                read = "not declared",
                note = "You entered this in the connection settings. The broker enforces its own permissions.",
            ),
            s.state.declaration,
        )
        assertNull(s.state.readLabel, "it must not use the wording of a broker-read result")
        assertNotEquals(PermissionsTexts.READ_FROM_BROKER, s.state.declaration?.label)
    }

    @Test
    fun `a declaration with configure yes and write no reads yes and no`() = runTest {
        val s = PermissionsHarness(this, declared = DeclaredPermissions(configure = true, write = false))
        s.access.tags = listOf("monitoring")
        s.open()
        assertEquals("yes", s.state.declaration?.configure)
        assertEquals("no", s.state.declaration?.write)
    }

    @Test
    fun `a locked frame without a declaration says so and shows no declaration block`() = runTest {
        val s = PermissionsHarness(this)
        s.access.tags = listOf("monitoring")
        s.open()
        assertNull(s.state.declaration)
        assertEquals(PermissionsTexts.LOCKED_DETAIL_NO_DECLARATION, s.state.detail)
    }

    @Test
    fun `an administrator never sees the declaration, only what the broker returned`() = runTest {
        val s = PermissionsHarness(this, declared = DeclaredPermissions(configure = false, write = true))
        s.openAndChoose("svc-orders")
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertNull(s.state.declaration)
        assertEquals(PermissionsTexts.READ_FROM_BROKER, s.state.readLabel)
    }

    // ---------------------------------------------------------------- an administrator: users, default user (ruling 6), table (rulings 7, 8)

    @Test
    fun `an administrator may choose any user the broker lists, offered by name only, alphabetical`() = runTest {
        val s = PermissionsHarness(this)
        s.access.addUser("alice")
        s.open()
        assertTrue(s.state.canChooseUser)
        assertEquals(listOf("alice", "ops-admin", "svc-billing", "svc-orders"), s.state.users.map { it.name })
        assertTrue(s.state.users.none { it.hidden })
        assertEquals("4 of 4 users", s.state.userCountText)
        assertEquals(1, s.requests().count { it == "users" })
    }

    @Test
    fun `the default user is the connection user when it is in the list`() = runTest {
        val s = PermissionsHarness(this)
        s.open()
        assertEquals("ops-admin", s.state.chosenUser)
        assertEquals(listOf("permissions:ops-admin"), s.requests().filter { it.startsWith("permissions:") })
    }

    @Test
    fun `otherwise the default user is the first name by alphabet`() = runTest {
        val s = PermissionsHarness(this)
        s.access.internalUsers = listOf("svc-orders", "svc-billing")
        s.open()
        assertEquals("svc-billing", s.state.chosenUser)
        assertEquals(listOf("permissions:svc-billing"), s.requests().filter { it.startsWith("permissions:") })
    }

    @Test
    fun `choosing a user shows the patterns exactly as the broker returned them, labelled as read from the broker`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertEquals("svc-orders", s.state.chosenUser)
        assertEquals("Permissions of svc-orders", s.state.heading)
        assertEquals(PermissionsTexts.READ_FROM_BROKER, s.state.readLabel)
        assertNull(s.state.message)
        val orders = s.state.rows.single { it.vhost == "/orders" }
        assertEquals(s.cell("^queue\\..*"), orders.configure)
        assertEquals(s.cell("^ex\\..*"), orders.write)
        assertEquals(s.cell(".*"), orders.read)
        assertFalse(s.state.stale)
        assertNull(s.state.action)
    }

    @Test
    fun `a virtual host without a row is drawn with no permission in all three cells, rows alphabetical`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        assertEquals(listOf("/", "/billing", "/orders"), s.state.rows.map { it.vhost })
        val billing = s.state.rows.single { it.vhost == "/billing" }
        for (c in listOf(billing.configure, billing.write, billing.read)) {
            assertEquals(PatternReading.NoPermission, c.reading)
            assertEquals(PermissionsTexts.NO_PERMISSION, PermissionsTexts.shown(c))
        }
    }

    @Test
    fun `a permission row for a virtual host the broker did not list for this user is shown, never dropped`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setPermissions("svc-orders", "/staging", "^s$", "", ".*")
        s.openAndChoose("svc-orders")
        assertEquals(listOf("/", "/billing", "/orders", "/staging"), s.state.rows.map { it.vhost })
        assertEquals(s.cell("^s$"), s.state.rows.single { it.vhost == "/staging" }.configure)
    }

    @Test
    fun `an unreadable virtual host list still shows the broker's permission rows`() = runTest {
        val s = PermissionsHarness(this)
        s.access.vhostsFailure = AccessResult.Unexpected("HTTP 500")
        s.openAndChoose("svc-orders")
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertEquals(listOf("/orders"), s.state.rows.map { it.vhost })
    }

    @Test
    fun `an empty pattern reads as no permission and the empty text is kept, a caret-dollar pattern is a pattern`() = runTest {
        data class Case(val configure: String, val write: String, val read: String, val kind: String, val reading: PatternReading, val shown: String)
        val cases = listOf(
            Case("", ".*", ".*", "configure", PatternReading.NoPermission, "no permission"),
            Case("^$", ".*", ".*", "configure", PatternReading.HasPattern("^$"), "^$"),
            Case(".*", "", ".*", "write", PatternReading.NoPermission, "no permission"),
            Case(".*", ".*", "", "read", PatternReading.NoPermission, "no permission"),
        )
        for (c in cases) {
            val s = PermissionsHarness(this)
            s.access.setPermissions("svc-orders", "/orders", c.configure, c.write, c.read)
            s.openAndChoose("svc-orders")
            val row = s.state.rows.single { it.vhost == "/orders" }
            val cell = when (c.kind) { "configure" -> row.configure; "write" -> row.write; else -> row.read }
            assertEquals(c.reading, cell.reading, "$c")
            assertEquals(c.shown, PermissionsTexts.shown(cell), "$c")
            assertEquals(c.configure.takeIf { c.kind == "configure" } ?: c.write.takeIf { c.kind == "write" } ?: c.read, cell.text, "the model keeps the raw text: $c")
            assertFalse(cell.hidden)
        }
    }

    @Test
    fun `topic permissions are listed per exchange with their write and read pattern`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setTopicPermissions("svc-orders", "/orders", "orders.events", "", "^b\\.")
        s.openAndChoose("svc-orders")
        assertEquals(
            listOf(
                TopicPermissionRow("/orders", "amq.topic", s.cell("^sensors\\..*"), s.cell(".*")),
                TopicPermissionRow("/orders", "orders.events", s.cell(""), s.cell("^b\\.")),
            ),
            s.state.topicRows,
        )
    }

    @Test
    fun `a user with no permission row and no topic row gets the empty frame, not an error`() = runTest {
        val s = PermissionsHarness(this)
        s.access.addUser("svc-idle")
        s.openAndChoose("svc-idle")
        assertIs<PermissionsScreen.Empty>(s.state.screen)
        assertEquals("svc-idle has no permissions on any virtual host.", s.state.message)
        assertEquals(PermissionsTexts.empty("svc-idle"), s.state.message)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.state.topicRows)
        assertNull(s.state.action, "not an error: no Retry")
        assertTrue(s.state.canChooseUser, "the chooser stays")
    }

    @Test
    fun `a user with only a topic permission row is not empty`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setTopicPermissions("svc-topic", "/orders", "amq.topic", ".*", ".*")
        s.openAndChoose("svc-topic")
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertEquals(1, s.state.topicRows.size)
    }

    // ---------------------------------------------------------------- a token user, a token administrator (ruling 4, PD-1)

    @Test
    fun `a token administrator lands on its own name, the broker answers 404 and the token text shows, no error`() = runTest {
        val s = PermissionsHarness(this)
        s.access.user = "jdoe"
        s.access.internalUser = false
        s.access.tags = listOf("administrator")
        s.open()
        assertEquals("jdoe", s.state.chosenUser)
        assertIs<PermissionsScreen.TokenUser>(s.state.screen)
        assertEquals("No pattern table for a token user.", s.state.message)
        assertEquals(PermissionsTexts.tokenDetail("jdoe"), s.state.detail)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.state.topicRows)
        assertNull(s.state.action, "never an error")
        assertNull(s.state.readErrorBanner)
        assertTrue("jdoe" in s.state.users.map { it.name }, "its own name is an extra entry: ${s.state.users}")
        assertTrue(s.state.users.map { it.name }.containsAll(listOf("ops-admin", "svc-billing", "svc-orders")))
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value)
        assertTrue("permissions:jdoe" in s.requests(), "the broker is asked and answers 404: ${s.requests()}")
    }

    @Test
    fun `a token administrator may choose an internal user and then sees that user's patterns`() = runTest {
        val s = PermissionsHarness(this)
        s.access.user = "jdoe"
        s.access.internalUser = false
        s.access.tags = listOf("administrator")
        s.openAndChoose("svc-orders")
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertEquals(s.cell("^queue\\..*"), s.state.rows.single { it.vhost == "/orders" }.configure)
        // choosing its own name again shows the token note again
        s.vm.chooseUser("jdoe")
        s.settle()
        assertIs<PermissionsScreen.TokenUser>(s.state.screen)
    }

    @Test
    fun `an internal administrator whose own name is listed gets no extra entry`() = runTest {
        val s = PermissionsHarness(this)
        s.open()
        assertEquals(1, s.state.users.count { it.name == "ops-admin" })
    }

    @Test
    fun `an unknown name answers 404 and reads as the token text, never an error`() = runTest {
        val s = PermissionsHarness(this)
        s.access.internalUsers = listOf("ops-admin", "svc-billing")
        s.open()
        s.vm.chooseUser("no-such-user")
        s.settle()
        // a key that is not an offered option changes nothing
        assertEquals("ops-admin", s.state.chosenUser)
    }

    // ---------------------------------------------------------------- the chooser and its filters (rulings 5, 10)

    @Test
    fun `the user filter narrows the chooser by part of the name and counts N of M, and sends nothing`() = runTest {
        val s = PermissionsHarness(this)
        s.open()
        s.forgetRequests()
        s.vm.setUserFilter("svc-")
        s.settle()
        assertEquals(listOf("svc-billing", "svc-orders"), s.state.users.map { it.name })
        assertEquals("2 of 3 users", s.state.userCountText)
        assertEquals("svc-", s.state.userFilter)
        assertEquals(emptyList(), s.requests(), "filtering is local")
        s.vm.setUserFilter("SVC-B")
        s.settle()
        assertEquals(listOf("svc-billing"), s.state.users.map { it.name }, "the filter ignores case")
        assertEquals("1 of 3 users", s.state.userCountText)
    }

    @Test
    fun `a user whose name looks like a token is offered once as hidden, cannot be chosen, and its text is nowhere`() = runTest {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl"
        val s = PermissionsHarness(this)
        s.access.addUser(jwt)
        s.open()
        val hidden = s.state.users.filter { it.hidden }
        assertEquals(1, hidden.size)
        val index = s.state.users.indexOf(hidden.single())
        assertEquals(SecretGuard.HIDDEN, hidden.single().name)
        assertEquals("hidden:$index", hidden.single().key, "keyed by its position, 0-based, like the hidden virtual hosts of M10-02b")
        s.forgetRequests()
        s.vm.chooseUser(hidden.single().key)
        s.settle()
        assertEquals("ops-admin", s.state.chosenUser, "a hidden option is disabled")
        assertEquals(emptyList(), s.requests(), "no request can carry the real name: it was never kept")
        assertFalse(s.state.toString().contains("eyJ"), "the token text is on the state: ${s.state}")
    }

    @Test
    fun `two hidden names are two disabled options with different keys`() = runTest {
        val s = PermissionsHarness(this)
        s.access.addUser("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl")
        s.access.addUser("Bearer abcdefgh")
        s.open()
        val hidden = s.state.users.filter { it.hidden }
        assertEquals(2, hidden.size)
        assertEquals(2, hidden.map { it.key }.toSet().size)
    }

    @Test
    fun `a pattern that holds the held password or looks like a token is shown as hidden and carries hidden true`() = runTest {
        val s = PermissionsHarness(this)
        s.access.heldSecret = "S3cret-PW-4711-xyz"
        s.access.setPermissions("svc-orders", "/orders", "token=abc", ".*", "^S3cret-PW-4711-xyz\$")
        s.access.setTopicPermissions("svc-orders", "/orders", "ex-S3cret-PW-4711-xyz", "password=x", ".*")
        s.openAndChoose("svc-orders")
        val row = s.state.rows.single { it.vhost == "/orders" }
        assertTrue(row.configure.hidden && row.read.hidden)
        assertEquals(SecretGuard.HIDDEN, PermissionsTexts.shown(row.configure))
        assertFalse(row.write.hidden)
        val topic = s.state.topicRows.single { it.exchange == SecretGuard.HIDDEN }
        assertTrue(topic.write.hidden)
        assertFalse(s.state.toString().contains("S3cret"))
        assertFalse(s.state.toString().contains("token=abc"))
    }

    @Test
    fun `the virtual host filter narrows both tables by part of the name, ignores case, and sends nothing`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setTopicPermissions("svc-orders", "/billing", "amq.topic", ".*", ".*")
        s.openAndChoose("svc-orders")
        s.forgetRequests()
        s.vm.setVhostFilter("BILL")
        s.settle()
        assertEquals(listOf("/billing"), s.state.rows.map { it.vhost })
        assertEquals(listOf("/billing"), s.state.topicRows.map { it.vhost })
        assertEquals("BILL", s.state.vhostFilter)
        assertEquals(emptyList(), s.requests())
        s.vm.setVhostFilter("")
        s.settle()
        assertEquals(3, s.state.rows.size)
    }

    @Test
    fun `the choice is per visit, leaving the tab and coming back starts at the default user again`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        s.vm.setUserFilter("svc")
        s.vm.setShown(false)
        s.settle()
        s.vm.setShown(true)
        s.settle()
        assertEquals("ops-admin", s.state.chosenUser)
        assertEquals("", s.state.userFilter)
    }

    // ---------------------------------------------------------------- errors, stale, banners (D2, ruling 10)

    @Test
    fun `an unreadable permission list on the first read says so with the broker text and Retry, and the chooser stays`() = runTest {
        val s = PermissionsHarness(this)
        s.access.permissionsFailure = AccessResult.Unexpected("HTTP 500")
        s.open()
        assertEquals(PermissionsScreen.Failed("HTTP 500"), s.state.screen)
        assertEquals("The permissions could not be read.", s.state.message)
        assertEquals("Broker text: HTTP 500", s.state.detail)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertTrue(s.state.canChooseUser)
        assertTrue(s.state.users.isNotEmpty())
        assertEquals(emptyList(), s.state.rows)
        assertEquals(LinkState.Connected("prod-eu"), s.h.active.state.value, "an answered error is not a lost connection")
    }

    @Test
    fun `Retry after a failed first read reads the user list and the permissions again`() = runTest {
        val s = PermissionsHarness(this)
        s.access.permissionsFailure = AccessResult.Unexpected("HTTP 500")
        s.open()
        s.access.permissionsFailure = null
        s.forgetRequests()
        s.vm.retry()
        s.settle()
        assertTrue("users" in s.requests(), "Retry reads the user list again: ${s.requests()}")
        assertTrue(s.requests().any { it.startsWith("permissions:") })
        assertFalse(s.state.screen is PermissionsScreen.Failed)
        assertNull(s.state.action)
    }

    // ---------------------------------------------------------------- M10-03b item 1: no selectable user

    @Test
    fun `an administrator whose user list is empty gets the Failed frame with the no-user message, Retry and no permission request`() = runTest {
        val s = PermissionsHarness(this)
        s.access.internalUsers = emptyList()
        s.open()
        assertIs<PermissionsScreen.Failed>(s.state.screen)
        assertEquals(PermissionsTexts.NO_USER, s.state.message, "the owner's wording is the message of the frame")
        assertEquals("The broker lists no user to choose.", PermissionsTexts.NO_USER)
        assertNull(s.state.detail, "it is not a broker text: no 'Broker text:' line")
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertNull(s.state.chosenUser)
        assertEquals(emptyList(), s.state.rows)
        assertEquals(emptyList(), s.requests().filter { it.startsWith("permissions:") }, "no permission request without a user to ask for")
    }

    @Test
    fun `an administrator whose every listed name is hidden gets the same frame and no permission request`() = runTest {
        val s = PermissionsHarness(this)
        s.access.internalUsers = listOf("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyMiJ9.c2lnbmF0dXJlMg")
        s.open()
        assertIs<PermissionsScreen.Failed>(s.state.screen)
        assertEquals(PermissionsTexts.NO_USER, s.state.message)
        assertNull(s.state.detail)
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertEquals(emptyList(), s.requests().filter { it.startsWith("permissions:") })
        assertFalse(s.state.toString().contains("eyJ"), "the token text is on the state: ${s.state}")
    }

    @Test
    fun `Retry after the no-user frame reads the user list again and recovers when a user appears`() = runTest {
        val s = PermissionsHarness(this)
        s.access.internalUsers = emptyList()
        s.open()
        s.access.addUser("svc-orders")
        s.forgetRequests()
        s.vm.retry()
        s.settle()
        assertTrue("users" in s.requests(), "Retry reads the user list again: ${s.requests()}")
        assertEquals("svc-orders", s.state.chosenUser)
        assertFalse(s.state.screen is PermissionsScreen.Failed)
    }

    @Test
    fun `an unreadable user list is a failed screen with the broker text and Retry`() = runTest {
        val s = PermissionsHarness(this)
        s.access.usersFailure = AccessResult.Unexpected("HTTP 503")
        s.open()
        assertEquals(PermissionsScreen.Failed("HTTP 503"), s.state.screen)
        assertEquals("Broker text: HTTP 503", s.state.detail)
        assertEquals(AccessTexts.RETRY, s.state.action)
    }

    @Test
    fun `an unreadable permission list keeps the last patterns, marked stale, with the reason and Retry`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        s.access.permissionsFailure = AccessResult.Unexpected("HTTP 500")
        s.vm.refreshNow()
        s.settle()
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertTrue(s.state.stale)
        assertEquals(s.cell("^queue\\..*"), s.state.rows.single { it.vhost == "/orders" }.configure, "the last patterns stay")
        val banner = s.state.readErrorBanner
        assertTrue(banner != null && PermissionsTexts.ERROR in banner && AccessTexts.brokerText("HTTP 500") in banner, "banner was $banner")
        assertEquals(AccessTexts.RETRY, s.state.action)
        assertNull(s.state.connectionBanner)
        assertNull(s.state.refusalBanner)
        // recovery clears it
        s.access.permissionsFailure = null
        s.vm.retry()
        s.settle()
        assertFalse(s.state.stale)
        assertNull(s.state.readErrorBanner)
        assertNull(s.state.action)
    }

    @Test
    fun `a refused sign-in keeps the last data stale with the refusal banner, and without data is a failed screen`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        s.access.permissionsFailure = AccessResult.NotAuthorised
        s.vm.refreshNow()
        s.settle()
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertTrue(s.state.stale)
        assertEquals(AccessTexts.SIGN_IN_REFUSED, s.state.refusalBanner)
        assertNull(s.state.readErrorBanner)
        assertEquals(AccessTexts.RETRY, s.state.action)
        val t = PermissionsHarness(this)
        t.access.permissionsFailure = AccessResult.NotAuthorised
        t.open()
        assertEquals(PermissionsScreen.Failed(AccessTexts.SIGN_IN_REFUSED), t.state.screen)
        assertNull(t.state.connectionBanner)
    }

    @Test
    fun `a lost connection keeps the last patterns stale with the Connection lost banner, and does not reconnect by itself`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        s.access.unreachable = true
        s.pass(5)
        assertIs<PermissionsScreen.Loaded>(s.state.screen)
        assertTrue(s.state.stale)
        assertEquals(ConnectionFailureReason.CONNECTION_LOST, s.state.connectionBanner)
        assertTrue(s.state.rows.isNotEmpty(), "the last data stays")
        assertEquals(AccessTexts.RETRY, s.state.action)
        s.pass(300)
        assertEquals(emptyList(), s.reconnects)
        s.access.unreachable = false
        s.vm.retry()
        s.settle()
        assertEquals(listOf("prod-eu"), s.reconnects)
        assertFalse(s.state.stale)
        assertNull(s.state.connectionBanner)
    }

    @Test
    fun `a profile with TLS off shows Not encrypted, a TLS profile does not`() = runTest {
        val plain = PermissionsHarness(this, tls = false)
        plain.open()
        assertEquals(ConnectionNotes.NOT_ENCRYPTED, plain.state.notEncrypted)
        val secure = PermissionsHarness(this, tls = true)
        secure.open()
        assertNull(secure.state.notEncrypted)
    }

    @Test
    fun `a known 3x broker is not offered and nothing is shown as a failure`() = runTest {
        val s = PermissionsHarness(this)
        s.access.notOffered = true
        s.open()
        assertFalse(s.state.screen is PermissionsScreen.Failed)
        assertNull(s.state.readErrorBanner)
        assertEquals(emptyList(), s.state.rows)
    }

    // ---------------------------------------------------------------- refresh (D3), cancel, a late answer

    @Test
    fun `the tab re-reads the chosen user at the app-wide interval and not the user list`() = runTest {
        val s = PermissionsHarness(this, refresh = RefreshInterval.Every5)
        s.openAndChoose("svc-orders")
        s.forgetRequests()
        s.pass(5)
        assertTrue("permissions:svc-orders" in s.requests(), "requests: ${s.requests()}")
        assertFalse("users" in s.requests(), "the user list is re-read on Retry only: ${s.requests()}")
        s.access.setPermissions("svc-orders", "/orders", "^new$", "^ex\\..*", ".*")
        s.pass(5)
        assertEquals(s.cell("^new$"), s.state.rows.single { it.vhost == "/orders" }.configure)
    }

    @Test
    fun `leaving the tab cancels and no request follows after setShown false`() = runTest {
        val s = PermissionsHarness(this, refresh = RefreshInterval.Every5)
        s.openAndChoose("svc-orders")
        s.vm.setShown(false)
        s.settle()
        s.forgetRequests()
        s.pass(120)
        assertEquals(emptyList(), s.requests())
    }

    @Test
    fun `a late answer for the user that was chosen before does not replace the table of the user chosen after`() = runTest {
        val s = PermissionsHarness(this)
        s.open()
        s.access.setPermissions("svc-billing", "/billing", "^b$", "^b$", "^b$")
        val gate = s.access.holdNext("permissions")
        s.vm.chooseUser("svc-orders")
        s.settle()
        s.vm.chooseUser("svc-billing")
        s.settle()
        gate.release()
        s.settle()
        assertEquals("svc-billing", s.state.chosenUser)
        assertEquals(s.cell("^b$"), s.state.rows.single { it.vhost == "/billing" }.configure)
        assertTrue(s.state.rows.none { it.configure.text == "^queue\\..*" }, "rows of the earlier choice leaked: ${s.state.rows}")
        assertEquals("Permissions of svc-billing", s.state.heading)
    }

    // ---------------------------------------------------------------- read only, safety (ruling 2)

    @Test
    fun `the gateway has reads only and the view model has no method that changes anything`() = runTest {
        val gatewayMethods = AccessGateway::class.java.methods.map { it.name }.toSet()
        assertEquals(setOf("whoAmI", "virtualHosts", "users", "permissionsOf"), gatewayMethods, "a new gateway method needs a decision")
        val allowed = setOf("getState", "getUpdatedAtMillis", "setShown", "refreshNow", "retry", "chooseUser", "setUserFilter", "setVhostFilter", "close")
        val own = PermissionsViewModel::class.java.methods.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.declaringClass == PermissionsViewModel::class.java }.map { it.name }
        assertTrue(own.all { it in allowed }, "unexpected public methods on the Permissions view model: ${own.filter { it !in allowed }}")
    }

    @Test
    fun `no state field ever holds a password, a hash or the tags of a user`() = runTest {
        val s = PermissionsHarness(this)
        s.access.addUser("svc-reports")
        s.openAndChoose("svc-orders")
        val shown = s.state.toString()
        for (word in listOf("password", "hash", "hashing", "rabbit_password")) assertFalse(word in shown.lowercase(), "'$word' on the state: $shown")
        assertNotNull(s.state.users.firstOrNull { it.name == "svc-reports" })
    }
}
