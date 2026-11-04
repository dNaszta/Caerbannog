package dev.caerbannog.ui.connections

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.CaCertificateValidator
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.DeclaredPermissions
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.SecretStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * M1-12a: opt-in screenshot helper (tag `screenshot`, like `keychain`). Renders the Connections screen at the
 * mockup's preview size 1280x880 and writes PNGs to `ui/build/screenshots/` so the user can compare them with
 * `docs/ux/Main.dc.html` by eye. No pixel comparison, no golden files: the test only asserts that each file
 * exists and is not empty. Fakes only (in-memory repository, in-memory secrets, fake checkers): it never touches
 * the real ~/.caerbannog or the OS keychain.
 */
@Tag("screenshot")
class ConnectionsScreenshotTest {

    private class Repo : ConnectionProfileRepository {
        private val profiles = linkedMapOf<String, ConnectionProfile>()
        override fun list(): List<ConnectionProfile> = profiles.values.toList()
        override fun add(profile: ConnectionProfile): SaveConnectionResult {
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

    private class Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult = ConnectionCheckResult.BothReachable
    }

    private class AnyCertificate : CaCertificateValidator {
        override fun validate(filePath: String) = CaCertificateValidationResult.Valid
    }

    /** Green for "uat-eu"; "prod-eu" cannot be read (red); "dev-eu" cannot be read but declares (amber). */
    private class PerProfileChecker : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult =
            if (profile.name == "prod-eu" || profile.name == "dev-eu") PermissionCheckResult.Unreadable(dev.caerbannog.core.connections.UnreadableCause.NOT_AUTHORIZED)
            else PermissionCheckResult.Checked(true, true)
    }

    private val outDir = File(System.getProperty("user.dir"), "build/screenshots")

    private val prodEu = ConnectionProfile(name = "prod-eu", host = "rabbit-prod.example.com", virtualHost = "/", username = "svc-explorer")
    private val uatEu = ConnectionProfile(name = "uat-eu", host = "rabbit-uat.example.com", virtualHost = "/", username = "svc-explorer")
    private val devEu = ConnectionProfile(
        name = "dev-eu", host = "rabbit-dev.example.com", virtualHost = "/", username = "svc-explorer",
        declaredPermissions = DeclaredPermissions(configure = true, write = false),
    )
    private val ciEu = ConnectionProfile(name = "ci-eu", host = "rabbit-ci.example.com", virtualHost = "/", username = "svc-explorer")

    private fun viewModel(vararg seeds: ConnectionProfile): ConnectionsViewModel {
        val repo = Repo()
        seeds.forEach { repo.add(it) }
        val secrets = Secrets()
        return ConnectionsViewModel(repo, secrets, Reachable(), AnyCertificate(), ConnectionSession(PerProfileChecker(), secrets))
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.snapshot(fileName: String): File {
        waitForIdle()
        // A dialog adds a second root (same size as the window); the first root is the window's scene.
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        outDir.mkdirs()
        val file = File(outDir, fileName)
        check(ImageIO.write(image, "png", file)) { "no PNG writer available for $fileName" }
        return file
    }

    private fun assertWritten(file: File) {
        assertTrue(file.exists(), "expected screenshot ${file.path} to exist")
        assertTrue(file.length() > 0, "expected screenshot ${file.path} to be non-empty")
    }

    /** Clicks the n-th profile card through its pill (same trick as ConnectionsScreenUiTest.selectProfile). */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.selectProfile(index: Int) {
        onAllNodes(
            SemanticsMatcher("permission pill") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("permissionPill:") == true
            },
            useUnmergedTree = true,
        )[index].performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.runCheck() {
        onNodeWithText("Check connection and permissions").performScrollTo().performClick()
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `screenshot - empty list`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(viewModel()) }

        assertWritten(snapshot("connections-01-empty.png"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `screenshot - three profiles with one selected`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(viewModel(prodEu, uatEu, ciEu, devEu)) }
        selectProfile(0); runCheck() // prod-eu: red
        selectProfile(1); runCheck() // uat-eu: green
        selectProfile(3); runCheck() // dev-eu: amber (declared)
        selectProfile(1)             // selected: uat-eu; ci-eu stays neutral
        onNodeWithText("Edit connection").performScrollTo() // panel back at its top

        assertWritten(snapshot("connections-02-profiles.png"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `screenshot - edit panel scrolled to the permission banner`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(viewModel(prodEu, uatEu)) }
        selectProfile(0)
        runCheck()
        onNodeWithTag("permissionBanner").performScrollTo()

        assertWritten(snapshot("connections-03-banner.png"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `screenshot - delete dialog`() = runDesktopComposeUiTest(1280, 880) {
        setContent { ConnectionsScreen(viewModel(prodEu, uatEu)) }
        selectProfile(1)
        onNodeWithText("Delete profile").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Delete connection").assertExists()

        assertWritten(snapshot("connections-04-delete-dialog.png"))
    }
}
