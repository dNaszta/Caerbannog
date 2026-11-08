package dev.caerbannog.ui.connections

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.UnreadableCause
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.Endpoint
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ordinary (non-Gherkin) coverage for the Compose Connections screen built by this task: no scenario
 * in `01_connections.feature` exercises the screen directly (Cucumber drives domain behaviour through
 * `ConnectionsViewModel`, not Compose rendering), so this is plain TDD-style coverage for the one
 * piece of UI production code this task adds, using minimal local fakes (this module cannot depend on
 * `acceptance-tests`, nor should it - a Compose screen test has no business depending on a
 * Cucumber-step module).
 */
class ConnectionsScreenUiTest {

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

    private class NoOpSecretStore : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = null
        override fun delete(profileName: String) = Unit
    }

    private class AlwaysReachableChecker : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class AlwaysValidCertificate : CaCertificateValidator {
        override fun validate(filePath: String): CaCertificateValidationResult = CaCertificateValidationResult.Valid
    }

    private var repository = InMemoryRepository()

    /** Clicks the n-th profile card (cards are found through their pill, in list order). */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.selectProfile(index: Int) {
        onAllNodes(
            SemanticsMatcher("permission pill") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("permissionPill:") == true
            },
            // Unmerged tree: the pill tag stays findable whether the card is `clickable` (merges children) or not;
            // the click lands on the pill, which sits inside the card, so it reaches the card either way.
            useUnmergedTree = true,
        )[index].performClick()
        waitForIdle()
    }

    /**
     * The "New connection" button. Found by text plus click action, because the empty panel's title is also
     * the text "New connection" (not clickable); after M1-12a the button lives in the app bar, not the list.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.newConnectionButton(): SemanticsNodeInteraction =
        onNode(hasText("New connection").and(hasClickAction()))

    private fun testViewModel(vararg seeds: ConnectionProfile): ConnectionsViewModel {
        repository = InMemoryRepository()
        seeds.forEach { repository.add(it) }
        return ConnectionsViewModel(repository, NoOpSecretStore(), AlwaysReachableChecker(), AlwaysValidCertificate())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `empty state shows no saved connections`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        onNodeWithText("No saved connections yet.").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting a saved profile shows its fields in the editing panel`() = runDesktopComposeUiTest {
        val profile = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(profile)) }

        selectProfile(0)

        onNodeWithText("Edit connection").assertExists()
        onNodeWithText("Delete profile").assertExists()
    }

    // M2-03 part 1, scenario "The connection panel says the host name is not verified" (13, product decision D7).
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the edit panel says the host name is not verified`() = runDesktopComposeUiTest {
        val profile = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(profile)) }

        selectProfile(0)

        onNodeWithText("Edit connection").assertExists()
        onNodeWithText("Host name is not verified.", substring = true).performScrollTo().assertExists()
    }

    private val passedBanner = "Connection test passed"

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `saving a new connection under an existing name shows an inline error naming it`() = runDesktopComposeUiTest {
        val existing = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(existing)) }

        newConnectionButton().performClick()
        onNodeWithText("Name").performTextInput("uat-eu")
        onNodeWithText("Host").performTextInput("other.example.com")
        onNodeWithText("Username").performTextInput("someone")
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()

        onNodeWithText("A connection named 'uat-eu' already exists.").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting another profile clears the previous profile's check-connection banner`() = runDesktopComposeUiTest {
        val a = ConnectionProfile(name = "profile-a", host = "a.example.com", virtualHost = "/", username = "u")
        val b = ConnectionProfile(name = "profile-b", host = "b.example.com", virtualHost = "/", username = "u")
        setContent { ConnectionsScreen(testViewModel(a, b)) }

        selectProfile(0)
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(passedBanner, substring = true).assertExists()

        selectProfile(1)
        waitForIdle()

        onNodeWithText("Edit connection").assertExists()
        onNodeWithText(passedBanner, substring = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking New connection clears the previous profile's check-connection banner`() = runDesktopComposeUiTest {
        val a = ConnectionProfile(name = "profile-a", host = "a.example.com", virtualHost = "/", username = "u")
        setContent { ConnectionsScreen(testViewModel(a)) }

        selectProfile(0)
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(passedBanner, substring = true).assertExists()

        newConnectionButton().performClick()
        waitForIdle()

        onNodeWithText(passedBanner, substring = true).assertDoesNotExist()
    }

    // --- Topology control level (M1-10) -------------------------------------------------------
    // The three radios are located by role, in display order: Read-only, Declare and edit, Full control.

    private val fullControlTitle = "Allow full control?"
    private val fullControlBody =
        "Full control lets this app delete topology (queues, streams, exchanges, bindings, policies) on this " +
            "connection and run migrations with destructive actions. This is only your own ceiling for this " +
            "connection: the broker's permissions still apply, and the app never reads, gets or purges queue messages."

    private val readOnly = 0
    private val declareAndEdit = 1
    private val fullControl = 2

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.radio(index: Int): SemanticsNodeInteraction =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))[index]

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertSelectedLevel(index: Int) {
        (0..2).forEach { i ->
            if (i == index) radio(i).performScrollTo().assertIsSelected() else radio(i).performScrollTo().assertIsNotSelected()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.pick(index: Int) {
        radio(index).performScrollTo().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.fillNewConnection() {
        onNodeWithText("Name").performTextInput("new-one")
        onNodeWithText("Host").performTextInput("new.example.com")
        onNodeWithText("Username").performTextInput("someone")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.save() {
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.dialogIsOpen(): Boolean =
        onAllNodesWithText(fullControlTitle).fetchSemanticsNodes().isNotEmpty()

    private fun saved(level: TopologyControlLevel = TopologyControlLevel.READ_ONLY) = ConnectionProfile(
        name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer",
        topologyControlLevel = level,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection shows three enabled topology radios with Read-only selected`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        (0..2).forEach { radio(it).performScrollTo().assertIsEnabled() }
        assertSelectedLevel(readOnly)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved with Declare and edit stores DECLARE_AND_EDIT`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()

        pick(declareAndEdit)
        assertEquals(false, dialogIsOpen(), "no confirmation expected for Declare and edit")
        assertSelectedLevel(declareAndEdit)
        save()

        assertEquals(TopologyControlLevel.DECLARE_AND_EDIT, repository.list().single().topologyControlLevel)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved without touching the radios stores READ_ONLY`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        save()

        assertEquals(TopologyControlLevel.READ_ONLY, repository.list().single().topologyControlLevel)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `picking Full control asks first, keeps the previous selection until confirmed, then Save stores FULL_CONTROL`() =
        runDesktopComposeUiTest {
            setContent { ConnectionsScreen(testViewModel()) }
            fillNewConnection()

            pick(fullControl)

            onNodeWithText(fullControlTitle).assertExists()
            onNodeWithText(fullControlBody).assertExists()
            onNodeWithText("Use Full control").assertExists()
            onNodeWithText("Cancel").assertExists()
            // Nothing selected yet: the dialog is only a question. (Radios sit behind the dialog scrim.)
            onNodeWithText("Use Full control").performClick()
            waitForIdle()

            assertEquals(false, dialogIsOpen())
            assertSelectedLevel(fullControl)
            save()
            assertEquals(TopologyControlLevel.FULL_CONTROL, repository.list().single().topologyControlLevel)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selection is still the previous one while the Full control dialog is open`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        pick(declareAndEdit)

        pick(fullControl)

        assertEquals(true, dialogIsOpen())
        radio(declareAndEdit).assertIsSelected()
        radio(fullControl).assertIsNotSelected()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.cancelFromLevel(startIndex: Int, startLevel: TopologyControlLevel) {
        fillNewConnection()
        if (startIndex != readOnly) pick(startIndex)

        pick(fullControl)
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertEquals(false, dialogIsOpen())
        assertSelectedLevel(startIndex)
        save()
        assertEquals(startLevel, repository.list().single().topologyControlLevel)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Cancel on the Full control dialog keeps Read-only`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        cancelFromLevel(readOnly, TopologyControlLevel.READ_ONLY)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Cancel on the Full control dialog keeps Declare and edit`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        cancelFromLevel(declareAndEdit, TopologyControlLevel.DECLARE_AND_EDIT)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Esc on the Full control dialog behaves like Cancel`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        pick(declareAndEdit)

        pick(fullControl)
        onNodeWithText(fullControlTitle).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(false, dialogIsOpen())
        assertSelectedLevel(declareAndEdit)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `re-clicking an already selected Full control asks nothing`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel(saved(TopologyControlLevel.FULL_CONTROL))) }
        selectProfile(0)
        waitForIdle()
        assertSelectedLevel(fullControl)

        pick(fullControl)

        assertEquals(false, dialogIsOpen())
        assertSelectedLevel(fullControl)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `going from Full control to Read-only asks nothing, and raising again asks again`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel(saved(TopologyControlLevel.FULL_CONTROL))) }
        selectProfile(0)
        waitForIdle()

        pick(declareAndEdit)
        assertEquals(false, dialogIsOpen())
        assertSelectedLevel(declareAndEdit)

        pick(fullControl)
        assertEquals(true, dialogIsOpen(), "each raise to Full control is a fresh act and must ask again")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit mode shows the saved level and a click changes nothing until Save`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel(saved(TopologyControlLevel.READ_ONLY))) }
        selectProfile(0)
        waitForIdle()
        (0..2).forEach { radio(it).performScrollTo().assertIsEnabled() }
        assertSelectedLevel(readOnly)

        pick(declareAndEdit)
        assertSelectedLevel(declareAndEdit)
        assertEquals(TopologyControlLevel.READ_ONLY, repository.list().single().topologyControlLevel,
            "clicking a radio must not persist anything before Save")

        save()
        assertEquals(TopologyControlLevel.DECLARE_AND_EDIT, repository.list().single().topologyControlLevel)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `edit mode Full control is persisted only after confirm and Save`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel(saved(TopologyControlLevel.READ_ONLY))) }
        selectProfile(0)
        waitForIdle()

        pick(fullControl)
        onNodeWithText("Use Full control").performClick()
        waitForIdle()
        assertSelectedLevel(fullControl)
        assertEquals(TopologyControlLevel.READ_ONLY, repository.list().single().topologyControlLevel,
            "confirming the dialog must not persist anything before Save")

        save()
        assertEquals(TopologyControlLevel.FULL_CONTROL, repository.list().single().topologyControlLevel)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting another profile resets the radios to that profile's saved level`() = runDesktopComposeUiTest {
        val a = saved(TopologyControlLevel.READ_ONLY).copy(name = "profile-a")
        val b = saved(TopologyControlLevel.DECLARE_AND_EDIT).copy(name = "profile-b")
        setContent { ConnectionsScreen(testViewModel(a, b)) }

        selectProfile(0)
        waitForIdle()
        pick(declareAndEdit) // unsaved change on profile-a
        assertSelectedLevel(declareAndEdit)

        selectProfile(1)
        waitForIdle()
        assertSelectedLevel(declareAndEdit) // profile-b's own saved level
        selectProfile(0)
        waitForIdle()
        assertSelectedLevel(readOnly) // profile-a: the unsaved pick is gone
    }

    // --- Declared permissions (M1-06) -----------------------------------------------------------
    // Contract the screen must follow (proposed by the test developer, see the M1-06 Status block):
    //  - ONE declaration switch (Role.Switch; since M2-14 the form also has the "Use TLS" switch, tag tlsSwitch), labelled "I know what this user may do on the broker", below "Topology control".
    //  - When on: two yes/no choices, each a pair of RadioButtons in the order Yes, No, labelled
    //    "Can change topology (configure)" and "Can publish (write)". In display order the radios are
    //    0..2 topology level (existing), then 3 configure-Yes, 4 configure-No, 5 write-Yes, 6 write-No.
    //  - When off the two choices are not shown at all (no extra radios).
    //  - Form state only: nothing reaches the repository before Save; Save sends Clear when off, Set(...) when on.
    // Red state: none of this exists yet, so these fail on assertions (the switch node is not found).

    private val declarationSwitchLabel = "I know what this user may do on the broker"
    private val configureLabel = "Can change topology (configure)"
    private val writeLabel = "Can publish (write)"
    private val declarationHelper =
        "Only used when the broker does not let this user read its own permissions. This is your own " +
            "statement; the broker still enforces its permissions."

    private val configureYes = 3
    private val configureNo = 4
    private val writeYes = 5
    private val writeNo = 6

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.declarationSwitch(): SemanticsNodeInteraction =
        // M2-14: the form has a second switch, "Use TLS" (tag tlsSwitch); M6-01: MQTT switch (tag mqttPluginSwitch); M5-05: verifyHostName
        onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
                .and(!hasTestTag("tlsSwitch"))
                .and(!hasTestTag("verifyHostNameSwitch"))
                .and(!hasTestTag("mqttPluginSwitch")),
        ).performScrollTo()

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.toggleDeclaration() {
        declarationSwitch().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.radioCount(): Int =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes().size

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertDeclared(configure: Boolean, write: Boolean) {
        onNodeWithText(configureLabel).performScrollTo().assertExists()
        onNodeWithText(writeLabel).performScrollTo().assertExists()
        selectedPair(configureYes, configureNo, configure)
        selectedPair(writeYes, writeNo, write)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.selectedPair(yes: Int, no: Int, expectYes: Boolean) {
        if (expectYes) {
            radio(yes).performScrollTo().assertIsSelected(); radio(no).performScrollTo().assertIsNotSelected()
        } else {
            radio(yes).performScrollTo().assertIsNotSelected(); radio(no).performScrollTo().assertIsSelected()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertNotDeclared() {
        declarationSwitch().assertIsOff()
        onAllNodesWithText(configureLabel).assertCountEquals(0)
        onAllNodesWithText(writeLabel).assertCountEquals(0)
        assertEquals(3, radioCount(), "only the three topology radios may exist while no declaration is made")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.selectSaved(profile: ConnectionProfile): ConnectionsViewModel {
        val vm = testViewModel(profile)
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)
        waitForIdle()
        return vm
    }

    private fun declared(configure: Boolean, write: Boolean) =
        saved().copy(declaredPermissions = DeclaredPermissions(configure = configure, write = write))

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the declaration switch, its label and the fixed helper text are shown`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        onNodeWithText(declarationSwitchLabel).performScrollTo().assertExists()
        declarationSwitch().assertExists()
        onNodeWithText(declarationHelper).performScrollTo().assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the declared permissions section title says it is the user's own statement`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        // Exact full-text match (substring = false is the default): the old title "Declared permissions" must not satisfy it.
        onNodeWithText("Declared permissions (your own statement)").performScrollTo().assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection starts with the switch off and no choices shown`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        assertNotDeclared()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved with the switch off declares nothing`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        save()

        assertNull(repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `switching on shows both choices, each starting at No`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }

        toggleDeclaration()

        declarationSwitch().assertIsOn()
        assertEquals(7, radioCount(), "three topology radios plus two yes/no pairs")
        assertDeclared(configure = false, write = false)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved with the switch on and both No declares no and no`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        toggleDeclaration()
        save()

        assertEquals(DeclaredPermissions(configure = false, write = false), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved with configure Yes and write No declares yes and no`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        toggleDeclaration()

        radio(configureYes).performScrollTo().performClick()
        waitForIdle()
        assertDeclared(configure = true, write = false)
        save()

        assertEquals(DeclaredPermissions(configure = true, write = false), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `new connection saved with configure No and write Yes declares no and yes`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        toggleDeclaration()

        radio(writeYes).performScrollTo().performClick()
        waitForIdle()
        assertDeclared(configure = false, write = true)
        save()

        assertEquals(DeclaredPermissions(configure = false, write = true), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a choice can be changed back before saving`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        toggleDeclaration()
        radio(configureYes).performScrollTo().performClick()
        radio(configureNo).performScrollTo().performClick()
        waitForIdle()
        assertDeclared(configure = false, write = false)
        save()

        assertEquals(DeclaredPermissions(false, false), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `switching on and off again before saving declares nothing`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(testViewModel()) }
        fillNewConnection()
        toggleDeclaration()
        radio(configureYes).performScrollTo().performClick()
        toggleDeclaration()
        assertNotDeclared()
        save()

        assertNull(repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `editing a profile without a declaration shows the switch off`() = runDesktopComposeUiTest {
        selectSaved(saved())
        assertNotDeclared()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `editing a profile declared yes and no shows the switch on with those values`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = false))

        declarationSwitch().assertIsOn()
        assertDeclared(configure = true, write = false)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `editing a profile declared no and yes shows the switch on with those values`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = false, write = true))

        declarationSwitch().assertIsOn()
        assertDeclared(configure = false, write = true)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `nothing is saved before Save`() = runDesktopComposeUiTest {
        selectSaved(saved())

        toggleDeclaration()
        radio(configureYes).performScrollTo().performClick()
        waitForIdle()

        assertNull(repository.list().single().declaredPermissions, "form state must not reach the repository before Save")
        save()
        assertEquals(DeclaredPermissions(configure = true, write = false), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `switching off an existing declaration changes nothing until Save, then Save clears it`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = true))

        toggleDeclaration()
        assertNotDeclared()
        assertEquals(DeclaredPermissions(true, true), repository.list().single().declaredPermissions,
            "switching off must not persist anything before Save")

        save()
        assertNull(repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `saving an edited profile without touching the declaration keeps it`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = false))

        save()

        assertEquals(DeclaredPermissions(configure = true, write = false), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `changing a declared right on an edited profile replaces the declaration on Save`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = false))

        radio(configureNo).performScrollTo().performClick()
        radio(writeYes).performScrollTo().performClick()
        waitForIdle()
        save()

        assertEquals(DeclaredPermissions(configure = false, write = true), repository.list().single().declaredPermissions)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a declaration and the other fields are saved independently`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = true).copy(topologyControlLevel = TopologyControlLevel.DECLARE_AND_EDIT))

        onNodeWithText("Host").performTextReplacement("changed.example.com")
        radio(readOnly).performScrollTo().performClick()
        save()

        val after = repository.list().single()
        assertEquals("changed.example.com", after.host)
        assertEquals(TopologyControlLevel.READ_ONLY, after.topologyControlLevel)
        assertEquals(DeclaredPermissions(true, true), after.declaredPermissions)
        assertEquals("svc-explorer", after.username)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting another profile resets the switch and choices to that profile's saved declaration`() = runDesktopComposeUiTest {
        val a = declared(configure = true, write = false).copy(name = "profile-a")
        val b = saved().copy(name = "profile-b")
        setContent { ConnectionsScreen(testViewModel(a, b)) }

        selectProfile(0)
        waitForIdle()
        assertDeclared(configure = true, write = false)
        radio(writeYes).performScrollTo().performClick() // unsaved change on profile-a
        waitForIdle()

        selectProfile(1)
        waitForIdle()
        assertNotDeclared()

        selectProfile(0)
        waitForIdle()
        assertDeclared(configure = true, write = false) // the unsaved pick is gone
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking New connection after editing a declared profile shows the switch off`() = runDesktopComposeUiTest {
        selectSaved(declared(configure = true, write = true))

        newConnectionButton().performClick()
        waitForIdle()

        assertNotDeclared()
    }

    // --- M1-07: permission state (pill, banner), wiring, scrollbar ----------------------------------
    // Fakes only: the checker is a local fake, the secret store is in memory. Text is asserted through the
    // tagged node or one of its descendants, so it does not depend on how the production code groups Text.

    private class FakePermissionChecker : PermissionChecker {
        var result: PermissionCheckResult = PermissionCheckResult.Checked(true, true)
        val calls = mutableListOf<ConnectionProfile>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            calls += profile
            return result
        }
    }

    private class PasswordStore : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName] ?: "fake-password"
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private val fakeChecker = FakePermissionChecker()

    private fun permissionViewModel(vararg seeds: ConnectionProfile): ConnectionsViewModel {
        repository = InMemoryRepository()
        seeds.forEach { repository.add(it) }
        val secrets = PasswordStore()
        return ConnectionsViewModel(
            repository, secrets, AlwaysReachableChecker(), AlwaysValidCertificate(), ConnectionSession(fakeChecker, secrets),
        )
    }

    private val prod = ConnectionProfile(name = "prod-eu", host = "rabbit.example", virtualHost = "/", username = "svc-explorer")

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertTagged(tag: String, text: String) {
        onNode(hasTestTag(tag).and(hasText(text).or(hasAnyDescendant(hasText(text)))), useUnmergedTree = true).assertExists(
            "expected a node tagged '$tag' showing '$text'",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.checkButton() {
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `not checked shows the neutral pill and the not-run banner`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("permissionPill:prod-eu", "Not checked")
        selectProfile(0)
        assertTagged("permissionBanner", "Permission check has not run yet.")
        assertEquals(0, fakeChecker.calls.size, "selecting must not run a check")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a verified unlocked connection shows the green pill and the banner`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Can change topology")
        assertTagged("permissionBanner", "svc-explorer can change topology on virtual host /.")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a verified locked connection shows Topology locked`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = true)
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Topology locked")
        assertTagged("permissionBanner", "svc-explorer cannot change topology on virtual host /.")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a declared result is worded as a claim`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        val declaring = prod.copy(declaredPermissions = DeclaredPermissions(configure = true, write = true))
        setContent { ConnectionsScreen(permissionViewModel(declaring)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Can change topology (declared)")
        assertTagged("permissionBanner", "You declared that svc-explorer can change topology and can publish test messages.")
        onNodeWithText("Your declaration is not used, because the broker answered.", useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a declaration that the broker overrode shows the dim line`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
        val declaring = prod.copy(declaredPermissions = DeclaredPermissions(configure = true, write = true))
        setContent { ConnectionsScreen(permissionViewModel(declaring)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Topology locked")
        assertTagged("permissionDimNote", "Your declaration is not used, because the broker answered.")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertUnreadableShown(cause: UnreadableCause) {
        fakeChecker.result = PermissionCheckResult.Unreadable(cause)
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Topology locked")
        assertTagged("permissionBanner", PermissionCheckResult.Unreadable(cause).userMessage)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a refused permission read shows locked with the reason`() = runDesktopComposeUiTest {
        assertUnreadableShown(UnreadableCause.NOT_AUTHORIZED)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unreachable management API shows locked with the reason`() = runDesktopComposeUiTest {
        assertUnreadableShown(UnreadableCause.UNREACHABLE)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unexpected error shows locked with the reason`() = runDesktopComposeUiTest {
        assertUnreadableShown(UnreadableCause.UNEXPECTED)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the button is renamed and the old label is gone`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        onNodeWithText("Check connection and permissions").performScrollTo().assertExists()
        onNodeWithText("Check connection").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the button runs the reachability check and the permission check`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        checkButton()

        onNodeWithText(passedBanner, substring = true).assertExists()
        assertEquals(1, fakeChecker.calls.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Save on a saved profile runs the permission check again`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        checkButton()
        assertEquals(1, fakeChecker.calls.size, "precondition")

        save()

        assertEquals(2, fakeChecker.calls.size, "a successful save of a saved profile re-checks")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `adding a new connection runs no permission check`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel()) }
        newConnectionButton().performClick()
        fillNewConnection()

        save()

        assertEquals(0, fakeChecker.calls.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `no profile card has a Select button and a click on the card selects it`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        onNodeWithText("Select").assertDoesNotExist()
        onNodeWithText("Edit connection").assertDoesNotExist()
        selectProfile(0)

        onNodeWithText("Edit connection").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the edit panel has a scrollbar`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        onNodeWithTag("editPanelScrollbar", useUnmergedTree = true).assertExists()
        onNodeWithText("Save").performScrollTo().assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the password field is masked`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        val passwordField = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        passwordField.assertExists("expected a password-masked field")
        onNodeWithText("Password").performTextInput("hunter2")

        // Not onNodeWithText("hunter2"): hasText also reads the test-only InputText property, which holds the raw
        // typed text on any field. EditableText is what the screen shows (and what accessibility sees): it must be
        // the bullet mask, same length as the input, and must not contain the typed text.
        val shown = passwordField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("\u2022".repeat("hunter2".length), shown, "the field must show bullets, not the typed password")
        assertFalse(shown.contains("hunter2"), "the typed password must not be visible in the field")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the New connection panel has no permission banner and no pill`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel()) }

        onNodeWithTag("permissionBanner", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Permission check has not run yet.", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Not checked", useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `starting a new connection after a saved one hides the permission banner`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        assertTagged("permissionBanner", "Permission check has not run yet.")

        newConnectionButton().performClick()
        waitForIdle()

        onNodeWithTag("permissionBanner", useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a saved profile that was never checked shows Not checked and the neutral banner`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("permissionPill:prod-eu", "Not checked")
        selectProfile(0)
        assertTagged("permissionPill:prod-eu", "Not checked")
        assertTagged("permissionBanner", "Permission check has not run yet.")
        assertEquals(0, fakeChecker.calls.size)
    }

    // --- Round 2: dim note outside the banner, keyboard focus on the card, single-line pill ----------

    private val dimText = "Your declaration is not used, because the broker answered."
    private val declaringProd get() = prod.copy(declaredPermissions = DeclaredPermissions(configure = true, write = true))

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.dimNoteNodes() = onAllNodesWithTag("permissionDimNote", useUnmergedTree = true)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the dim note is its own line under the dim note tag and not inside the banner`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
        setContent { ConnectionsScreen(permissionViewModel(declaringProd)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionDimNote", dimText)
        onNode(hasTestTag("permissionBanner").and(hasAnyDescendant(hasText(dimText))), useUnmergedTree = true)
            .assertDoesNotExist()
        onNode(hasTestTag("permissionBanner").and(hasAnyDescendant(hasTestTag("permissionDimNote"))), useUnmergedTree = true)
            .assertDoesNotExist()
        assertTagged("permissionBanner", "svc-explorer cannot change topology on virtual host /.")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `no dim note without a declaration`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionBanner", "svc-explorer can change topology on virtual host /.")
        dimNoteNodes().assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `no dim note before any check, even with a declaration`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(declaringProd)) }
        selectProfile(0)

        dimNoteNodes().assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `no dim note when the declaration is used`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        setContent { ConnectionsScreen(permissionViewModel(declaringProd)) }
        selectProfile(0)

        checkButton()

        assertTagged("permissionPill:prod-eu", "Can change topology (declared)")
        dimNoteNodes().assertCountEquals(0)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.card() = onNode(
        hasClickAction().and(hasAnyDescendant(hasTestTag("permissionPill:prod-eu"))),
        useUnmergedTree = true,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the profile card takes keyboard focus and Enter selects it`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        card().performSemanticsAction(SemanticsActions.RequestFocus)
        card().assertIsFocused()
        card().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        onNodeWithText("Edit connection").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the profile card takes keyboard focus and Space selects it`() = runDesktopComposeUiTest {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        card().performSemanticsAction(SemanticsActions.RequestFocus)
        card().assertIsFocused()
        card().performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()

        onNodeWithText("Edit connection").assertExists()
    }

    // --- M1-12a: layout (app bar, drawer, field grid), pill containment ------------------------------
    // Written before the code (red). Tags the production code must provide (names are the contract of this test):
    //   "appBar"      the app bar container of the AppFrame (title text "Connections" and the New connection button inside; M2-05b: was "connectionsAppBar")
    //   "appDrawer"      the drawer container of the AppFrame (brand, search, nav items; M2-05b: was "connectionsDrawer", the Connection card is gone)
    //   (M2-05b: the drawer's "drawerConnectionCard" and "drawerPermissionPill" are gone; the chip replaced them)
    //   "profileCard:<name>"     each profile card in the list (the click target, the pill's parent)
    // Layout is asserted at the mockup's preview size 1280x880.

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.nodeBounds(matcher: SemanticsMatcher, unmerged: Boolean = true): Rect =
        onNode(matcher, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private val gridLabelsInMockupOrder = listOf(
        "Name", "Virtual host", "Host", "Username", "Stream port (TLS)", "Management port (HTTPS)", "Password", "CA certificate",
    )

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.gridField(label: String): SemanticsNode {
        val isField = hasText(label)
        val nodes = onAllNodes(isField.and(hasSetTextAction().or(hasText("Stream port (TLS)").or(hasText("Management port (HTTPS)")))))
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size, "expected exactly one text field labelled '$label', found ${nodes.size}")
        return nodes.single()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the app bar shows the title Connections and a New connection button at its right`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("appBar", "Connections")
        val bar = nodeBounds(hasTestTag("appBar"))
        val title = nodeBounds(hasText("Connections").and(hasAnyAncestor(hasTestTag("appBar"))))
        // merged tree: the Button node carries the click action, its child Text carries the label
        val button = onNode(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("appBar"))))
            .fetchSemanticsNode().boundsInRoot
        assertTrue(title.left < button.left, "title must be left of the button: title $title, button $button")
        assertTrue(button.center.x > bar.center.x, "the button must sit in the right half of the app bar: bar $bar, button $button")
        assertTrue(bar.right - button.right <= 48f * 2, "the button must be at the right edge of the app bar: bar $bar, button $button")
        assertTrue(button.top >= bar.top && button.bottom <= bar.bottom, "the button must be inside the app bar: bar $bar, button $button")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the New connection button is only in the app bar, not in the profile list`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        onAllNodes(hasText("New connection").and(hasClickAction())).assertCountEquals(1)
        onAllNodes(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("appBar"))))
            .assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the app bar New connection button opens the empty panel`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        onNodeWithText("Edit connection").assertExists()

        onNode(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("appBar")))).performClick()
        waitForIdle()

        onNodeWithText("Edit connection").assertDoesNotExist()
        onNode(hasText("New connection").and(hasClickAction().not())).assertExists()
        val nameValue = onNode(hasText("Name").and(hasSetTextAction())).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("", nameValue, "the New connection panel must have an empty Name field")
        onNodeWithText("Delete profile").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the field labels are in the mockup order in two columns`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        val pos = gridLabelsInMockupOrder.associateWith { gridField(it).positionInRoot }
        val readingOrder = pos.entries.sortedWith(compareBy({ it.value.y }, { it.value.x })).map { it.key }
        assertEquals(gridLabelsInMockupOrder, readingOrder, "fields must read left to right, top to bottom in the mockup order; positions $pos")

        val left = listOf("Name", "Host", "Stream port (TLS)", "Password").map { pos.getValue(it) }
        val right = listOf("Virtual host", "Username", "Management port (HTTPS)", "CA certificate").map { pos.getValue(it) }
        assertEquals(1, left.map { it.x }.distinct().size, "left column must share one x: $left")
        assertEquals(1, right.map { it.x }.distinct().size, "right column must share one x: $right")
        assertTrue(right.first().x > left.first().x, "second column must be right of the first: $pos")
        // Same row = same y.
        assertEquals(pos.getValue("Name").y, pos.getValue("Virtual host").y, "Name and Virtual host share a row")
        assertEquals(pos.getValue("Host").y, pos.getValue("Username").y, "Host and Username share a row")
        assertEquals(pos.getValue("Stream port (TLS)").y, pos.getValue("Management port (HTTPS)").y, "the ports share a row")
        assertEquals(pos.getValue("Password").y, pos.getValue("CA certificate").y, "Password and CA certificate share a row")
        val passwordHeight = gridField("Password").boundsInRoot.height
        val caHeight = gridField("CA certificate").boundsInRoot.height
        assertEquals(passwordHeight, caHeight, "the Password and CA fields must have equal heights at 1280")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the field grid collapses to one column in a narrow window`() = runDesktopComposeUiTest(600, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        val pos = gridLabelsInMockupOrder.associateWith { gridField(it).positionInRoot }
        assertEquals(1, pos.values.map { it.x }.distinct().size, "narrow: every field in one column; positions $pos")
        val readingOrder = pos.entries.sortedBy { it.value.y }.map { it.key }
        assertEquals(gridLabelsInMockupOrder, readingOrder, "narrow: the order stays the mockup order; positions $pos")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the two port fields are read-only and show 5551 and 15671`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        fun check(label: String, value: String) {
            val config = gridField(label).config
            assertEquals(value, config.getOrNull(SemanticsProperties.EditableText)?.text, "'$label' must show $value")
            assertFalse(config.contains(SemanticsActions.SetText), "'$label' must be read-only (no set-text action)")
        }
        check("Stream port (TLS)", "5551")
        check("Management port (HTTPS)", "15671")
        // Sanity for the check itself: an editable field does offer set-text.
        assertTrue(gridField("Host").config.contains(SemanticsActions.SetText), "Host must stay editable")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the port fields show the ports of a profile that has others and stay read-only`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod.copy(streamPort = 6551, managementPort = 16671))) }
        selectProfile(0)

        assertEquals("6551", gridField("Stream port (TLS)").config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("16671", gridField("Management port (HTTPS)").config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertFalse(gridField("Stream port (TLS)").config.contains(SemanticsActions.SetText))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a new connection shows the standard ports, read-only`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel()) }

        assertEquals("5551", gridField("Stream port (TLS)").config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("15671", gridField("Management port (HTTPS)").config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertFalse(gridField("Management port (HTTPS)").config.contains(SemanticsActions.SetText))
    }

    // --- M2-05b: the drawer Connection card and the edit-panel Connect button are GONE ------------------
    // The chip in the app bar carries name, state and pill (ui `ConnectionControlUiTest`, `AppFrameUiTest`); the one place that connects is
    // the chip's popup. The M2-01 tests of the card and of the interim Connect button were replaced by the tests below.
    // Contract: the Connections screen is built with the AppFrame, so its container tags are the frame's: "appBar" (title "Connections",
    // the New connection button, the chip) and "appDrawer". The screen takes `control` and `controlEvents` (null = no chip).

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer has no Connection card, with or without a profile open in the panel`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        onNodeWithTag("drawerConnectionCard").assertDoesNotExist()
        onNode(hasText("No connection selected"), useUnmergedTree = true).assertDoesNotExist()

        selectProfile(0)

        onNodeWithTag("drawerConnectionCard").assertDoesNotExist()
        onNodeWithTag("drawerPermissionPill", useUnmergedTree = true).assertDoesNotExist()
        onNode(hasTestTag("appDrawer").and(hasAnyDescendant(hasText("rabbit.example"))), useUnmergedTree = true)
            .assertDoesNotExist() // the host was only in the card; it stays a row detail of the Connections screen
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the edit panel of a saved profile has no Connect button, and keeps Check connection and permissions`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        onNodeWithTag("connectButton").assertDoesNotExist()

        selectProfile(0)

        onNodeWithTag("connectButton").assertDoesNotExist()
        onNode(hasClickAction().and(hasText("Connect")), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Check connection and permissions").performScrollTo().assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the profile card state line stays a read-only summary and has no action`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        onNode(hasTestTag("profileCard:prod-eu").and(hasAnyDescendant(hasText("Connect").or(hasText("Disconnect")))), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the Connections screen carries the connection chip in its app bar next to New connection`() = runDesktopComposeUiTest(1280, 880) {
        val chip = dev.caerbannog.core.connections.ConnectionChipState(
            dev.caerbannog.core.connections.ChipState.NoConnection, dev.caerbannog.core.connections.ChipIcon.LinkOff,
            "No connection", null, null, null, "No connection. Open connection menu", false,
        )
        val control = dev.caerbannog.core.connections.ConnectionControlState(
            chip, emptyList(), "No saved connections",
            dev.caerbannog.core.connections.RefreshSectionState(dev.caerbannog.core.streams.RefreshInterval.Every5, false, "Connect to refresh"),
        )
        val events = object : dev.caerbannog.core.connections.ConnectionControlEvents {
            override fun connect(name: String) = Unit
            override fun disconnect(name: String) = Unit
            override fun retry(name: String) = Unit
            override fun setRefreshInterval(interval: dev.caerbannog.core.streams.RefreshInterval) = Unit
            override fun refreshNow() = Unit
            override fun openConnections() = Unit
        }
        setContent { ConnectionsScreen(permissionViewModel(prod), control, events) }

        onNode(hasTestTag("connectionChip").and(hasAnyAncestor(hasTestTag("appBar")))).assertExists("the chip is in the app bar of the Connections screen")
        val newConnection = nodeBounds(hasText("New connection").and(hasClickAction()), unmerged = false) // the button merges its Text; the click action lives on the merged node
        val chipBounds = nodeBounds(hasTestTag("connectionChip"))
        assertTrue(newConnection.right <= chipBounds.left + 0.5f, "the chip is last, after the screen's own action: $newConnection, $chipBounds")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer has the brand and the Connections nav item as text, and no Connection card label`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("appDrawer", "Caerbannog")
        assertTagged("appDrawer", "behind the rabbit")
        assertTagged("appDrawer", "Connections")
        onNode(hasText("Connection").and(hasAnyAncestor(hasTestTag("appDrawer"))), useUnmergedTree = true)
            .assertDoesNotExist() // exact text "Connection" was the card label
        val drawer = nodeBounds(hasTestTag("appDrawer"))
        assertTrue(drawer.left <= 1f && drawer.width in 270f..600f, "the drawer sits at the left edge, about 280dp wide: $drawer")
    }

    // The pill must stay inside the profile card and never wrap; the name gives way (one line, ellipsis).
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertPillContainedAndNameEllipsized() {
        val longName = "a-rather-long-profile-name-".repeat(6) + "end"
        fakeChecker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        val profile = prod.copy(name = longName, declaredPermissions = DeclaredPermissions(configure = true, write = true))
        setContent { ConnectionsScreen(permissionViewModel(profile)) }
        selectProfile(0)
        checkButton()

        val pillText = "Can change topology (declared)"
        val cardTag = hasTestTag("profileCard:$longName")
        val pillTag = hasTestTag("permissionPill:$longName")
        val card = nodeBounds(cardTag)
        val pill = nodeBounds(pillTag)
        assertTrue(pill.width > 0f, "the pill must have a width: $pill")
        assertTrue(pill.left >= card.left && pill.right <= card.right, "the pill must stay inside the card: card $card, pill $pill")
        assertTrue(pill.width < card.width, "the pill must be narrower than the card: card $card, pill $pill")

        fun layoutOf(textMatcher: SemanticsMatcher): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = onNode(textMatcher, useUnmergedTree = true).fetchSemanticsNode()
            val ok = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) ?: false
            assertTrue(ok, "the text node must expose its layout")
            return layouts.single()
        }
        val pillLayout = layoutOf(hasText(pillText).and(hasAnyAncestor(pillTag).or(pillTag)))
        assertEquals(1, pillLayout.lineCount, "the pill text '$pillText' must not wrap")
        assertFalse(pillLayout.isLineEllipsized(0), "the pill text must not be cut: it is the information")

        val nameLayout = layoutOf(hasText(longName).and(hasAnyAncestor(cardTag)))
        assertEquals(1, nameLayout.lineCount, "the profile name must stay on one line")
        // Ellipsis shows as visual overflow with the visible part shorter than the text (isLineEllipsized is
        // unreliable when the name only gets a few dp next to a wide pill).
        assertTrue(nameLayout.hasVisualOverflow, "a very long profile name must ellipsize, not push the pill out")
        assertTrue(
            nameLayout.getLineEnd(0, visibleEnd = true) < longName.length,
            "the visible part of the name must be shorter than the full name (ellipsized)",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with a very long name the pill stays inside the card on one line and the name ellipsizes`() =
        runDesktopComposeUiTest(1280, 880) { assertPillContainedAndNameEllipsized() }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `in a narrow window the pill still stays inside the card on one line and the name ellipsizes`() =
        runDesktopComposeUiTest(640, 880) { assertPillContainedAndNameEllipsized() }

    // --- M1-12a round 2: short labels, long text as single-line supporting text ----------------------

    private val passwordHint = "Blank keeps the saved password"
    private val caHint = "Blank uses the system trust store"

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.textNodes(text: String) =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the Password and CA labels are exactly the short mockup labels for a new and a saved profile`() {
        listOf(false, true).forEach { saved ->
            runDesktopComposeUiTest(1280, 880) {
                setContent { ConnectionsScreen(permissionViewModel(*if (saved) arrayOf(prod) else emptyArray())) }
                if (saved) selectProfile(0)

                val password = onAllNodes(hasText("Password").and(hasSetTextAction())).fetchSemanticsNodes()
                assertEquals(1, password.size, "saved=$saved: exactly one field labelled exactly 'Password'")
                val ca = onAllNodes(hasText("CA certificate").and(hasSetTextAction())).fetchSemanticsNodes()
                assertEquals(1, ca.size, "saved=$saved: exactly one field labelled exactly 'CA certificate'")
                assertEquals(0, textNodes("CA certificate (optional)").size, "saved=$saved: the '(optional)' label is gone")
                assertEquals(
                    0, onAllNodes(hasText("CA certificate (optional)", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size,
                    "saved=$saved: no node may mention 'CA certificate (optional)'",
                )
                assertEquals(0, textNodes("Password (leave blank to keep unchanged)").size, "saved=$saved: the long label is gone")
                assertEquals(
                    0, textNodes("CA certificate (optional - blank uses the system trust store)").size,
                    "saved=$saved: the long CA label is gone",
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a saved profile shows the keep-password hint under the Password field and a new one does not`() {
        runDesktopComposeUiTest(1280, 880) {
            setContent { ConnectionsScreen(permissionViewModel(prod)) }
            selectProfile(0)

            val hint = textNodes(passwordHint)
            assertEquals(1, hint.size, "saved profile: the hint '$passwordHint' must be shown once")
            val field = onNode(hasText("Password").and(hasSetTextAction())).fetchSemanticsNode()
            assertTrue(hint.single().positionInRoot.y >= field.positionInRoot.y, "the hint sits under the Password field")
            assertEquals(1, onAllNodes(hasText(passwordHint, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size)
            val layouts = mutableListOf<TextLayoutResult>()
            hint.single().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals(1, layouts.single().lineCount, "the hint is a single line")
            // lineCount == 1 also holds for an ellipsized line; the hint must fit the grid cell at 1280 wide.
            // hasVisualOverflow is true for any shrink-wrapped Text with Ellipsis, and isLineEllipsized stays false for a
            // truncated line here (measured), so the visible end of the line must reach the end of the text.
            assertFalse(layouts.single().isLineEllipsized(0), "the hint '$passwordHint' must not be ellipsized at 1280 wide")
            assertEquals(
                passwordHint.length, layouts.single().getLineEnd(0, visibleEnd = true),
                "the whole hint '$passwordHint' must be visible at 1280 wide, not cut with an ellipsis",
            )
        }
        runDesktopComposeUiTest(1280, 880) {
            setContent { ConnectionsScreen(permissionViewModel()) }
            assertEquals(0, textNodes(passwordHint).size, "new profile: no keep-password hint")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the CA supporting text is shown for a new and a saved profile on one line`() {
        listOf(false, true).forEach { saved ->
            runDesktopComposeUiTest(1280, 880) {
                setContent { ConnectionsScreen(permissionViewModel(*if (saved) arrayOf(prod) else emptyArray())) }
                if (saved) selectProfile(0)

                val hint = textNodes(caHint)
                assertEquals(1, hint.size, "saved=$saved: the hint '$caHint' must be shown once")
                val layouts = mutableListOf<TextLayoutResult>()
                hint.single().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                assertEquals(1, layouts.single().lineCount, "saved=$saved: the hint is a single line")
            }
        }
    }

    // --- M1-12b: Material icons and the password reveal toggle ---------------------------------------
    // Test hook contract for the developer: every icon carries testTag("icon:<mockup name>") (Material Symbols
    // snake_case name, e.g. icon:verified_user) and contentDescription = null, except the two password toggle
    // icons, which carry "Show password" / "Hide password". Icons are looked up in the unmerged tree under the
    // tagged container: permissionPill:<name>, drawerPermissionPill, permissionBanner, connectionsAppBar,
    // connectionsDrawer. No pixel tests; tags are plain strings, so these tests do not need the icon library.

    private val showPassword = "Show password"
    private val hidePassword = "Hide password"
    private val bullet = "\u2022"

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    /** Tags `icon:*` of all nodes inside the node tagged [containerTag] (unmerged tree), in tree order. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.iconTagsUnder(containerTag: String): List<String> =
        onAllNodes(
            SemanticsMatcher("icon node") { it.tag()?.startsWith("icon:") == true }.and(hasAnyAncestor(hasTestTag(containerTag))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.tag()!! }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.iconNodes(name: String): List<SemanticsNode> =
        onAllNodes(hasTestTag("icon:$name"), useUnmergedTree = true).fetchSemanticsNodes()

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.revealToggle(): SemanticsNodeInteraction =
        onNode(hasContentDescription(showPassword).or(hasContentDescription(hidePassword)), useUnmergedTree = true)

    private fun SemanticsNode.description(): List<String>? = config.getOrNull(SemanticsProperties.ContentDescription)

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.passwordShown(): String =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.typePassword(text: String) {
        onNodeWithText("Password").performTextInput(text)
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertToggle(expectedDescription: String, message: String) {
        val nodes = onAllNodes(hasContentDescription(showPassword).or(hasContentDescription(hidePassword)), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, nodes.size, "$message: exactly one reveal toggle expected, found ${nodes.size}")
        assertEquals(listOf(expectedDescription), nodes.single().description(), message)
    }

    // 1. Reveal toggle

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the password toggle starts masked for a new and for a saved profile`() {
        listOf(false, true).forEach { saved ->
            runDesktopComposeUiTest(1280, 880) {
                setContent { ConnectionsScreen(permissionViewModel(*if (saved) arrayOf(prod) else emptyArray())) }
                if (saved) selectProfile(0)

                assertToggle(showPassword, "saved=$saved: masked by default")
                assertEquals(1, iconNodes("visibility").size, "saved=$saved: the eye icon is shown while masked")
                assertEquals(0, iconNodes("visibility_off").size, "saved=$saved: no crossed-out eye while masked")
                typePassword("hunter2")
                assertEquals(bullet.repeat(7), passwordShown(), "saved=$saved: typed text is bullets only")
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking the toggle shows the typed password and Hide password, a second click masks it again`() =
        runDesktopComposeUiTest(1280, 880) {
            setContent { ConnectionsScreen(permissionViewModel(prod)) }
            selectProfile(0)
            typePassword("hunter2")

            revealToggle().performClick()
            waitForIdle()
            assertEquals("hunter2", passwordShown(), "after the click the typed text is shown")
            assertToggle(hidePassword, "shown state")
            assertEquals(1, iconNodes("visibility_off").size, "the crossed-out eye is shown while the password is visible")
            assertEquals(0, iconNodes("visibility").size)
            onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists("still a password field when shown")

            revealToggle().performClick()
            waitForIdle()
            assertEquals(bullet.repeat(7), passwordShown(), "the second click masks it again")
            assertToggle(showPassword, "masked again")
            onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertExists("still a password field when masked")
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the toggle is a keyboard focusable click target`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        // The description sits on the Icon inside the IconButton; the merged node is the button with the click action.
        onNode(hasClickAction().and(hasContentDescription(showPassword))).assertExists("the button carries the description and a click action")
    }

    // 2. Reset

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting another saved profile after revealing masks the password again`() = runDesktopComposeUiTest(1280, 880) {
        val a = prod
        val b = prod.copy(name = "uat-eu", host = "rabbit-uat.example")
        setContent { ConnectionsScreen(permissionViewModel(a, b)) }
        selectProfile(0)
        revealToggle().performClick()
        waitForIdle()
        assertToggle(hidePassword, "revealed on the first profile")

        selectProfile(1)

        assertToggle(showPassword, "the second profile starts masked")
        typePassword("s3cret")
        assertEquals(bullet.repeat(6), passwordShown(), "typed text on the second profile is masked")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `New connection pressed from a saved profile masks the password again`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        revealToggle().performClick()
        waitForIdle()
        assertToggle(hidePassword, "revealed on the saved profile")

        newConnectionButton().performClick()
        waitForIdle()

        assertToggle(showPassword, "the new form starts masked")
        typePassword("s3cret")
        assertEquals(bullet.repeat(6), passwordShown(), "typed text in the new form is masked")
    }

    // 3. The typed password is never in a node's text while masked

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.allVisibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            // Not InputText: that test-only property holds the raw typed text on any field.
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val editable = node.config.getOrNull(SemanticsProperties.EditableText)?.text
            text + listOfNotNull(editable)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `while masked the typed password is in no node's text`() = runDesktopComposeUiTest(1280, 880) {
        val secret = "hunter2-Zq9"
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        typePassword(secret)
        checkButton()

        allVisibleTexts().forEach { assertFalse(it.contains(secret), "the typed password must not appear in node text: '$it'") }

        revealToggle().performClick()
        waitForIdle()
        assertTrue(allVisibleTexts().any { it == secret }, "sanity: once revealed, the text does show it")

        revealToggle().performClick()
        waitForIdle()
        allVisibleTexts().forEach { assertFalse(it.contains(secret), "masked again: the password must not appear in node text: '$it'") }
    }

    // 4. Pill icons, 5. banner icons and the drawer pill

    private class GatedChecker : PermissionChecker {
        val gate = kotlinx.coroutines.CompletableDeferred<PermissionCheckResult>()
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult = gate.await()
    }

    private class SettableReachability(var result: ConnectionCheckResult) : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = result
    }

    private fun viewModelWith(
        reachability: BrokerReachabilityChecker,
        checker: PermissionChecker,
        vararg seeds: ConnectionProfile,
    ): ConnectionsViewModel {
        repository = InMemoryRepository()
        seeds.forEach { repository.add(it) }
        val secrets = PasswordStore()
        return ConnectionsViewModel(repository, secrets, reachability, AlwaysValidCertificate(), ConnectionSession(checker, secrets))
    }

    private data class IconCase(
        val label: String,
        val result: PermissionCheckResult?, // null = not checked
        val declared: DeclaredPermissions?,
        val expectedIcons: List<String>,
    )

    private val iconCases = listOf(
        IconCase("not checked", null, null, emptyList()),
        IconCase("Checked, unlocked", PermissionCheckResult.Checked(true, true), null, listOf("icon:verified_user")),
        IconCase("Checked, locked", PermissionCheckResult.Checked(false, true), null, listOf("icon:lock")),
        IconCase(
            "Declared, unlocked",
            PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED),
            DeclaredPermissions(configure = true, write = true),
            listOf("icon:help"),
        ),
        IconCase(
            "Declared, locked",
            PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED),
            DeclaredPermissions(configure = false, write = true),
            listOf("icon:help"),
        ),
        IconCase("Unreadable", PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED), null, listOf("icon:lock")),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the pill and the banner show the icon of their tone`() {
        iconCases.forEach { case ->
            runDesktopComposeUiTest(1280, 880) {
                fakeChecker.result = case.result ?: PermissionCheckResult.Checked(true, true)
                val profile = prod.copy(declaredPermissions = case.declared)
                setContent { ConnectionsScreen(permissionViewModel(profile)) }
                selectProfile(0)
                if (case.result != null) checkButton()

                assertEquals(case.expectedIcons, iconTagsUnder("permissionPill:prod-eu"), "${case.label}: list pill icons")
                assertEquals(case.expectedIcons, iconTagsUnder("permissionBanner"), "${case.label}: banner icons")
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Checking shows no icon on the pill or the banner`() = runDesktopComposeUiTest(1280, 880) {
        val gated = GatedChecker()
        setContent { ConnectionsScreen(viewModelWith(AlwaysReachableChecker(), gated, prod)) }
        selectProfile(0)
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()

        assertTagged("permissionPill:prod-eu", "Checking...")
        assertTagged("permissionBanner", "Checking permissions...")
        assertEquals(emptyList(), iconTagsUnder("permissionPill:prod-eu"), "Checking: pill has no icon")
        assertEquals(emptyList(), iconTagsUnder("permissionBanner"), "Checking: banner has no icon")
        gated.gate.complete(PermissionCheckResult.Checked(true, true))
    }

    // 6. Decorative icons expose no content description

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.describedNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true).fetchSemanticsNodes()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the only content descriptions on the Connections screen are Show password and Hide password`() =
        runDesktopComposeUiTest(1280, 880) {
            setContent { ConnectionsScreen(permissionViewModel(prod)) }
            selectProfile(0)
            checkButton()

            assertEquals(
                listOf(listOf(showPassword)), describedNodes().map { it.description() },
                "masked: the reveal toggle is the only node with a content description",
            )
            revealToggle().performClick()
            waitForIdle()
            assertEquals(
                listOf(listOf(hidePassword)), describedNodes().map { it.description() },
                "shown: the reveal toggle is the only node with a content description",
            )
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every decorative icon has no content description`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        checkButton()

        val icons = onAllNodes(SemanticsMatcher("icon node") { it.tag()?.startsWith("icon:") == true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(icons.size >= 8, "expected the screen's icons to be present, found ${icons.map { it.tag() }}")
        icons.filter { it.tag() !in setOf("icon:visibility", "icon:visibility_off") }.forEach {
            assertNull(it.description(), "${it.tag()} is decorative: contentDescription must be null")
        }
    }

    // 7. Static icons by tag

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer has the brand icon and the Connections nav icon`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        val inDrawer = iconTagsUnder("appDrawer")
        assertEquals(1, inDrawer.count { it == "icon:cruelty_free" }, "drawer brand icon, found $inDrawer")
        assertEquals(1, inDrawer.count { it == "icon:power" }, "drawer Connections nav icon, found $inDrawer")
        val brand = onNode(hasText("Caerbannog"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val brandIcon = iconNodes("cruelty_free").single().boundsInRoot
        assertTrue(brandIcon.right <= brand.left + 0.5f, "the brand icon sits before the text: icon $brandIcon, text $brand")
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.assertLeadingIcon(iconName: String, buttonText: String, scrollTo: Boolean = false) {
        if (scrollTo) onNode(hasClickAction().and(hasText(buttonText))).performScrollTo()
        val button = onNode(hasClickAction().and(hasText(buttonText))).fetchSemanticsNode().boundsInRoot
        fun inside(r: Rect) = r.left >= button.left - 0.5f && r.right <= button.right + 0.5f && r.top >= button.top - 0.5f && r.bottom <= button.bottom + 0.5f
        val icon = iconNodes(iconName).map { it.boundsInRoot }.filter(::inside)
        assertEquals(1, icon.size, "expected one icon:$iconName inside the '$buttonText' button $button")
        val text = onAllNodes(hasText(buttonText), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter(::inside)
        assertTrue(text.isNotEmpty(), "the '$buttonText' label must be inside its button")
        assertTrue(icon.single().right <= text.minOf { it.left } + 0.5f, "icon:$iconName must lead the '$buttonText' label: icon ${icon.single()}, text $text")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `New connection in the app bar has a leading add icon`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        assertEquals(listOf("icon:add"), iconTagsUnder("appBar"), "the app bar shows exactly the add icon")
        assertLeadingIcon("add", "New connection")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Delete profile and the check button have leading icons`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        assertLeadingIcon("delete", "Delete profile", scrollTo = true)
        assertLeadingIcon("wifi_tethering", "Check connection and permissions", scrollTo = true)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the CA field has a trailing upload icon and the Password field a trailing eye, both for new and saved`() {
        listOf(false, true).forEach { saved ->
            runDesktopComposeUiTest(1280, 880) {
                setContent { ConnectionsScreen(permissionViewModel(*if (saved) arrayOf(prod) else emptyArray())) }
                if (saved) selectProfile(0)

                fun assertTrailing(iconName: String, label: String) {
                    val field = gridField(label).boundsInRoot
                    val icons = iconNodes(iconName).map { it.boundsInRoot }
                        .filter { it.left >= field.left && it.right <= field.right + 0.5f && it.top >= field.top && it.bottom <= field.bottom + 0.5f }
                    assertEquals(1, icons.size, "saved=$saved: expected one icon:$iconName inside the '$label' field $field")
                    assertTrue(icons.single().left > field.center.x, "saved=$saved: icon:$iconName is trailing (right half of '$label')")
                }
                assertTrailing("upload_file", "CA certificate")
                assertTrailing("visibility", "Password")
                assertEquals(1, iconNodes("upload_file").size, "saved=$saved: the upload icon appears once")
                onNode(hasClickAction().and(hasAnyDescendant(hasTestTag("icon:upload_file")))).assertDoesNotExist()
            }
        }
    }

    // M2-14: the test `the TLS line has a lock icon before the text` ("TLS is always on") is replaced by `ConnectionsScreenTlsUiTest`.

    private fun checkResultCases(): List<Pair<ConnectionCheckResult, Boolean>> = listOf(
        ConnectionCheckResult.BothReachable to true,
        ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "connection refused") to false,
        ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, "timed out") to false,
        ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management") to false,
        ConnectionCheckResult.TlsRefused to false,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `check_circle shows after a BothReachable check and is absent for every failure`() {
        checkResultCases().forEach { (result, expectIcon) ->
            runDesktopComposeUiTest(1280, 880) {
                setContent { ConnectionsScreen(viewModelWith(SettableReachability(result), fakeChecker, prod)) }
                selectProfile(0)
                assertEquals(0, iconNodes("check_circle").size, "$result: no check_circle before any check")

                checkButton()

                assertEquals(if (expectIcon) 1 else 0, iconNodes("check_circle").size, "$result: check_circle count after the check")
                if (expectIcon) onNodeWithText(passedBanner, substring = true).assertExists()
            }
        }
    }

    // --- M1-12b: the button row wraps instead of squeezing Save --------------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `in a narrow panel the Save button is at least as wide as its text`() = runDesktopComposeUiTest(480, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        onNode(hasClickAction().and(hasText("Save"))).performScrollTo()

        val save = onNode(hasClickAction().and(hasText("Save"))).fetchSemanticsNode().boundsInRoot
        val textNodes = onAllNodes(hasText("Save"), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.left >= save.left - 0.5f && it.boundsInRoot.right <= save.right + 0.5f && it.boundsInRoot.top >= save.top - 0.5f }
        assertTrue(textNodes.isNotEmpty(), "the 'Save' label must sit inside the Save button $save")
        val layouts = mutableListOf<TextLayoutResult>()
        textNodes.first().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val textWidth = layouts.single().size.width.toFloat()
        assertTrue(save.width >= textWidth, "Save must not be squeezed: button width ${save.width} < text width $textWidth")
        assertEquals(1, layouts.single().lineCount, "the Save label must stay on one line")
    }

    // M2-05c step 2: the drawer of the Connections screen reports navigation (tags `navItem:<Name>`, see AppFrameNavigationUiTest).
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the Connections screen drawer reports the chosen destination and marks Connections selected`() = runDesktopComposeUiTest {
        val seen = mutableListOf<dev.caerbannog.ui.appframe.AppDestination>()
        setContent { ConnectionsScreen(testViewModel(), onNavigate = { seen += it }) }

        onNodeWithTag("navItem:Connections").assertIsSelected()
        onNodeWithTag("navItem:Streams").performClick()

        assertEquals(listOf(dev.caerbannog.ui.appframe.AppDestination.Streams), seen)
    }
}
