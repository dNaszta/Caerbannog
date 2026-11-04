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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
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
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private val passedBanner = "Connection test passed"

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `saving a new connection under an existing name shows an inline error naming it`() = runDesktopComposeUiTest {
        val existing = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(existing)) }

        onAllNodesWithText("New connection")[0].performClick()
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

        onNodeWithText("New connection").performClick()
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
    //  - ONE switch (Role.Switch), labelled "I know what this user may do on the broker", below "Topology control".
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
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performScrollTo()

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

        onNodeWithText("New connection").performClick()
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
        onAllNodesWithText("New connection")[0].performClick()
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
        onNodeWithText("Password (leave blank to keep unchanged)").performTextInput("hunter2")

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

        onAllNodesWithText("New connection")[0].performClick()
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the long declared pill text stays on one line`() = runDesktopComposeUiTest {
        fakeChecker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        val longName = prod.copy(
            name = "a-rather-long-profile-name-for-a-narrow-card",
            declaredPermissions = DeclaredPermissions(configure = true, write = true),
        )
        setContent { ConnectionsScreen(permissionViewModel(longName)) }
        selectProfile(0)
        checkButton()

        val text = "Can change topology (declared)"
        val node = onNode(
            hasText(text).and(hasAnyAncestor(hasTestTag("permissionPill:${longName.name}")).or(hasTestTag("permissionPill:${longName.name}"))),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val ok = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) ?: false
        assertEquals(true, ok, "the pill text must expose its layout")
        assertEquals(1, layouts.single().lineCount, "the pill text '$text' must not wrap in the 280dp card")
    }
}
