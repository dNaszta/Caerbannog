package dev.caerbannog.ui.connections

import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.Endpoint
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-14a step 2 (tests first, red until built): the TLS switch, the "Not encrypted" warning, the disabled CA field and the CA validation
 * on focus loss and off the UI thread, on the Connections screen. Fakes are local, as in `ConnectionsScreenUiTest`.
 *
 * Contract (test tags are the test developer's proposal, text and icon names come from the brief and `docs/ux/`):
 *   "tlsSwitch"            the switch of the row labelled "Use TLS" (a Material Switch: on by default for a new profile and for a saved one with tls on)
 *   "notEncryptedWarning"  the warning directly under the switch, only with TLS off: the text "Not encrypted" and `icon:lock_open` inside
 *   ports                  still read-only text fields. TLS on keeps the labels "Stream port (TLS)" / "Management port (HTTPS)";
 *                          TLS off labels them "Stream port" / "Management port" (the "(TLS)" and "(HTTPS)" would be wrong)
 *   CA field               label "CA certificate"; with TLS off it is disabled and its helper reads "Not used without TLS", the saved path stays in it
 *   no text "TLS is always on" and no lock line any more
 * Validation of the CA path: not per keystroke; on focus loss and on Save; the `CaCertificateValidator` is called off the thread that runs
 * the UI; a result for another profile or another panel than the one shown is not shown.
 *
 * Where the brief is silent (listed in the Status): ports are NOT made editable by this task (they stay read-only, see `ConnectionsScreenUiTest`),
 * so "edited ports" are the ports of a saved profile that are not the defaults; Save does not have to abort on an invalid CA path.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionsScreenTlsUiTest {

    private class InMemoryRepository : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>()
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

    private class Secrets : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "fake-password"
        override fun delete(profileName: String) = Unit
    }

    private class Checker(var result: ConnectionCheckResult = ConnectionCheckResult.BothReachable) : BrokerReachabilityChecker {
        val profiles = Collections.synchronizedList(ArrayList<ConnectionProfile>())
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult {
            profiles += profile
            return result
        }
    }

    /** Records the thread of every call; invalid for anything but "/valid.pem"; can be held until released. */
    private class RecordingValidator : CaCertificateValidator {
        val paths: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads: MutableList<Thread> = Collections.synchronizedList(ArrayList())
        @Volatile var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)

        override fun validate(filePath: String): CaCertificateValidationResult {
            paths += filePath
            threads += Thread.currentThread()
            entered.countDown()
            gate?.let { check(it.await(5, TimeUnit.SECONDS)) { "the test never released the validator" } }
            return if (filePath == "/valid.pem") CaCertificateValidationResult.Valid else CaCertificateValidationResult.Invalid("the file could not be read")
        }
    }

    private val repository = InMemoryRepository()
    private val checker = Checker()
    private val validator = RecordingValidator()
    private val uiThreads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())

    private val dev = ConnectionProfile(
        name = "dev-local", host = "localhost", virtualHost = "/", username = "admin", streamPort = 5552, managementPort = 15672, tls = false,
    )
    private val uat = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc")

    private fun viewModel(vararg seeds: ConnectionProfile): ConnectionsViewModel {
        seeds.forEach { repository.add(it) }
        return ConnectionsViewModel(repository, Secrets(), checker, validator)
    }

    private fun DesktopComposeUiTest.show(vararg seeds: ConnectionProfile) {
        val vm = viewModel(*seeds)
        setContent {
            uiThreads += Thread.currentThread() // composition runs on the UI thread
            ConnectionsScreen(vm)
        }
        uiThreads += Thread.currentThread() // the test thread drives the same event loop
    }

    private fun DesktopComposeUiTest.select(name: String) {
        onAllNodes(
            SemanticsMatcher("card of $name") { it.config.getOrNull(SemanticsProperties.TestTag) == "permissionPill:$name" },
            useUnmergedTree = true,
        )[0].performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.tlsSwitch(): SemanticsNodeInteraction = onNodeWithTag("tlsSwitch").performScrollTo()

    private fun DesktopComposeUiTest.field(label: String): SemanticsNode {
        val nodes = onAllNodes(hasText(label).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))).fetchSemanticsNodes()
        assertEquals(1, nodes.size, "expected exactly one text field labelled '$label', found ${nodes.size}")
        return nodes.single()
    }

    private fun SemanticsNode.text(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun DesktopComposeUiTest.fieldNode(label: String): SemanticsNodeInteraction =
        onNode(hasText(label).and(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)))

    private fun DesktopComposeUiTest.exists(text: String, substring: Boolean = false): Boolean =
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun DesktopComposeUiTest.iconCount(name: String, under: String): Int =
        onAllNodes(hasTestTag("icon:$name").and(hasAnyAncestor(hasTestTag(under))), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.save() {
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.fillNew() {
        onNodeWithText("Name").performTextInput("new-conn")
        onNodeWithText("Host").performTextInput("rabbit.example.com")
        onNodeWithText("Username").performTextInput("svc")
    }

    private fun DesktopComposeUiTest.focusName() {
        fieldNode("Name").performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
    }

    // --- M2-14a R3: accessible names of the switches ------------------------------------------------------------------------------

    /** The accessible name of a node: its ContentDescription, else its Text (merged tree, as a screen reader sees it). */
    private fun SemanticsNode.accessibleName(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            .joinToString(" ")

    private fun DesktopComposeUiTest.switches(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch)).fetchSemanticsNodes()

    @Test
    fun `the Use TLS switch has the accessible name Use TLS`() = runDesktopComposeUiTest(1280, 880) {
        show()
        tlsSwitch()
        waitForIdle()

        assertTrue(onAllNodes(hasTestTag("tlsSwitch"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the tlsSwitch tag stays")
        val names = switches().map { it.accessibleName() }
        assertTrue(names.any { it.contains("Use TLS") }, "a screen reader must say what the TLS switch is for; the switch nodes are named: $names")
        // and the node that carries that name is the one that toggles
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch).and(hasText("Use TLS").or(androidx.compose.ui.test.hasContentDescription("Use TLS"))))
            .performScrollTo().assertIsOn().performClick()
        waitForIdle()
        onNodeWithTag("notEncryptedWarning").performScrollTo().assertExists()
    }

    @Test
    fun `the declaration switch has an accessible name`() = runDesktopComposeUiTest(1280, 880) {
        show()
        onNodeWithText("I know what this user may do on the broker").performScrollTo()
        waitForIdle()

        val names = switches().map { it.accessibleName() }
        assertTrue(
            names.any { it.contains("I know what this user may do on the broker") },
            "a screen reader must say what the declaration switch is for; the switch nodes are named: $names",
        )
    }

    // --- the switch and the warning --------------------------------------------------------------------------------------------

    @Test
    fun `a new connection shows the switch Use TLS on, the TLS ports and no warning`() = runDesktopComposeUiTest(1280, 880) {
        show()

        tlsSwitch().assertIsOn()
        assertTrue(exists("Use TLS"), "the switch has a visible label")
        assertFalse(exists("Not encrypted"), "no warning with TLS on")
        assertEquals("5551", field("Stream port (TLS)").text())
        assertEquals("15671", field("Management port (HTTPS)").text())
        assertFalse(exists("TLS is always on", substring = true), "the old fixed TLS line is gone")
    }

    @Test
    fun `turning the switch off shows the warning with a lock_open icon, the plain ports and no host name note`() = runDesktopComposeUiTest(1280, 880) {
        show()
        assertTrue(exists("Host name is not verified."), "precondition: the note is shown with TLS on")

        tlsSwitch().performClick()
        waitForIdle()

        tlsSwitch().assertIsOff()
        onNodeWithTag("notEncryptedWarning").performScrollTo().assertExists()
        onNode(hasTestTag("notEncryptedWarning").and(hasText("Not encrypted")), useUnmergedTree = true).assertExists("the warning says 'Not encrypted'")
        assertEquals(1, iconCount("lock_open", "notEncryptedWarning"), "the warning has an icon, it is not colour only")
        assertEquals("5552", field("Stream port").text())
        assertEquals("15672", field("Management port").text())
        assertFalse(exists("Stream port (TLS)"), "the TLS wording of the label is gone with TLS off")
        assertFalse(exists("Host name is not verified."), "the host name note only exists with TLS on")
    }

    @Test
    fun `the warning sits directly under the switch`() = runDesktopComposeUiTest(1280, 880) {
        show()
        tlsSwitch().performClick()
        waitForIdle()

        val switch = onNodeWithTag("tlsSwitch").fetchSemanticsNode().boundsInRoot
        val warning = onNodeWithTag("notEncryptedWarning").fetchSemanticsNode().boundsInRoot
        assertTrue(warning.top >= switch.bottom - 1f, "the warning ($warning) must be below the switch ($switch)")
        assertTrue(warning.top - switch.bottom < 64f, "the warning ($warning) must be right under the switch ($switch), not further down")
    }

    @Test
    fun `turning the switch on again brings back the TLS ports, the note and removes the warning`() = runDesktopComposeUiTest(1280, 880) {
        show()
        tlsSwitch().performClick()
        waitForIdle()
        tlsSwitch().performClick()
        waitForIdle()

        tlsSwitch().assertIsOn()
        assertEquals("5551", field("Stream port (TLS)").text())
        assertEquals("15671", field("Management port (HTTPS)").text())
        assertTrue(exists("Host name is not verified."))
        onNodeWithTag("notEncryptedWarning").assertDoesNotExist()
    }

    @Test
    fun `only ports that are still the default move with the switch`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(streamPort = 5600, managementPort = 15671))
        select("uat-eu")
        assertEquals("5600", field("Stream port (TLS)").text())

        tlsSwitch().performClick()
        waitForIdle()

        assertEquals("5600", field("Stream port").text(), "an edited stream port stays")
        assertEquals("15672", field("Management port").text(), "the untouched management port follows")

        tlsSwitch().performClick()
        waitForIdle()
        assertEquals("5600", field("Stream port (TLS)").text())
        assertEquals("15671", field("Management port (HTTPS)").text())
    }

    @Test
    fun `a saved plain profile shows the switch off and the warning, and another profile resets it`() = runDesktopComposeUiTest(1280, 880) {
        show(dev, uat)

        select("dev-local")
        tlsSwitch().assertIsOff()
        onNodeWithTag("notEncryptedWarning").performScrollTo().assertExists()
        assertEquals("5552", field("Stream port").text())

        select("uat-eu")
        tlsSwitch().assertIsOn()
        onNodeWithTag("notEncryptedWarning").assertDoesNotExist()
        assertEquals("5551", field("Stream port (TLS)").text())
    }

    @Test
    fun `an unsaved switch change is dropped when another profile is selected`() = runDesktopComposeUiTest(1280, 880) {
        show(uat, dev)
        select("uat-eu")
        tlsSwitch().performClick() // off, not saved
        select("dev-local")
        select("uat-eu")

        tlsSwitch().assertIsOn()
        assertEquals(true, repository.list().first { it.name == "uat-eu" }.tls, "nothing is saved before Save")
    }

    // --- Save ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `a new connection saved with the switch off has TLS off and the plain ports`() = runDesktopComposeUiTest(1280, 880) {
        show()
        fillNew()
        tlsSwitch().performClick()
        save()

        val saved = repository.list().single()
        assertFalse(saved.tls)
        assertEquals(5552, saved.streamPort)
        assertEquals(15672, saved.managementPort)
    }

    @Test
    fun `a new connection saved without touching the switch has TLS on and the TLS ports`() = runDesktopComposeUiTest(1280, 880) {
        show()
        fillNew()
        save()

        val saved = repository.list().single()
        assertTrue(saved.tls)
        assertEquals(5551, saved.streamPort)
        assertEquals(15671, saved.managementPort)
    }

    @Test
    fun `saving a saved plain profile untouched keeps TLS off and its ports`() = runDesktopComposeUiTest(1280, 880) {
        show(dev)
        select("dev-local")
        save()

        val saved = repository.list().single()
        assertFalse(saved.tls)
        assertEquals(5552, saved.streamPort)
        assertEquals(15672, saved.managementPort)
    }

    @Test
    fun `saving a TLS profile with the switch turned off stores TLS off and moves default ports only`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(streamPort = 5600))
        select("uat-eu")
        tlsSwitch().performClick()
        save()

        val saved = repository.list().single()
        assertFalse(saved.tls)
        assertEquals(5600, saved.streamPort)
        assertEquals(15672, saved.managementPort)
    }

    // --- the CA field with TLS off (O3: kept but unused) ------------------------------------------------------------------------

    @Test
    fun `with TLS off the CA field is disabled, says it is not used and keeps the saved path`() = runDesktopComposeUiTest(1280, 880) {
        show(dev.copy(caCertificatePath = "/etc/ssl/uat-ca.pem"))
        select("dev-local")

        fieldNode("CA certificate").assertIsNotEnabled()
        assertEquals("/etc/ssl/uat-ca.pem", field("CA certificate").text(), "the saved path stays visible, it is only unused")
        assertTrue(exists("Not used without TLS"))
        assertFalse(exists("Blank uses the system trust store"))
    }

    @Test
    fun `with TLS on the CA field is enabled with its usual helper`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")

        fieldNode("CA certificate").assertIsEnabled()
        assertTrue(exists("Blank uses the system trust store"))
        assertFalse(exists("Not used without TLS"))
    }

    @Test
    fun `toggling the switch toggles the CA field and the path survives the round trip`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(caCertificatePath = "/etc/ssl/uat-ca.pem"))
        select("uat-eu")

        tlsSwitch().performClick()
        waitForIdle()
        fieldNode("CA certificate").assertIsNotEnabled()
        assertEquals("/etc/ssl/uat-ca.pem", field("CA certificate").text())

        tlsSwitch().performClick()
        waitForIdle()
        fieldNode("CA certificate").assertIsEnabled()
        assertEquals("/etc/ssl/uat-ca.pem", field("CA certificate").text())
    }

    @Test
    fun `Save with TLS off never validates the CA path and keeps it saved`() = runDesktopComposeUiTest(1280, 880) {
        show(dev.copy(caCertificatePath = "/no/such/ca.pem"))
        select("dev-local")
        save()

        assertEquals(emptyList(), validator.paths.toList(), "the CA path is not validated with TLS off")
        assertEquals("/no/such/ca.pem", repository.list().single().caCertificatePath)
        assertFalse(exists("the file could not be read"), "no CA message with TLS off")
    }

    @Test
    fun `a CA message from the TLS on state is hidden when the switch goes off`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/not/a/file.pem")
        focusName()
        waitUntil(timeoutMillis = 10_000) { exists("the file could not be read") }

        tlsSwitch().performClick()
        waitForIdle()

        assertFalse(exists("the file could not be read"), "the CA is ignored with TLS off, so is its message")
    }

    // --- CA validation: on focus loss and on Save, off the UI thread -------------------------------------------------------------

    @Test
    fun `typing a CA path does not validate it per keystroke`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")

        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")
        waitForIdle()

        assertEquals(emptyList(), validator.paths.toList(), "no validation while the field has focus")
        assertFalse(exists("the file could not be read"), "no message while typing")
    }

    @Test
    fun `leaving the field validates the path once and shows the result`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")

        focusName()
        waitUntil(timeoutMillis = 10_000) { exists("the file could not be read") }

        assertEquals(listOf("/some/path/ca.pem"), validator.paths.toList(), "one validation of the typed path, when focus left")
    }

    @Test
    fun `leaving an untouched CA field validates nothing`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(caCertificatePath = "/valid.pem"))
        select("uat-eu")
        fieldNode("CA certificate").performSemanticsAction(SemanticsActions.RequestFocus)
        focusName()
        waitForIdle()

        assertEquals(emptyList(), validator.paths.toList())
    }

    @Test
    fun `Save validates a CA path that still has focus`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")

        save() // the user never left the field
        waitUntil(timeoutMillis = 10_000) { exists("the file could not be read") }

        assertEquals(listOf("/some/path/ca.pem"), validator.paths.toList(), "validated once: on Save, not again on focus loss")
    }

    @Test
    fun `editing a saved profile's CA path then Save without leaving the field stores the valid path, validated once`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/valid.pem")

        save() // focus never left the field
        waitUntil(timeoutMillis = 10_000) { repository.list().single().caCertificatePath == "/valid.pem" }

        assertEquals(listOf("/valid.pem"), validator.paths.toList(), "the file is read exactly once")
    }

    @Test
    fun `Save pressed while the focus-loss validation is still running loses neither the path nor the other edits`() = runDesktopComposeUiTest(1280, 880) {
        validator.gate = CountDownLatch(1)
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/valid.pem")
        focusName()
        assertTrue(validator.entered.await(10, TimeUnit.SECONDS), "the validator was never asked")

        onNodeWithText("Host").performTextInput("-edited")
        save() // validation of /valid.pem is still held
        validator.gate!!.countDown()
        waitUntil(timeoutMillis = 10_000) { repository.list().single().caCertificatePath == "/valid.pem" }
        waitForIdle()

        val stored = repository.list().single()
        assertEquals("/valid.pem", stored.caCertificatePath, "the validated path must be stored, not a stale value")
        assertTrue(stored.host.contains("-edited"), "the late validation result must not overwrite the host saved meanwhile, was '${stored.host}'")
        assertEquals(listOf("/valid.pem"), validator.paths.toList(), "validated once")
    }

    @Test
    fun `a blank CA path is never validated`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("   ")
        focusName()
        save()

        assertEquals(emptyList(), validator.paths.toList())
    }

    @Test
    fun `the validator runs off the thread that runs the UI`() = runDesktopComposeUiTest(1280, 880) {
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")
        focusName()
        waitUntil(timeoutMillis = 10_000) { validator.threads.isNotEmpty() }

        val ui = uiThreads.toList()
        assertTrue(ui.isNotEmpty())
        val on = validator.threads.first()
        // The UI thread of the app is the AWT event queue; in this test harness the composition and the test body run on the test worker.
        assertTrue(
            on !in ui && !on.name.startsWith("AWT-EventQueue"),
            "the file was read on '${on.name}', which is a UI thread (${ui.map { it.name }}); it must be an IO thread",
        )
    }

    @Test
    fun `a slow validator does not block the UI`() = runDesktopComposeUiTest(1280, 880) {
        validator.gate = CountDownLatch(1)
        show(uat)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")
        focusName()
        assertTrue(validator.entered.await(10, TimeUnit.SECONDS), "the validator was never asked")

        // The validator is held. The UI must still take input.
        onNodeWithText("Name").performTextInput("typed-while-validating")
        waitForIdle()
        assertTrue(field("Name").text().orEmpty().contains("typed-while-validating"), "the UI was blocked by the validator")

        validator.gate!!.countDown()
        waitUntil(timeoutMillis = 10_000) { exists("the file could not be read") }
    }

    @Test
    fun `a validation result that arrives after the user opened another panel is not shown there`() = runDesktopComposeUiTest(1280, 880) {
        validator.gate = CountDownLatch(1)
        show(uat, dev)
        select("uat-eu")
        onNodeWithText("CA certificate").performTextInput("/some/path/ca.pem")
        focusName()
        assertTrue(validator.entered.await(10, TimeUnit.SECONDS))

        select("dev-local") // the user moved on while the file was still being read
        validator.gate!!.countDown()
        waitForIdle()
        Thread.sleep(300) // the late result has no UI signal to wait on; give it time to (wrongly) arrive
        waitForIdle()

        assertFalse(exists("the file could not be read"), "a late result of uat-eu was shown on dev-local")
        select("uat-eu")
        assertFalse(exists("the file could not be read"), "and it must not come back when uat-eu is opened again")
    }

    // --- the Test connection wording --------------------------------------------------------------------------------------------

    private fun DesktopComposeUiTest.check(result: ConnectionCheckResult, profile: ConnectionProfile) {
        checker.result = result
        show(profile)
        select(profile.name)
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun `check over plain says both endpoints are reachable and the connection is not encrypted`() = runDesktopComposeUiTest(1280, 880) {
        check(ConnectionCheckResult.BothReachable, dev)

        assertTrue(exists("Both endpoints are reachable. This connection is not encrypted."), "the plain success text")
        assertFalse(exists("Connection test passed", substring = true), "the TLS wording is not used for a plain profile")
        assertEquals(listOf(false), checker.profiles.map { it.tls }, "the check was asked for the profile as saved, with TLS off")
    }

    @Test
    fun `check with TLS on keeps today's success text`() = runDesktopComposeUiTest(1280, 880) {
        check(ConnectionCheckResult.BothReachable, uat)

        assertTrue(exists("Connection test passed. The stream endpoint and the management API are both reachable."))
        assertFalse(exists("not encrypted", substring = true))
    }

    @Test
    fun `TLS on against a plain broker shows the hint`() = runDesktopComposeUiTest(1280, 880) {
        check(ConnectionCheckResult.TlsRefused, uat)

        assertTrue(
            exists("The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended."),
            "the hint names the switch",
        )
        assertFalse(exists("The app does not connect without TLS", substring = true), "the old refusal text is gone")
    }

    @Test
    fun `a plain failure names the endpoint and says nothing about TLS or a certificate`() = runDesktopComposeUiTest(1280, 880) {
        check(ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused"), dev)

        assertTrue(exists("The stream endpoint could not be reached: connection refused"))
        assertFalse(exists("handshake", substring = true), "no TLS wording in a plain failure")
        assertFalse(exists("TLS is always on", substring = true))
    }

    @Test
    fun `a plain save and a check never show the TLS always on line`() = runDesktopComposeUiTest(1280, 880) {
        check(ConnectionCheckResult.BothReachable, dev)
        assertFalse(exists("TLS is always on", substring = true))
        assertTrue(exists("Use TLS"))
    }

    // --- M6-01: MQTT UI tests -----------------------------------------------------------------------------------------

    @Test
    fun `mqtt plugin toggle is off by default for a new profile and shows no mqtt port field`() = runDesktopComposeUiTest(1280, 880) {
        show()
        onNodeWithTag("mqttPluginSwitch").performScrollTo().assertIsOff()
        assertFalse(exists("MQTT port", substring = true))
    }

    @Test
    fun `toggling mqtt on shows the mqtt port field with 8883 when TLS is on`() = runDesktopComposeUiTest(1280, 880) {
        show()
        onNodeWithTag("mqttPluginSwitch").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("mqttPluginSwitch").assertIsOn()
        assertTrue(exists("MQTT port (TLS)"))
        assertEquals("8883", field("MQTT port (TLS)").text())
    }

    @Test
    fun `toggling tls off changes the mqtt port field to plain port 1883`() = runDesktopComposeUiTest(1280, 880) {
        show()
        onNodeWithTag("mqttPluginSwitch").performScrollTo().performClick()
        waitForIdle()
        assertEquals("8883", field("MQTT port (TLS)").text())

        tlsSwitch().performClick()
        waitForIdle()

        assertTrue(exists("MQTT port"))
        assertFalse(exists("MQTT port (TLS)"))
        assertEquals("1883", field("MQTT port").text())
    }

    @Test
    fun `a saved profile with mqttPort starts with MQTT toggle on and displays the port`() = runDesktopComposeUiTest(1280, 880) {
        val mqttProfile = uat.copy(mqttPort = 8883)
        show(mqttProfile)
        select(mqttProfile.name)

        onNodeWithTag("mqttPluginSwitch").performScrollTo().assertIsOn()
        assertTrue(exists("MQTT port (TLS)"))
        assertEquals("8883", field("MQTT port (TLS)").text())
    }

    // --- M5-05: verifyHostName switch UI tests ------------------------------------------------------------------------

    private fun DesktopComposeUiTest.verifyHostNameSwitch(): SemanticsNodeInteraction = onNodeWithTag("verifyHostNameSwitch").performScrollTo()

    @Test
    fun `with TLS on, toggling verify host name on hides the note and persists on save`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(verifyHostName = false))
        select(uat.name)

        assertTrue(exists("Host name is not verified."))
        verifyHostNameSwitch().assertIsOff()

        verifyHostNameSwitch().performClick()
        waitForIdle()

        verifyHostNameSwitch().assertIsOn()
        assertFalse(exists("Host name is not verified."))

        save()

        val saved = repository.list().first { it.name == uat.name }
        assertTrue(saved.verifyHostName)
    }

    @Test
    fun `with TLS off, verify host name switch is not shown`() = runDesktopComposeUiTest(1280, 880) {
        show(uat.copy(tls = false))
        select(uat.name)

        onNodeWithTag("verifyHostNameSwitch").assertDoesNotExist()
    }
}
