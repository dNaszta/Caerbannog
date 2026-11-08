package dev.caerbannog.acceptance.connections

import dev.caerbannog.core.connections.BrokerEndpoints
import dev.caerbannog.core.connections.BrokerPorts
import dev.caerbannog.core.connections.CaCertificateValidationResult
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.ConnectionsViewModel
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.Endpoint
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.checkResultMessage
import io.cucumber.datatable.DataTable
import dev.caerbannog.core.connections.fakes.GatedCaCertificateValidator
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail

/**
 * Step definitions for tests/bdd/features/01_connections.feature, covering every scenario named "in"
 * by docs/tasks/M1-02-connection-profiles.md's Acceptance list EXCEPT "Switch between connections"
 * (deliberately deferred, see SwitchBetweenConnectionsSteps.kt) and everything in
 * 02_permission_check.feature (a different task, M1-03, not touched here).
 *
 * Expected to fail to compile right now: none of the `dev.caerbannog.core.connections.*` types this
 * file imports exist yet in `core`, and `ConnectionsViewModel` does not exist either. This is the
 * intended "red" state - see Fakes.kt's header for the full list of missing production types.
 *
 * Package-private state is reset per scenario via @Before, since cucumber-java creates a fresh
 * instance of this class for every scenario (no shared state leaks between scenarios).
 */
class ConnectionsSteps {

    private lateinit var repository: InMemoryConnectionProfileRepository
    private lateinit var secretStore: FakeSecretStore
    private lateinit var checker: FakeBrokerReachabilityChecker
    private lateinit var caValidator: FakeCaCertificateValidator
    private lateinit var viewModel: ConnectionsViewModel

    private var lastProfileName: String? = null
    private var lastPassword: String? = null
    private var lastSaveResult: SaveConnectionResult? = null
    private var lastCheckResult: ConnectionCheckResult? = null
    private var lastCaCertificateResult: CaCertificateValidationResult? = null

    @Before
    fun resetFakes() {
        repository = InMemoryConnectionProfileRepository()
        secretStore = FakeSecretStore()
        checker = FakeBrokerReachabilityChecker()
        caValidator = FakeCaCertificateValidator()
        viewModel = ConnectionsViewModel(repository, secretStore, checker, caValidator)
    }

    // --- Background -----------------------------------------------------

    @Given("the app is started with no saved connections")
    fun theAppIsStartedWithNoSavedConnections() {
        assertTrue(viewModel.state.value.profiles.isEmpty()) {
            "Expected a freshly started app to have no saved connections, found ${viewModel.state.value.profiles}"
        }
    }

    // --- Adding / saving --------------------------------------------------

    @When("I add a connection with these settings")
    fun iAddAConnectionWithTheseSettings(table: DataTable) {
        val fields = table.asMap(String::class.java, String::class.java)
        // Like the form: the ports start at the TLS on defaults and follow the switch while they are still the defaults.
        val tls = fields["tls"]?.let(::tlsFromWord)
        val ports = BrokerEndpoints.afterTlsSwitch(BrokerEndpoints.defaultsFor(true), tlsNow = tls ?: true)
        val input = ConnectionProfileInput(
            name = fields.getValue("name"),
            host = fields.getValue("host"),
            virtualHost = fields.getValue("virtual host"),
            username = fields.getValue("username"),
            password = fields["password"],
            streamPort = ports.stream,
            managementPort = ports.management,
            tls = tls,
            // Optional row: absent means "no level given" (null), which on add must become Read-only.
            topologyControlLevel = fields["topology control level"]?.let(::topologyControlLevelFromLabel),
        )
        lastProfileName = input.name
        lastPassword = input.password
        lastSaveResult = viewModel.addConnection(input)
    }

    @Given("a saved connection {string}")
    fun aSavedConnection(name: String) {
        addDefaultConnection(name, password = "s3cret")
    }

    @Given("a saved connection {string} with password {string}")
    fun aSavedConnectionWithPassword(name: String, password: String) {
        addDefaultConnection(name, password = password)
    }

    @Given("saved connections {string} and {string}")
    fun savedConnectionsAAndB(first: String, second: String) {
        addDefaultConnection(first, password = "s3cret")
        addDefaultConnection(second, password = "s3cret")
    }

    private fun addDefaultConnection(name: String, password: String) {
        val input = ConnectionProfileInput(
            name = name,
            host = "rabbit-${name}.example.com",
            virtualHost = "/",
            username = "svc-explorer",
            password = password,
        )
        lastProfileName = name
        lastPassword = password
        val result = viewModel.addConnection(input)
        check(result is SaveConnectionResult.Saved) {
            "Test setup failed: could not save fixture connection '$name': $result"
        }
    }

    // --- M2-14: the TLS switch ---------------------------------------------------------------------------------------

    private fun tlsFromWord(word: String): Boolean = when (word) {
        "on" -> true
        "off" -> false
        else -> error("TLS is 'on' or 'off' in a scenario, got '$word'")
    }

    private fun addConnectionWith(name: String, tls: Boolean, ports: BrokerPorts, ca: String? = null) {
        val result = viewModel.addConnection(
            ConnectionProfileInput(
                name = name, host = "rabbit-$name.example.com", virtualHost = "/", username = "svc-explorer", password = "s3cret",
                streamPort = ports.stream, managementPort = ports.management, tls = tls,
            ),
        )
        check(result is SaveConnectionResult.Saved) { "Test setup failed: could not save '$name': $result" }
        lastProfileName = name
        lastPassword = "s3cret"
        if (ca != null) {
            // Seeded behind the view model like a saved file: the form never validates a CA path while TLS is off.
            repository.update(repository.list().first { it.name == name }.copy(caCertificatePath = ca))
        }
    }

    @Given("a saved connection {string} with TLS {word}")
    fun aSavedConnectionWithTls(name: String, word: String) {
        val tls = tlsFromWord(word)
        addConnectionWith(name, tls, BrokerEndpoints.defaultsFor(tls))
    }

    @Given("a saved connection {string} with TLS {word} and the CA certificate path {string}")
    fun aSavedConnectionWithTlsAndCa(name: String, word: String, ca: String) {
        val tls = tlsFromWord(word)
        addConnectionWith(name, tls, BrokerEndpoints.defaultsFor(tls), ca)
    }

    @Given("a saved connection {string} with TLS {word}, stream port {int} and management port {int}")
    fun aSavedConnectionWithTlsAndPorts(name: String, word: String, stream: Int, management: Int) {
        addConnectionWith(name, tlsFromWord(word), BrokerPorts(stream, management))
    }

    @When("I turn TLS {word} for {string} and save")
    fun iTurnTlsAndSave(word: String, name: String) {
        val tls = tlsFromWord(word)
        val saved = repository.list().first { it.name == name }
        lastProfileName = name
        // What the form does on the switch: the ports follow while they are the default of the old mode; Save sends the form's values.
        val ports = BrokerEndpoints.afterTlsSwitch(BrokerPorts(saved.streamPort, saved.managementPort), tlsNow = tls)
        lastSaveResult = viewModel.updateConnection(
            originalName = name,
            input = ConnectionProfileInput(
                name = saved.name, host = saved.host, virtualHost = saved.virtualHost, username = saved.username, password = null,
                streamPort = ports.stream, managementPort = ports.management, tls = tls,
            ),
        )
    }

    @Then("its TLS switch is {word}")
    fun itsTlsSwitchIs(word: String) {
        assertEquals(tlsFromWord(word), currentProfile().tls, "TLS switch of '$lastProfileName'")
    }

    @Then("the saved CA certificate path of {string} is still {string}")
    fun theSavedCaPathIsStill(name: String, path: String) {
        assertEquals(path, repository.list().first { it.name == name }.caCertificatePath)
    }

    @Then("no message about the CA certificate is shown")
    fun noMessageAboutTheCa() {
        assertEquals(null, viewModel.state.value.lastCaCertificateValidationResult, "a CA validation result is on screen")
        assertEquals(emptyList<String>(), caValidator.validated, "the CA validator was asked about: ${caValidator.validated}")
    }

    @When("I set verify host name of {string} to {word}")
    fun iSetVerifyHostNameTo(name: String, word: String) {
        val verify = word == "on"
        val saved = repository.list().first { it.name == name }
        lastProfileName = name
        lastSaveResult = viewModel.updateConnection(
            originalName = name,
            input = ConnectionProfileInput(
                name = saved.name,
                host = saved.host,
                virtualHost = saved.virtualHost,
                username = saved.username,
                password = null,
                streamPort = saved.streamPort,
                managementPort = saved.managementPort,
                tls = saved.tls,
                verifyHostName = verify,
            ),
        )
    }

    @Then("the saved profile {string} has verify host name enabled")
    fun assertVerifyHostNameEnabled(name: String) {
        val profile = repository.list().first { it.name == name }
        assertTrue(profile.verifyHostName, "Expected verifyHostName=true on profile '$name'")
    }

    @When("I try to save another connection named {string}")
    fun iTryToSaveAnotherConnectionNamed(name: String) {
        // A genuinely different profile (different host) that happens to reuse an existing name -
        // the point of this scenario is the name collision, not an identical duplicate.
        val input = ConnectionProfileInput(
            name = name,
            host = "a-different-host.example.com",
            virtualHost = "/",
            username = "someone-else",
            password = "different-password",
        )
        lastSaveResult = viewModel.addConnection(input)
    }

    @Then("the connection {string} appears in the saved profiles")
    fun theConnectionAppearsInTheSavedProfiles(name: String) {
        assertTrue(viewModel.state.value.profiles.any { it.name == name }) {
            "Expected '$name' among saved profiles, found ${viewModel.state.value.profiles.map { it.name }}"
        }
    }

    @Then("the connection {string} is no longer in the saved profiles")
    fun theConnectionIsNoLongerInTheSavedProfiles(name: String) {
        assertFalse(viewModel.state.value.profiles.any { it.name == name }) {
            "Expected '$name' to have been deleted, but it is still in ${viewModel.state.value.profiles.map { it.name }}"
        }
    }

    @Then("the connection {string} is still in the saved profiles")
    fun theConnectionIsStillInTheSavedProfiles(name: String) = theConnectionAppearsInTheSavedProfiles(name)

    @Then("its stream port is {int}")
    fun itsStreamPortIs(port: Int) {
        assertEquals(port, currentProfile().streamPort)
    }

    @Then("its management port is {int}")
    fun itsManagementPortIs(port: Int) {
        assertEquals(port, currentProfile().managementPort)
    }

    @Then("the app refuses to save it")
    fun theAppRefusesToSaveIt() {
        assertTrue(lastSaveResult is SaveConnectionResult.NameAlreadyExists) {
            "Expected the save to be refused with NameAlreadyExists, got $lastSaveResult"
        }
    }

    @Then("the message says a connection with that name already exists")
    fun theMessageSaysAConnectionWithThatNameAlreadyExists() {
        val result = lastSaveResult as? SaveConnectionResult.NameAlreadyExists
            ?: fail("Expected a NameAlreadyExists result, got $lastSaveResult")
        assertEquals(lastProfileName, result.name)
    }

    // --- Topology control level -------------------------------------------

    @When("I set the topology control level of {string} to {string}")
    fun iSetTheTopologyControlLevelOfTo(name: String, levelLabel: String) {
        lastProfileName = name
        lastSaveResult = viewModel.setTopologyControlLevel(name, topologyControlLevelFromLabel(levelLabel))
    }

    @Given("a saved connection {string} at topology control level {string}")
    fun aSavedConnectionAtTopologyControlLevel(name: String, levelLabel: String) {
        lastProfileName = name
        val result = viewModel.addConnection(
            ConnectionProfileInput(
                name = name,
                host = "rabbit-$name.example.com",
                virtualHost = "/",
                username = "svc-explorer",
                password = "s3cret",
                topologyControlLevel = topologyControlLevelFromLabel(levelLabel),
            ),
        )
        check(result is SaveConnectionResult.Saved) { "Test setup failed: could not save '$name': $result" }
    }

    @When("I change the host of {string} to {string} and save")
    fun iChangeTheHostOfToAndSave(name: String, newHost: String) {
        val saved = viewModel.state.value.profiles.first { it.name == name }
        lastProfileName = name
        // Like the edit panel for an untouched level and password: the level is not given (null = keep).
        lastSaveResult = viewModel.updateConnection(
            originalName = name,
            input = ConnectionProfileInput(
                name = saved.name,
                host = newHost,
                virtualHost = saved.virtualHost,
                username = saved.username,
                password = null,
                // The form sends the ports it shows; an edit of the host must not reset them.
                streamPort = saved.streamPort,
                managementPort = saved.managementPort,
            ),
        )
    }

    @Then("its topology control level is {string}")
    fun itsTopologyControlLevelIs(levelLabel: String) {
        assertEquals(topologyControlLevelFromLabel(levelLabel), currentProfile().topologyControlLevel)
    }

    private fun topologyControlLevelFromLabel(label: String): TopologyControlLevel = when (label) {
        "Read-only" -> TopologyControlLevel.READ_ONLY
        "Declare and edit" -> TopologyControlLevel.DECLARE_AND_EDIT
        "Full control" -> TopologyControlLevel.FULL_CONTROL
        else -> error("Unknown topology control level label in a scenario: '$label'")
    }

    // --- Reachability checks -----------------------------------------------

    @Given("the broker's stream endpoint and management API are both reachable over TLS")
    fun theBrokerSStreamEndpointAndManagementApiAreBothReachableOverTls() {
        checker.streamReachable = true
        checker.managementReachable = true
        checker.tlsOffered = true
        checker.managementPluginEnabled = true
    }

    @Given("the broker's stream endpoint and management API are both reachable over plain HTTP")
    fun theBrokerSEndpointsAreReachableOverPlain() {
        checker.streamReachable = true
        checker.managementReachable = true
        checker.plainOffered = true
        checker.managementPluginEnabled = true
    }

    @Given("the {endpoint} is not reachable")
    fun theEndpointIsNotReachable(endpoint: Endpoint) {
        when (endpoint) {
            Endpoint.STREAM -> checker.streamReachable = false
            Endpoint.MANAGEMENT -> checker.managementReachable = false
        }
    }

    @Given("the broker does not have the {string} plugin enabled")
    fun theBrokerDoesNotHaveThePluginEnabled(pluginName: String) {
        // The fake only models the one plugin this feature cares about; the name is still taken from
        // the Gherkin text (rather than hard-coded here) so the step definition does not silently pass
        // for a typo'd or different plugin name in the scenario.
        check(pluginName == "rabbitmq_stream_management") {
            "This fake only simulates 'rabbitmq_stream_management' missing, got '$pluginName'"
        }
        checker.managementPluginEnabled = false
    }

    @Given("the broker only offers plain connections")
    fun theBrokerOnlyOffersPlainConnections() {
        checker.tlsOffered = false
        checker.plainOffered = true
    }

    @Given("the broker presents a certificate signed by a private CA")
    fun theBrokerPresentsACertificateSignedByAPrivateCa() {
        checker.requiredCaCertificatePath = PRIVATE_CA_FIXTURE_PATH
    }

    @Given("the broker presents an untrusted certificate")
    fun theBrokerPresentsAnUntrustedCertificate() {
        checker.certificateTrusted = false
    }


    @When("I check the connection {string}")
    fun iCheckTheConnection(name: String) {
        lastProfileName = name
        lastCheckResult = runBlocking { viewModel.checkConnection(name) }
    }

    @Then("the check reports that both endpoints are reachable")
    fun theCheckReportsThatBothEndpointsAreReachable() {
        assertEquals(ConnectionCheckResult.BothReachable, lastCheckResult)
    }

    @Then("the check reports that the {endpoint} cannot be reached")
    fun theCheckReportsThatTheEndpointCannotBeReached(endpoint: Endpoint) {
        val result = lastCheckResult as? ConnectionCheckResult.EndpointUnreachable
            ?: fail("Expected EndpointUnreachable, got $lastCheckResult")
        assertEquals(endpoint, result.endpoint)
    }

    @Then("the other endpoint is still reported as reachable")
    fun theOtherEndpointIsStillReportedAsReachable() {
        // Proves the app actually probed BOTH endpoints rather than stopping at the first failure:
        // the failing one is named by the EndpointUnreachable result, and the checker's own call log
        // shows the other one was checked too (and, by construction of the fake, did not fail it -
        // otherwise a different result type would have been returned).
        assertEquals(setOf(Endpoint.STREAM, Endpoint.MANAGEMENT), checker.checkedEndpoints.toSet()) {
            "Expected both endpoints to have been probed, only probed ${checker.checkedEndpoints}"
        }
    }

    @Then("the check tells me to enable {string}")
    fun theCheckTellsMeToEnable(pluginName: String) {
        val result = lastCheckResult as? ConnectionCheckResult.ManagementPluginMissing
            ?: fail("Expected ManagementPluginMissing, got $lastCheckResult")
        assertEquals(pluginName, result.pluginName)
    }

    @Then("the connection is refused")
    fun theConnectionIsRefused() {
        assertEquals(ConnectionCheckResult.TlsRefused, lastCheckResult)
    }

    private fun checkMessage(): String {
        val result = checkNotNull(lastCheckResult) { "no check result" }
        return checkResultMessage(result, tls = currentProfile().tls)
    }

    @Then("the message says the broker may not use TLS")
    fun theMessageSaysTheBrokerMayNotUseTls() {
        assertTrue(checkMessage().contains("may not use TLS")) { "message: ${checkMessage()}" }
    }

    @Then("the message says the switch can be turned off in the connection settings")
    fun theMessageSaysTheSwitchCanBeTurnedOff() {
        assertTrue(checkMessage().contains("turn off 'Use TLS' in this connection's settings")) { "message: ${checkMessage()}" }
    }

    @Then("the check reports the certificate was not trusted")
    fun theCheckReportsTheCertificateWasNotTrusted() {
        assertEquals(ConnectionCheckResult.CertificateRejected, lastCheckResult)
        assertEquals(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED, checkMessage())
    }

    @Then("the check message does not mention turning TLS off")
    fun theCheckMessageDoesNotMentionTurningTlsOff() {
        assertFalse(checkMessage().contains("turn off 'Use TLS'")) { "message: ${checkMessage()}" }
        assertFalse(checkMessage().contains("may not use TLS")) { "message: ${checkMessage()}" }
    }


    @Then("no attempt without TLS was made")
    fun noAttemptWithoutTlsWasMade() {
        assertTrue(checker.attempts.isNotEmpty() && checker.attempts.all { it }) {
            "every attempt must be with TLS (no fallback), attempts (true = TLS): ${checker.attempts}"
        }
    }

    @Then("the message says the connection is not encrypted")
    fun theMessageSaysTheConnectionIsNotEncrypted() {
        assertTrue(checkMessage().contains("not encrypted")) { "message: ${checkMessage()}" }
    }

    @Then("the message does not mention TLS or a certificate")
    fun theMessageDoesNotMentionTlsOrACertificate() {
        val text = checkMessage()
        assertFalse(text.contains("TLS", ignoreCase = true) || text.contains("certificate", ignoreCase = true)) { "message: $text" }
    }

    // --- CA certificate -----------------------------------------------------

    @Given("I am editing a connection profile")
    fun iAmEditingAConnectionProfile() {
        // No fixture state needed: "An unreadable CA certificate file is rejected" exercises
        // viewModel.validateCaCertificateFile(...) standalone, before any profile is saved - exactly
        // like a user filling in the "New connection" panel. This step only documents that context.
    }

    @When("I set the CA certificate of {string} to the private CA file")
    fun iSetTheCaCertificateOfToThePrivateCaFile(name: String) {
        lastCaCertificateResult = viewModel.setCaCertificate(name, PRIVATE_CA_FIXTURE_PATH)
    }

    // "and leave the field": the screen validates on focus loss and on Save, not per keystroke (ConnectionsScreenUiTest pins when and on
    // which thread); the step calls the same view model method the screen calls then.
    @When("I add a connection {string} with the private CA file typed in the form")
    fun iAddAConnectionWithThePrivateCaFileTypedInTheForm(name: String) {
        val result = viewModel.addConnection(
            ConnectionProfileInput(
                name = name, host = "rabbit-$name.example.com", virtualHost = "/", username = "svc-explorer", password = "s3cret",
                caCertificatePath = PRIVATE_CA_FIXTURE_PATH,
            ),
        )
        check(result is SaveConnectionResult.Saved) { "Test setup failed: could not save '$name': $result" }
        lastProfileName = name
        lastPassword = "s3cret"
    }

    @Then("the saved CA certificate path of {string} is the private CA file")
    fun theSavedCaCertificatePathIsThePrivateCaFile(name: String) {
        assertEquals(
            PRIVATE_CA_FIXTURE_PATH, viewModel.state.value.profiles.first { it.name == name }.caCertificatePath,
            "the CA path typed in the form was not stored with the new connection",
        )
    }

    @When("I enter the path of a file that is not a valid certificate and leave the field")
    fun iEnterThePathOfAFileThatIsNotAValidCertificate() {
        lastCaCertificateResult = viewModel.validateCaCertificateFile(INVALID_CERTIFICATE_FIXTURE_PATH)
    }

    @Then("the file is rejected with a message saying it is not a valid certificate")
    fun theFileIsRejectedWithAMessageSayingItIsNotAValidCertificate() {
        val result = lastCaCertificateResult as? CaCertificateValidationResult.Invalid
            ?: fail("Expected the CA certificate file to be rejected, got $lastCaCertificateResult")
        assertTrue(result.reason.contains("valid certificate", ignoreCase = true)) {
            "Expected a message about an invalid certificate, got '${result.reason}'"
        }
    }

    @When("I type the CA certificate path {string} and leave the field")
    fun iTypeTheCaCertificatePath(path: String) {
        val tempDir = System.getProperty("java.io.tmpdir").trimEnd('/')
        val typed = when (path) {
            "the temp folder" -> tempDir
            "the temp folder/" -> "$tempDir/"
            "a file that is gone" -> "$tempDir/caerbannog-gone-${System.nanoTime()}.pem"
            else -> path
        }
        // A thrown exception here is the bug (it reaches the AWT thread while typing): let it fail the step.
        lastCaCertificateResult = viewModel.validateCaCertificateFile(typed)
    }

    @Then("the file is rejected with a message saying the file could not be read")
    fun theFileIsRejectedWithAMessageSayingTheFileCouldNotBeRead() {
        assertEquals(CaCertificateValidationResult.Invalid("the file could not be read"), lastCaCertificateResult)
    }

    // --- M2-14e: a late CA answer for a renamed or deleted profile ----------------------------------------------------------

    private var gatedValidator: GatedCaCertificateValidator? = null
    private var lateCheck: Deferred<CaCertificateValidationResult?>? = null
    private var checkPool: ExecutorService? = null

    @After
    fun stopLateCheckWorker() {
        gatedValidator?.release()
        checkPool?.shutdownNow()
    }

    @Given("the check of a valid CA certificate file for {string} is still running")
    fun theCheckOfAValidCaFileIsStillRunning(name: String) {
        val gate = GatedCaCertificateValidator()
        gatedValidator = gate
        // A view model of its own over the same repository and secret store: only this check goes through the held validator.
        viewModel = ConnectionsViewModel(repository, secretStore, checker, gate)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "late-ca-check") }.also { checkPool = it }
        viewModel.selectConnection(name) // the form for this profile is on screen
        // Like the form: the check runs on a worker, the answer comes back to the caller. Unconfined = started here, no extra thread.
        lateCheck = CoroutineScope(Dispatchers.Unconfined).async {
            viewModel.validateCaCertificateOffThread(name, PRIVATE_CA_FIXTURE_PATH, pool.asCoroutineDispatcher())
        }
        gate.awaitStarted()
    }

    @When("the connection {string} is renamed to {string} before the check finishes")
    fun theConnectionIsRenamedBeforeTheCheckFinishes(name: String, newName: String) {
        val saved = repository.list().first { it.name == name }
        val result = viewModel.updateConnection(
            originalName = name,
            input = ConnectionProfileInput(
                name = newName, host = saved.host, virtualHost = saved.virtualHost, username = saved.username, password = null,
                streamPort = saved.streamPort, managementPort = saved.managementPort, tls = saved.tls,
            ),
        )
        check(result is SaveConnectionResult.Saved) { "Test setup failed: could not rename '$name' to '$newName': $result" }
    }

    @When("the connection {string} is deleted before the check finishes")
    fun theConnectionIsDeletedBeforeTheCheckFinishes(name: String) {
        viewModel.requestDelete(name)
        viewModel.confirmDelete()
    }

    @When("the check finishes")
    fun theCheckFinishes() {
        requireNotNull(gatedValidator) { "No running check in this scenario" }.release()
    }

    @Then("the late answer is dropped without an error")
    fun theLateAnswerIsDroppedWithoutAnError() {
        val check = requireNotNull(lateCheck) { "No running check in this scenario" }
        // await() rethrows anything the check threw (today: IllegalStateException from profileNamed): that is the failure.
        val answer = runBlocking { withTimeout(10_000) { check.await() } }
        assertEquals(null, answer, "a late answer for a renamed or deleted profile must be dropped (null)")
    }

    @Then("no CA certificate message is shown")
    fun noCaCertificateMessageIsShown() {
        assertEquals(null, viewModel.state.value.lastCaCertificateValidationResult, "a CA validation result is on screen")
    }

    @Then("no saved connection has a CA certificate path")
    fun noSavedConnectionHasACaCertificatePath() {
        val withPath = repository.list().filter { it.caCertificatePath != null }.map { it.name to it.caCertificatePath }
        assertEquals(emptyList<Pair<String, String?>>(), withPath, "saved connections with a CA certificate path")
    }

    // --- Passwords and secrets -----------------------------------------------

    @Then("the profile file on disk does not contain {string}")
    fun theProfileFileOnDiskDoesNotContain(secret: String) {
        // ConnectionProfile structurally has no password field at all, so nothing that serializes a
        // ConnectionProfile (the real JSON file included - see data-rabbit's own
        // JsonConnectionProfileRepositoryTest, which asserts this against a real file on disk) can
        // ever contain the password. This checks that structural guarantee at the type level, plus
        // the concrete value for this scenario's saved profile, since this feature's own fakes do not
        // touch a real file (see Design notes: the fakes here are in-memory by design).
        assertTrue(ConnectionProfile::class.java.declaredFields.none { it.name.contains("password", ignoreCase = true) }) {
            "ConnectionProfile must not declare a password field at all"
        }
        assertFalse(currentProfile().toString().contains(secret)) {
            "ConnectionProfile's own string representation must never contain the password"
        }
    }

    @Then("the password is kept in the operating system's secret store")
    fun thePasswordIsKeptInTheOperatingSystemSSecretStore() {
        val name = requireNotNull(lastProfileName)
        assertTrue(secretStore.storeCalls.any { it.first == name }) {
            "Expected the app to store the password for '$name' via SecretStore, but it never called store()"
        }
        assertEquals(lastPassword, secretStore.retrieve(name))
    }

    // --- Delete -----------------------------------------------------------

    @When("I delete the connection {string} and confirm")
    fun iDeleteTheConnectionAndConfirm(name: String) {
        viewModel.requestDelete(name)
        viewModel.confirmDelete()
    }

    @When("I start deleting the connection {string} and then cancel")
    fun iStartDeletingTheConnectionAndThenCancel(name: String) {
        viewModel.requestDelete(name)
        viewModel.cancelDelete()
    }

    // --- Helpers -----------------------------------------------------------

    private fun currentProfile(): ConnectionProfile {
        val name = requireNotNull(lastProfileName) { "No connection name recorded yet in this scenario" }
        return viewModel.state.value.profiles.firstOrNull { it.name == name }
            ?: fail("Expected a saved connection named '$name', found ${viewModel.state.value.profiles.map { it.name }}")
    }

    companion object {
        // Fixture certificate files for "Use a custom CA certificate" and "An unreadable CA
        // certificate file is rejected". Resolved from the test classpath (src/test/resources) so
        // they work regardless of the working directory the test runner uses.
        val PRIVATE_CA_FIXTURE_PATH: String = requireFixture("fixtures/private-ca.pem")
        val INVALID_CERTIFICATE_FIXTURE_PATH: String = requireFixture("fixtures/not-a-certificate.txt")

        private fun requireFixture(resourcePath: String): String {
            val url = ConnectionsSteps::class.java.classLoader.getResource(resourcePath)
                ?: error("Missing test fixture on the classpath: $resourcePath")
            return java.io.File(url.toURI()).absolutePath
        }
    }
}
