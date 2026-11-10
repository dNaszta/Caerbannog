package dev.caerbannog.ui.connections

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrations.HistoryKeyResult
import dev.caerbannog.core.migrations.ProfileHistoryKeys
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M5-08c2 scope 3: the Connections screen shows "The migration history of this connection could not be updated, so the connection was left as it
 * is" (tag `connections:historyNotUpdatedBanner`) when a rename (Save) or a delete cannot update the history. The REAL view model runs behind
 * the screen; only the history keys fail. The delete dialog closes before the banner appears; the profile is untouched; the banner is drawn once
 * and goes away with the next selection. Mockup: `docs/ux/Main.dc.html`, last card.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectionsScreenHistoryBannerTest {

    private val message = "The migration history of this connection could not be updated, so the connection was left as it is"
    private val tag = "connections:historyNotUpdatedBanner"

    private class Repo : ConnectionProfileRepository {
        val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list() = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
            if (profiles.containsKey(profile.name)) return SaveConnectionResult.NameAlreadyExists(profile.name)
            profiles[profile.name] = profile
            return SaveConnectionResult.Saved(profile)
        }
        override fun update(profile: ConnectionProfile): SaveConnectionResult { profiles[profile.name] = profile; return SaveConnectionResult.Saved(profile) }
        override fun delete(name: String) { profiles.remove(name) }
    }

    private class NoSecrets : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = null
        override fun delete(profileName: String) = Unit
    }

    private class Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private class ValidCa : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    private class History(var failing: Boolean) : ProfileHistoryKeys {
        override fun rename(oldName: String, newName: String) = if (failing) HistoryKeyResult.Failed("x") else HistoryKeyResult.Done
        override fun delete(profileName: String) = if (failing) HistoryKeyResult.Failed("x") else HistoryKeyResult.Done
    }

    private val repo = Repo()
    private val history = History(failing = true)

    private fun viewModel(vararg names: String): ConnectionsViewModel {
        names.forEach { repo.add(ConnectionProfile(name = it, host = "$it.example.com", virtualHost = "/", username = "svc")) }
        return ConnectionsViewModel(repo, NoSecrets(), Reachable(), ValidCa(), historyKeys = history)
    }

    private fun DesktopComposeUiTest.selectProfile(index: Int) {
        onAllNodes(SemanticsMatcher("permission pill") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("permissionPill:") == true }, useUnmergedTree = true)[index].performClick()
        waitForIdle()
    }

    private fun SemanticsNode.allText(): String =
        (listOf(config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()) + children.map { it.allText() }).filter { it.isNotEmpty() }.joinToString(" ")

    private fun DesktopComposeUiTest.bannerText(): String? =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.allText()

    private fun DesktopComposeUiTest.confirmDelete() {
        onNodeWithText("Delete profile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Yes").performClick()
        waitForIdle()
    }

    @Test
    fun `a delete whose history cannot be updated closes the dialog, shows the banner and keeps the profile`(): Unit = runDesktopComposeUiTest {
        val vm = viewModel("uat-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)

        confirmDelete()

        onAllNodesWithText("Delete connection").assertCountEquals(0)
        assertEquals(message, bannerText(), "the banner must say exactly this")
        onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(listOf("uat-eu"), repo.profiles.keys.toList(), "the profile was deleted although its history could not be updated")
    }

    @Test
    fun `a rename whose history cannot be updated shows the banner and keeps the old name`(): Unit = runDesktopComposeUiTest {
        val vm = viewModel("uat-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)

        onNode(hasSetTextAction() and hasText("uat-eu")).performTextReplacement("uat-eu-2")
        onNodeWithText("Save").performScrollTo().performClick()
        waitForIdle()

        assertEquals(message, bannerText())
        onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(listOf("uat-eu"), repo.profiles.keys.toList())
    }

    @Test
    fun `the banner is not drawn before anything is refused`(): Unit = runDesktopComposeUiTest {
        val vm = viewModel("uat-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)

        assertEquals(null, bannerText())
    }

    @Test
    fun `a delete whose history is updated deletes without a banner`(): Unit = runDesktopComposeUiTest {
        history.failing = false
        val vm = viewModel("uat-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)

        confirmDelete()

        assertEquals(null, bannerText())
        assertTrue(repo.profiles.isEmpty())
    }

    @Test
    fun `selecting another profile takes the banner away`(): Unit = runDesktopComposeUiTest {
        val vm = viewModel("uat-eu", "prod-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)
        confirmDelete()
        assertEquals(message, bannerText(), "precondition: the banner is shown")

        selectProfile(1)

        assertEquals(null, bannerText(), "the banner stays only until the next Save, Delete or selection")
    }

    @Test
    fun `trying again after the history works succeeds without the banner`(): Unit = runDesktopComposeUiTest {
        val vm = viewModel("uat-eu")
        setContent { ConnectionsScreen(vm) }
        selectProfile(0)
        confirmDelete()
        history.failing = false

        onNodeWithText("Delete profile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Yes").performClick()
        waitForIdle()

        assertEquals(null, bannerText())
        assertTrue(repo.profiles.isEmpty())
    }
}
