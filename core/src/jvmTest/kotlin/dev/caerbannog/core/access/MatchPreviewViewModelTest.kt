package dev.caerbannog.core.access

import dev.caerbannog.core.connections.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M10-04 part B, step 2 (test developer): the form of the match preview and its verdict (brief docs/tasks/M10-04-access-match-preview.md items 6 to 8, PD-3, PD-4;
 * mockup frames `perm-admin`, `perm-verdicts`; scenarios tagged `@m10-04`). Virtual time, fakes only. The matcher is the real one (RE2/J, part A, accepted): this
 * test is about the form, the texts and the rules around the verdict, not about regular expressions.
 *
 * CONTRACT USED (stub in core commonMain, built in step 3): `MatchPreviewViewModel(input, scope, heldSecret, matcher)` with `state`, `chooseAction`, `chooseVhost`,
 * `setName`, `setRoutingKey`, `check`; `MatchPreviewState`, `MatchVerdictView`, `MatchInput`, `MatchPreviewTexts`; `PermissionsViewModel.matchInput`.
 * Red until built: every test fails with NotImplementedError from the stub.
 *
 * Sentences are pinned as drawn in the mockup. Where the mockup draws no sentence (a topic pattern that does not match, an empty routing key, permissions that were
 * never loaded) the test pins what is certain (title, which pattern and name are named) and the architect is asked in the step 2 report.
 */
class MatchPreviewViewModelTest {

    private class World(val test: TestScope, permissions: UserPermissions? = permissions(), user: String = "svc-orders", secret: String? = null) {
        val input = MutableStateFlow(
            MatchInput(
                user = user,
                source = permissions?.let { PermissionsSource.Available(it) } ?: PermissionsSource.NotAvailable(PermissionsUnavailable.NotLoaded),
                vhosts = listOf("/billing", "/orders"),
                link = LinkState.Connected("prod-eu"),
            ),
        )
        val vm = MatchPreviewViewModel(input, test.backgroundScope, heldSecret = { secret })
        val state: MatchPreviewState get() = vm.state.value
        val verdict: MatchVerdictView get() = assertNotNull(state.verdict, "no verdict is shown; state is $state")

        fun settle() = test.runCurrent()

        fun fill(name: String, key: String? = null, vhost: String = "/orders", action: MatchAction = MatchAction.Publish) {
            vm.chooseAction(action)
            vm.chooseVhost(vhost)
            vm.setName(name)
            if (key != null) vm.setRoutingKey(key)
            settle()
        }

        fun check() {
            vm.check()
            settle()
        }

        fun ask(name: String, key: String? = null, vhost: String = "/orders", action: MatchAction = MatchAction.Publish) {
            fill(name, key, vhost, action)
            check()
        }

        fun give(permissions: UserPermissions?, user: String = "svc-orders") {
            input.value = input.value.copy(
                user = user,
                source = permissions?.let { PermissionsSource.Available(it) } ?: PermissionsSource.NotAvailable(PermissionsUnavailable.NotLoaded),
            )
            settle()
        }
    }

    // ---------------------------------------------------------------- the form

    @Test
    fun `the form starts on publish with the Exchange label, a routing key box, the hosts of the table and no verdict`() = runTest {
        val w = World(this)
        w.settle()
        assertEquals(MatchAction.Publish, w.state.action)
        assertEquals(MatchPreviewTexts.NAME_PUBLISH, w.state.nameLabel)
        assertEquals("Exchange", w.state.nameLabel)
        assertTrue(w.state.showRoutingKey)
        assertEquals(listOf("/billing", "/orders"), w.state.vhosts)
        assertEquals("/billing", w.state.vhost, "the first host until one is chosen")
        assertEquals("", w.state.name)
        assertNull(w.state.verdict, "the mockup draws no verdict before Check")
    }

    @Test
    fun `the label of the name box follows the action and the routing key is for publish only (PD-3)`() = runTest {
        val w = World(this)
        w.vm.chooseAction(MatchAction.Read)
        w.settle()
        assertEquals("Queue", w.state.nameLabel)
        assertFalse(w.state.showRoutingKey)
        w.vm.chooseAction(MatchAction.Configure)
        w.settle()
        assertEquals("Name", w.state.nameLabel)
        assertFalse(w.state.showRoutingKey)
        w.vm.chooseAction(MatchAction.Publish)
        w.settle()
        assertEquals("Exchange", w.state.nameLabel)
        assertTrue(w.state.showRoutingKey)
    }

    @Test
    fun `what is typed is kept in the form`() = runTest {
        val w = World(this)
        w.fill("ex.orders", "orders.created", "/orders")
        assertEquals("ex.orders", w.state.name)
        assertEquals("orders.created", w.state.routingKey)
        assertEquals("/orders", w.state.vhost)
    }

    @Test
    fun `a host that is not in the list cannot be chosen`() = runTest {
        val w = World(this)
        w.vm.chooseVhost("/nowhere")
        w.settle()
        assertEquals("/billing", w.state.vhost)
    }

    @Test
    fun `without a host in the table there is no host chosen and Check says nothing`() = runTest {
        val w = World(this)
        w.input.value = w.input.value.copy(vhosts = emptyList())
        w.settle()
        assertNull(w.state.vhost)
        w.ask("ex.orders")
        assertNull(w.state.verdict, "nothing can be asked without a virtual host")
    }

    // ---------------------------------------------------------------- the four verdicts as drawn

    @Test
    fun `would match is drawn as the mockup draws it`() = runTest {
        val w = World(this, permissions(write = cell("^(ex|queue)\\..*")))
        w.ask("ex.orders")
        assertEquals(VerdictIcon.Match, w.verdict.icon)
        assertEquals("Would match.", w.verdict.title)
        assertEquals("The write pattern ^(ex|queue)\\..* matches ex.orders on /orders.", w.verdict.sentence)
        assertEquals("Patterns read from the broker.", w.verdict.source)
        assertEquals("Prediction from the patterns above; the broker decides. Check sends nothing to the broker.", w.verdict.note)
    }

    @Test
    fun `would not match names the pattern that did not match`() = runTest {
        val w = World(this, permissions(vhost = "/billing", write = cell("^invoices$")))
        w.ask("ex.orders", vhost = "/billing")
        assertEquals(VerdictIcon.NoMatch, w.verdict.icon)
        assertEquals("Would not match.", w.verdict.title)
        assertEquals("The write pattern ^invoices$ does not match ex.orders on /billing.", w.verdict.sentence)
        assertEquals(MatchPreviewTexts.SOURCE_BROKER, w.verdict.source)
        assertEquals(MatchPreviewTexts.NOTE, w.verdict.note)
    }

    @Test
    fun `a pattern that cannot be read is drawn as the mockup draws it and nothing is guessed`() = runTest {
        val w = World(this, permissions(write = cell("([unclosed")))
        w.ask("ex.orders")
        assertEquals(VerdictIcon.Unreadable, w.verdict.icon)
        assertEquals("Pattern could not be read.", w.verdict.title)
        assertEquals("The write pattern ([unclosed is not a pattern this app can apply. No answer is guessed.", w.verdict.sentence)
        assertEquals(MatchPreviewTexts.NOTE, w.verdict.note)
    }

    @Test
    fun `read and configure name their own kind`() = runTest {
        val w = World(this, permissions(read = cell("^queue\\..*"), configure = cell("^queue\\..*")))
        w.ask("queue.orders", action = MatchAction.Read)
        assertEquals("The read pattern ^queue\\..* matches queue.orders on /orders.", w.verdict.sentence)
        w.ask("queue.orders", action = MatchAction.Configure)
        assertEquals("The configure pattern ^queue\\..* matches queue.orders on /orders.", w.verdict.sentence)
        w.ask("other", action = MatchAction.Configure)
        assertEquals("Would not match.", w.verdict.title)
        assertEquals("The configure pattern ^queue\\..* does not match other on /orders.", w.verdict.sentence)
    }

    // ---------------------------------------------------------------- the topic permission

    @Test
    fun `a topic permission that matches is the topic verdict and names the topic pattern, the exchange and the routing key`() = runTest {
        val w = World(this, permissions(write = cell(".*"), topic = listOf(topicRow("amq.topic", write = cell("^orders\\..*")))))
        w.ask("amq.topic", "orders.created")
        assertEquals(VerdictIcon.Match, w.verdict.icon)
        assertEquals("Would match (topic permission).", w.verdict.title)
        assertEquals("The topic write pattern ^orders\\..* of amq.topic matches the routing key orders.created.", w.verdict.sentence)
        assertEquals(MatchPreviewTexts.SOURCE_BROKER, w.verdict.source)
    }

    @Test
    fun `a topic permission that does not match is would not match and names the topic pattern`() = runTest {
        val w = World(this, permissions(write = cell(".*"), topic = listOf(topicRow("amq.topic", write = cell("^sensors\\..*")))))
        w.ask("amq.topic", "alarms.fire")
        assertEquals(VerdictIcon.NoMatch, w.verdict.icon)
        assertEquals("Would not match.", w.verdict.title)
        for (part in listOf("topic", "^sensors\\..*", "amq.topic", "alarms.fire")) assertTrue(part in w.verdict.sentence, "the sentence must name '$part': ${w.verdict.sentence}")
    }

    @Test
    fun `an empty routing key against a topic pattern is asked as an empty key and the topic pattern decides`() = runTest {
        val w = World(this, permissions(write = cell(".*"), topic = listOf(topicRow("amq.topic", write = cell("^sensors\\..*")))))
        w.ask("amq.topic")
        assertEquals("Would not match.", w.verdict.title)
        assertTrue("^sensors\\..*" in w.verdict.sentence, w.verdict.sentence)
        assertTrue("amq.topic" in w.verdict.sentence, w.verdict.sentence)
    }

    @Test
    fun `an exchange without a topic row is limited by the write pattern only`() = runTest {
        val w = World(this, permissions(write = cell(".*"), topic = listOf(topicRow("amq.topic", write = cell("^sensors\\..*")))))
        w.ask("ex.orders", "anything")
        assertEquals("Would match.", w.verdict.title)
    }

    // ---------------------------------------------------------------- the default exchange

    @Test
    fun `an empty exchange is the default exchange and the box shows amq default after Check, never an empty box`() = runTest {
        val w = World(this, permissions(write = cell("^amq\\.default$")))
        w.ask("")
        assertEquals("amq.default", w.state.name)
        assertEquals("Would match.", w.verdict.title)
        assertEquals("The write pattern ^amq\\.default$ matches amq.default on /orders.", w.verdict.sentence)
    }

    @Test
    fun `a pattern that fits the queue name does not allow the default exchange`() = runTest {
        val w = World(this, permissions(write = cell("^queue$")))
        w.ask("")
        assertEquals("Would not match.", w.verdict.title)
        assertEquals("amq.default", w.state.name)
    }

    @Test
    fun `an empty name for read or configure is not turned into the default exchange`() = runTest {
        val w = World(this, permissions(read = cell("^$")))
        w.ask("", action = MatchAction.Read)
        assertEquals("", w.state.name, "amq.default is the default EXCHANGE of a publish, not a queue name")
    }

    // ---------------------------------------------------------------- PD-4, hidden, locked, token

    @Test
    fun `a virtual host without a permission says who has none and where, with would not match (PD-4)`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders", vhost = "/billing")
        assertEquals(VerdictIcon.NoMatch, w.verdict.icon)
        assertEquals("Would not match.", w.verdict.title)
        assertEquals("svc-orders has no permission on /billing.", w.verdict.sentence)
    }

    @Test
    fun `a hidden pattern is never applied and never shown as text`() = runTest {
        val w = World(this, permissions(write = cell("password=hunter2", hidden = true)))
        w.ask("hidden-exchange")
        assertEquals(VerdictIcon.Unreadable, w.verdict.icon)
        assertEquals("Pattern could not be read.", w.verdict.title)
        assertEquals("The write pattern hidden is not a pattern this app can apply. No answer is guessed.", w.verdict.sentence)
        assertFalse("hunter2" in w.verdict.toString())
    }

    @Test
    fun `a locked tab gives permissions not readable with the drawn sentence and no source`() = runTest {
        val w = World(this)
        w.input.value = w.input.value.copy(source = PermissionsSource.NotAvailable(PermissionsUnavailable.Locked))
        w.settle()
        w.ask("ex.orders")
        assertEquals(VerdictIcon.NotReadable, w.verdict.icon)
        assertEquals("Permissions not readable.", w.verdict.title)
        assertEquals("Only an administrator can read the patterns of a user, so there is nothing to check against.", w.verdict.sentence)
        assertNull(w.verdict.source, "no pattern was applied, so no pattern was read from anywhere")
        assertEquals(MatchPreviewTexts.NOTE, w.verdict.note)
    }

    @Test
    fun `a locked tab has no host list and Check still says permissions not readable, no host is needed for that`() = runTest {
        val w = World(this)
        w.input.value = w.input.value.copy(source = PermissionsSource.NotAvailable(PermissionsUnavailable.Locked), vhosts = emptyList())
        w.settle()
        assertNull(w.state.vhost)
        w.ask("ex.orders")
        assertEquals("Permissions not readable.", w.verdict.title)
    }

    @Test
    fun `a token user gives permissions not readable with the sentence of the Permissions tab`() = runTest {
        val w = World(this)
        w.input.value = w.input.value.copy(source = PermissionsSource.NotAvailable(PermissionsUnavailable.TokenUser))
        w.settle()
        w.ask("ex.orders")
        assertEquals("Permissions not readable.", w.verdict.title)
        assertEquals(PermissionsTexts.tokenDetail("svc-orders"), w.verdict.sentence)
        assertNull(w.verdict.source)
    }

    @Test
    fun `permissions that are not loaded give permissions not readable and no crash`() = runTest {
        val w = World(this, permissions = null)
        w.ask("ex.orders")
        assertEquals(VerdictIcon.NotReadable, w.verdict.icon)
        assertEquals("Permissions not readable.", w.verdict.title)
        assertNull(w.verdict.source)
    }

    // ---------------------------------------------------------------- typed secrets

    @Test
    fun `a typed name that is the connection password is not echoed in the verdict`() = runTest {
        val w = World(this, permissions(write = cell(".*")), secret = "s3cret-token-value")
        w.ask("s3cret-token-value")
        assertEquals("Would match.", w.verdict.title)
        assertFalse("s3cret-token-value" in w.verdict.toString(), "the verdict must not echo the secret: ${w.verdict}")
        assertTrue("hidden" in w.verdict.sentence, w.verdict.sentence)
    }

    @Test
    fun `a typed routing key that looks like a token is not echoed in the verdict`() = runTest {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.c2lnbmF0dXJl"
        val w = World(this, permissions(write = cell(".*"), topic = listOf(topicRow("amq.topic", write = cell(".*")))))
        w.ask("amq.topic", jwt)
        assertFalse(jwt in w.verdict.toString() || "eyJ" in w.verdict.toString(), "the verdict must not echo the token: ${w.verdict}")
        assertTrue("hidden" in w.verdict.sentence, w.verdict.sentence)
    }

    @Test
    fun `an ordinary name is echoed as typed`() = runTest {
        val w = World(this, permissions(write = cell(".*")), secret = "s3cret-token-value")
        w.ask("ex.orders")
        assertTrue("ex.orders" in w.verdict.sentence)
    }

    // ---------------------------------------------------------------- the verdict is cleared (D2)

    @Test
    fun `the verdict is cleared when the chosen user changes`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders")
        assertNotNull(w.state.verdict)
        w.give(permissions(user = "svc-billing", write = cell(".*")), user = "svc-billing")
        assertNull(w.state.verdict, "a verdict about svc-orders must not stand against svc-billing")
    }

    @Test
    fun `the verdict is cleared when the shown permissions change`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders")
        w.give(permissions(write = cell("^queue\\..*")))
        assertNull(w.state.verdict)
    }

    @Test
    fun `the verdict is cleared when the connection state changes`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders")
        w.input.value = w.input.value.copy(link = LinkState.Failed("prod-eu", "Connection lost"))
        w.settle()
        assertNull(w.state.verdict)
    }

    @Test
    fun `the verdict is cleared when the permissions become unavailable`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders")
        w.input.value = w.input.value.copy(source = PermissionsSource.NotAvailable(PermissionsUnavailable.Locked))
        w.settle()
        assertNull(w.state.verdict)
    }

    @Test
    fun `an input that is equal to the last one does not clear the verdict`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders")
        w.give(permissions(write = cell(".*")))
        assertNotNull(w.state.verdict, "a refresh that returns the same patterns changes nothing the verdict depends on")
    }

    @Test
    fun `new permissions keep what was typed in the form`() = runTest {
        val w = World(this, permissions(write = cell(".*")))
        w.ask("ex.orders", "orders.created")
        w.give(permissions(write = cell("^queue\\..*")))
        assertEquals("ex.orders", w.state.name)
        assertEquals("orders.created", w.state.routingKey)
        assertEquals("/orders", w.state.vhost)
    }

    @Test
    fun `a chosen host that leaves the table falls back to the first host`() = runTest {
        val w = World(this)
        w.fill("ex.orders", vhost = "/orders")
        w.input.value = w.input.value.copy(vhosts = listOf("/billing"))
        w.settle()
        assertEquals("/billing", w.state.vhost)
        assertEquals(listOf("/billing"), w.state.vhosts)
    }

    // ---------------------------------------------------------------- Check sends nothing

    @Test
    fun `Check asks the matcher only and sends no request to the broker`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setPermissions("svc-orders", "/orders", ".*", "^ex\\..*", ".*")
        s.openAndChoose("svc-orders")
        val preview = MatchPreviewViewModel(s.vm.matchInput, backgroundScope)
        s.settle()
        s.forgetRequests()
        preview.chooseVhost("/orders")
        preview.setName("ex.orders")
        preview.check()
        s.settle()
        assertEquals("Would match.", preview.state.value.verdict?.title)
        assertEquals(emptyList(), s.requests(), "Check must not send any request")
    }

    // ---------------------------------------------------------------- the accessor on PermissionsViewModel

    @Test
    fun `the accessor of the Permissions view model hands over the chosen user, the rows the broker returned and the hosts of the table`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setPermissions("svc-orders", "/orders", ".*", "^ex\\..*", ".*")
        s.openAndChoose("svc-orders")
        val input = s.vm.matchInput.value
        assertEquals("svc-orders", input.user)
        val source = input.source as PermissionsSource.Available
        assertEquals("svc-orders", source.permissions.user)
        assertEquals("^ex\\..*", source.permissions.rows.single { it.vhost == "/orders" }.write.text)
        assertEquals(listOf("/", "/billing", "/orders"), input.vhosts, "the listed hosts, alphabetical, with or without a row")
        assertEquals(LinkState.Connected("prod-eu"), input.link)
    }

    @Test
    fun `the accessor is not narrowed by the virtual host filter of the tab`() = runTest {
        val s = PermissionsHarness(this)
        s.openAndChoose("svc-orders")
        s.vm.setVhostFilter("bill")
        s.settle()
        assertEquals(listOf("/", "/billing", "/orders"), s.vm.matchInput.value.vhosts)
    }

    @Test
    fun `the accessor says Locked for an administrator-less connection, TokenUser for a token user and NotLoaded before anything is read`() = runTest {
        val before = PermissionsHarness(this)
        assertEquals(PermissionsSource.NotAvailable(PermissionsUnavailable.NotLoaded), before.vm.matchInput.value.source)

        val locked = PermissionsHarness(this)
        locked.access.tags = listOf("monitoring")
        locked.open()
        assertEquals(PermissionsSource.NotAvailable(PermissionsUnavailable.Locked), locked.vm.matchInput.value.source)

        val token = PermissionsHarness(this)
        token.access.internalUser = false
        token.access.internalUsers = token.access.internalUsers - token.access.user
        token.open()
        assertEquals(PermissionsSource.NotAvailable(PermissionsUnavailable.TokenUser), token.vm.matchInput.value.source)
    }

    @Test
    fun `the accessor changes when another user is chosen and not when the same patterns are read again`() = runTest {
        val s = PermissionsHarness(this)
        s.access.setPermissions("svc-billing", "/billing", ".*", ".*", ".*")
        s.openAndChoose("svc-orders")
        val first = s.vm.matchInput.value
        s.vm.refreshNow()
        s.settle()
        assertEquals(first, s.vm.matchInput.value, "the same patterns read again are the same input")
        s.vm.chooseUser("svc-billing")
        s.settle()
        assertEquals("svc-billing", s.vm.matchInput.value.user)
        assertTrue(first != s.vm.matchInput.value)
    }
}
