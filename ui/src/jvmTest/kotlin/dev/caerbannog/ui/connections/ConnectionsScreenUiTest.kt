package dev.caerbannog.ui.connections

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performKeyInput
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
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import kotlin.test.assertEquals
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
        onNodeWithText("Permission check has not run yet.").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `selecting a saved profile shows its fields in the editing panel`() = runDesktopComposeUiTest {
        val profile = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
        setContent { ConnectionsScreen(testViewModel(profile)) }

        onNodeWithText("Select").performClick()

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

        onAllNodesWithText("Select")[0].performClick()
        onNodeWithText("Check connection").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(passedBanner, substring = true).assertExists()

        onAllNodesWithText("Select")[1].performClick()
        waitForIdle()

        onNodeWithText("Edit connection").assertExists()
        onNodeWithText(passedBanner, substring = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking New connection clears the previous profile's check-connection banner`() = runDesktopComposeUiTest {
        val a = ConnectionProfile(name = "profile-a", host = "a.example.com", virtualHost = "/", username = "u")
        setContent { ConnectionsScreen(testViewModel(a)) }

        onNodeWithText("Select").performClick()
        onNodeWithText("Check connection").performScrollTo().performClick()
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
        onNodeWithText("Select").performClick()
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
        onNodeWithText("Select").performClick()
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
        onNodeWithText("Select").performClick()
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
        onNodeWithText("Select").performClick()
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

        onAllNodesWithText("Select")[0].performClick()
        waitForIdle()
        pick(declareAndEdit) // unsaved change on profile-a
        assertSelectedLevel(declareAndEdit)

        onAllNodesWithText("Select")[1].performClick()
        waitForIdle()
        assertSelectedLevel(declareAndEdit) // profile-b's own saved level
        onAllNodesWithText("Select")[0].performClick()
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
        onNodeWithText("Select").performClick()
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

        onAllNodesWithText("Select")[0].performClick()
        waitForIdle()
        assertDeclared(configure = true, write = false)
        radio(writeYes).performScrollTo().performClick() // unsaved change on profile-a
        waitForIdle()

        onAllNodesWithText("Select")[1].performClick()
        waitForIdle()
        assertNotDeclared()

        onAllNodesWithText("Select")[0].performClick()
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
}
