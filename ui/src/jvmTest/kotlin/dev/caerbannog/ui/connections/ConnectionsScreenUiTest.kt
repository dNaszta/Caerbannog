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
    //   "connectionsAppBar"      the app bar container (title text "Connections" and the New connection button inside)
    //   "connectionsDrawer"      the drawer container (brand, Connection card, nav item inside)
    //   "drawerConnectionCard"   the drawer's Connection card (label, profile name, host, pill inside)
    //   "drawerPermissionPill"   the pill inside that card (NOT "permissionPill:<name>", which is the list card's pill)
    //   "profileCard:<name>"     each profile card in the list (the click target, the pill's parent)
    // Layout is asserted at the mockup's preview size 1280x880.

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.nodeBounds(matcher: SemanticsMatcher): Rect =
        onNode(matcher, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val gridLabelsInMockupOrder = listOf(
        "Name", "Virtual host", "Host", "Username", "Stream port (TLS)", "Management port (HTTPS)", "Password", "CA certificate (optional)",
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

        assertTagged("connectionsAppBar", "Connections")
        val bar = nodeBounds(hasTestTag("connectionsAppBar"))
        val title = nodeBounds(hasText("Connections").and(hasAnyAncestor(hasTestTag("connectionsAppBar"))))
        // merged tree: the Button node carries the click action, its child Text carries the label
        val button = onNode(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("connectionsAppBar"))))
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
        onAllNodes(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("connectionsAppBar"))))
            .assertCountEquals(1)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the app bar New connection button opens the empty panel`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        onNodeWithText("Edit connection").assertExists()

        onNode(hasText("New connection").and(hasClickAction()).and(hasAnyAncestor(hasTestTag("connectionsAppBar")))).performClick()
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
        val right = listOf("Virtual host", "Username", "Management port (HTTPS)", "CA certificate (optional)").map { pos.getValue(it) }
        assertEquals(1, left.map { it.x }.distinct().size, "left column must share one x: $left")
        assertEquals(1, right.map { it.x }.distinct().size, "right column must share one x: $right")
        assertTrue(right.first().x > left.first().x, "second column must be right of the first: $pos")
        // Same row = same y.
        assertEquals(pos.getValue("Name").y, pos.getValue("Virtual host").y, "Name and Virtual host share a row")
        assertEquals(pos.getValue("Host").y, pos.getValue("Username").y, "Host and Username share a row")
        assertEquals(pos.getValue("Stream port (TLS)").y, pos.getValue("Management port (HTTPS)").y, "the ports share a row")
        assertEquals(pos.getValue("Password").y, pos.getValue("CA certificate (optional)").y, "Password and CA certificate share a row")
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

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer shows the selected profile's name, host and pill`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)

        assertTagged("drawerConnectionCard", "Connection")
        assertTagged("drawerConnectionCard", "prod-eu")
        assertTagged("drawerConnectionCard", "rabbit.example")
        assertTagged("drawerPermissionPill", "Not checked")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer pill follows the permission check of the selected profile`() = runDesktopComposeUiTest(1280, 880) {
        fakeChecker.result = PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED)
        setContent { ConnectionsScreen(permissionViewModel(prod)) }
        selectProfile(0)
        checkButton()

        assertTagged("drawerPermissionPill", "Topology locked")
        assertTagged("permissionPill:prod-eu", "Topology locked")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer shows the other profile after the selection changes`() = runDesktopComposeUiTest(1280, 880) {
        val dev = ConnectionProfile(name = "dev-eu", host = "rabbit-dev.example", virtualHost = "/", username = "svc")
        setContent { ConnectionsScreen(permissionViewModel(prod, dev)) }
        selectProfile(0)
        selectProfile(1)

        assertTagged("drawerConnectionCard", "dev-eu")
        assertTagged("drawerConnectionCard", "rabbit-dev.example")
        onNode(hasTestTag("drawerConnectionCard").and(hasAnyDescendant(hasText("prod-eu"))), useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer says No connection selected when nothing is selected`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("drawerConnectionCard", "No connection selected")
        onNodeWithTag("drawerPermissionPill", useUnmergedTree = true).assertDoesNotExist()

        selectProfile(0)
        onNode(hasText("No connection selected"), useUnmergedTree = true).assertDoesNotExist()

        newConnectionButton().performClick()
        waitForIdle()
        assertTagged("drawerConnectionCard", "No connection selected")
        onNodeWithTag("drawerPermissionPill", useUnmergedTree = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer has the brand, the Connection card and one Connections nav item as text`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        assertTagged("connectionsDrawer", "Caerbannog")
        assertTagged("connectionsDrawer", "behind the rabbit")
        assertTagged("connectionsDrawer", "Connection")
        assertTagged("connectionsDrawer", "Connections")
        val drawer = nodeBounds(hasTestTag("connectionsDrawer"))
        assertTrue(drawer.left <= 1f && drawer.width in 270f..600f, "the drawer sits at the left edge, about 280dp wide: $drawer")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer has no Search field and no Streams, Topology or Migrations items in M1`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(permissionViewModel(prod)) }

        listOf("Search streams, queues, exchanges", "Streams", "Topology", "Migrations").forEach { text ->
            onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
        }
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

    private val passwordHint = "Leave blank to keep the saved password"
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
                val ca = onAllNodes(hasText("CA certificate (optional)").and(hasSetTextAction())).fetchSemanticsNodes()
                assertEquals(1, ca.size, "saved=$saved: exactly one field labelled exactly 'CA certificate (optional)'")
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
}
