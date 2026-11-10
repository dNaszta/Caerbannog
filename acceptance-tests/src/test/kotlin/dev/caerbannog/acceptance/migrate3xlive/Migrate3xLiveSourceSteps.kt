package dev.caerbannog.acceptance.migrate3xlive

import dev.caerbannog.acceptance.connections.FakeSecretStore
import dev.caerbannog.acceptance.permissions.FakePermissionChecker
import dev.caerbannog.core.connections.ActiveConnection
import dev.caerbannog.core.connections.BrokerPermissions
import dev.caerbannog.core.connections.ConnectionFeature
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionSession
import dev.caerbannog.core.connections.LinkState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.availableFeatures
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import dev.caerbannog.core.connections.permissionPill
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.FetchResult
import dev.caerbannog.core.migrate3x.Finding
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.STORAGE_VERSION_PAUSE_MILLIS
import dev.caerbannog.core.migrate3x.STORAGE_VERSION_READS
import dev.caerbannog.core.migrate3x.analyseLive
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.util.Collections

/**
 * M8-02a step definitions for the `@m8-02 @fake-broker` scenarios. One class, so the scenario state lives in its fields (Cucumber makes one
 * instance per scenario). The app is the real `ActiveConnection` over `FakeConnectionGateway`; the live read is the real
 * `LiveDefinitionsSource` over `FakeThreeXReads`, with a recording pause (no sleep). No decision logic in the steps.
 */
class Migrate3xLiveSourceSteps {

    private val password = "S3cret-PW-live-4711"
    private val pauses: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val gateway = FakeConnectionGateway()
    private val checker = FakePermissionChecker()
    private val secrets = FakeSecretStore()
    private val session = ConnectionSession(checker, secrets)
    private val active = ActiveConnection(session, gateway) { pauses += it }

    private val profiles = LinkedHashMap<String, ConnectionProfile>()
    private var outcome: LiveAnalysisOutcome? = null
    private var fetchSeen: FetchResult? = null
    private var export: List<Pair<String, String?>> = listOf("orders" to null)

    // ---- Given ---------------------------------------------------------------------------------------------------------------------

    private fun aConnection(name: String, version: String?) {
        profiles[name] = ConnectionProfile(name = name, host = "$name.example.com", virtualHost = "/", username = "svc")
        secrets.store(name, password)
        if (version == null) gateway.versionCannotBeRead(name) else gateway.brokerRuns(name, version)
        setExport(name)
    }

    private fun setExport(name: String) {
        gateway.threeXReadsOf(name).definitions = ReadOutcome.Ok(
            DefinitionsFiles.file(queues = export.map { (queue, type) -> DefinitionsFiles.queue(queue, type) }),
        )
    }

    private fun reads(name: String): FakeThreeXReads = gateway.threeXReadsOf(name)

    @Given("a connection {string} to a broker running RabbitMQ 3.13")
    fun a313Connection(name: String) {
        aConnection(name, "3.13.7")
        reads(name).storageVersionAppearsAtRead("/", "orders", 1, version = 2)
    }

    @Given("a connection {string} to a broker running RabbitMQ 3.13 whose user has configure, write and read permission")
    fun a313ConnectionWithEveryPermission(name: String) {
        a313Connection(name)
        checker.permissionsByName[name] = BrokerPermissions(configure = ".*", write = ".*", read = ".*")
    }

    @Given("a connection {string} to a broker running RabbitMQ 4")
    fun a4Connection(name: String) = aConnection(name, "4.1.0")

    @Given("a connection {string} to a broker whose version cannot be read")
    fun anUnreadableConnection(name: String) = aConnection(name, null)

    @Given("the connection {string} is set to the level {string}")
    fun theLevel(name: String, level: String) {
        val control = when (level) {
            "Full control" -> TopologyControlLevel.FULL_CONTROL
            "Read-only" -> TopologyControlLevel.READ_ONLY
            else -> fail("unknown level $level")
        }
        profiles[name] = profile(name).copy(topologyControlLevel = control)
    }

    @Given("the user may export definitions")
    fun theUserMayExport() {
        // The default of the fake: the export is answered. Said in the scenario for the reader; it changes nothing here.
        profiles.keys.forEach { assertTrue(reads(it).definitions is ReadOutcome.Ok) }
    }

    @Given("the broker refuses the definitions export for this user because it is not an administrator")
    fun theExportIsRefused() = reads(only()).let { it.definitions = ReadOutcome.NotPermitted }

    @Given("the broker stops answering during the export")
    fun theBrokerStopsAnswering() = reads(only()).let { it.definitions = ReadOutcome.Unreachable }

    @Given("the broker reports no storage version for {string} until the second read")
    fun noVersionUntilSecondRead(queue: String) = reads(only()).storageVersionAppearsAtRead("/", queue, 2, version = 2)

    @Given("the broker never reports a storage version for {string}")
    fun neverReportsAVersion(queue: String) = reads(only()).storageVersionNeverAppears("/", queue)

    @Given("the export holds the classic queue {string} and the quorum queue {string}")
    fun theExportHolds(classic: String, quorum: String) {
        export = listOf(classic to null, quorum to "quorum")
        val name = only()
        setExport(name)
        reads(name).storageVersionAppearsAtRead("/", classic, 1, version = 2)
        reads(name).trapQueue("/", quorum)
    }

    @Given("the broker refuses the deprecated feature list")
    fun theDeprecatedListIsRefused() = reads(only()).let { it.deprecatedFeatures = ReadOutcome.NotPermitted }

    // ---- When ----------------------------------------------------------------------------------------------------------------------

    @When("I connect to {string}")
    fun iConnect(name: String) = runBlocking { active.connect(profile(name)) }

    @When("I analyse {string}")
    fun iAnalyse(name: String) = runBlocking {
        if (active.stateOf(name) !is LinkState.Connected) active.connect(profile(name))
        analyse(name)
    }

    @When("I connect to {string} and analyse {string}")
    fun iConnectAndAnalyse(connect: String, analyse: String) = runBlocking {
        active.connect(profile(connect))
        analyse(analyse)
    }

    /** What the Migrate from 3.x screen will do (M8-06c): analyse the definitions source of the held connection; every connection has one. */
    private suspend fun analyse(name: String) {
        val source = active.source ?: fail("no connection is held for $name")
        outcome = analyseLive(Recording(source.definitionsSource))
    }

    /** Keeps the facts the source fetched, so a step can see which queues the scan answered for. Changes nothing. */
    private inner class Recording(private val inner: DefinitionsSource) : DefinitionsSource {
        override val connectionName: String get() = inner.connectionName
        override suspend fun fetch(): FetchResult = inner.fetch().also { fetchSeen = it }
    }

    // ---- Then: the analysis --------------------------------------------------------------------------------------------------------

    private fun analysed(): LiveAnalysisOutcome.Analysed =
        outcome as? LiveAnalysisOutcome.Analysed ?: fail("expected a report but the outcome was: $outcome")

    private fun facts() = (fetchSeen as? FetchResult.Fetched ?: fail("nothing was fetched: $fetchSeen")).facts

    private fun findings(queue: String): List<Finding> = analysed().held.report.findings.filter { it.name == queue }

    @Then("the report is built from the definitions the broker returns")
    fun reportFromBrokerDefinitions() {
        val held = analysed().held
        assertEquals(export.map { it.first }, held.document.queues.map { it.name }, "the report must be about the queues of the export the broker returned")
        assertTrue(held.report.source.live, "a live report says it is live")
    }

    @Then("the report names the source as {string} with version 3.13")
    fun reportNamesTheSource(name: String) {
        val held = analysed().held
        assertEquals(name, held.fileLabel)
        assertEquals(name, held.report.source.name)
        assertTrue(held.report.source.sourceVersion?.startsWith("3.13") == true, "source version was ${held.report.source.sourceVersion}")
    }

    @Then("I am told that this user cannot export definitions")
    fun toldCannotExport() {
        val offer = outcome as? LiveAnalysisOutcome.OfferFileImport ?: fail("expected the file import offer but got: $outcome")
        assertEquals(LiveSourceTexts.CANNOT_EXPORT, offer.text)
    }

    @Then("I am offered to import a definitions file instead")
    fun offeredFileImport() = assertTrue(outcome is LiveAnalysisOutcome.OfferFileImport, "outcome: $outcome")

    @Then("I am told the broker version could not be read")
    fun toldVersionUnreadable() = assertEquals(LiveAnalysisOutcome.Failed(LiveSourceTexts.VERSION_UNREADABLE), outcome)

    @Then("I am told that only a RabbitMQ 3.x connection can be analysed live")
    fun toldOnlyThreeX() = assertEquals(LiveAnalysisOutcome.Failed(LiveSourceTexts.NOT_THREE_X), outcome)

    @Then("I am told that {string} could not be reached")
    fun toldNotReached(name: String) = assertEquals(LiveAnalysisOutcome.Unreachable(name), outcome)

    @Then("no report is shown")
    fun noReport() = assertFalse(outcome is LiveAnalysisOutcome.Analysed, "a report was built: $outcome")

    @Then("the report is still built")
    fun reportStillBuilt() {
        analysed()
    }

    @Then("the report does not claim that mirroring is in use")
    fun noMirroringClaim() = assertTrue(analysed().held.report.findings.none { it.rule == "mirroring-in-use" }, "a mirroring finding appeared although the list could not be read")

    // ---- Then: the connection ------------------------------------------------------------------------------------------------------

    private fun result(): PermissionCheckResult = session.state.value.current?.status.let { (it as? PermissionCheckStatus.Done)?.result }
        ?: fail("the current connection has no finished permission result: ${session.state.value.current}")

    private fun pillText(): String = permissionPill(session.state.value.current?.status).text

    @Then("the connection says {string}")
    fun theConnectionSays(text: String) = assertEquals(text, pillText())

    @Then("the connection does not say {string}")
    fun theConnectionDoesNotSay(text: String) = assertTrue(pillText() != text, "the pill says '$text' but must not")

    @Then("topology changes are locked whatever the user's level and permissions are")
    fun topologyLocked() {
        assertFalse(result().topologyUnlocked, "topology must be locked for a migration source: ${result()}")
        val offered = availableFeatures(result())
        assertFalse(ConnectionFeature.Topology in offered || ConnectionFeature.TopologyEdits in offered, "offered: $offered")
        assertEquals(null, active.source?.migrationExecutor, "a source has no migration executor")
    }

    @Then("test publishing is disabled")
    fun publishingDisabled() {
        assertFalse(result().publishingEnabled, "publishing must be off for a migration source: ${result()}")
        assertFalse(ConnectionFeature.Publish in availableFeatures(result()))
    }

    @Then("the only offered action is to migrate from 3.x")
    fun onlyMigrate() = assertEquals(setOf(ConnectionFeature.MigrateFromThreeX), availableFeatures(result()))

    @Then("the permission pill of {string} is the one the permission check gave")
    fun pillIsTheChecksOwn(name: String) {
        assertTrue(checker.calls.any { it.first.name == name }, "the permission check never ran for $name")
        val given = permissionCheckAnswer(name)
        assertEquals(permissionPill(PermissionCheckStatus.Done(given)), permissionPill(session.state.value.current?.status))
    }

    private fun permissionCheckAnswer(name: String): PermissionCheckResult =
        dev.caerbannog.core.connections.decidePermissions(checker.permissionsByName[name] ?: checker.permissions ?: fail("no permissions scripted"))

    @Then("the connection {string} is not called a RabbitMQ 3.x migration source")
    fun notCalledASource(name: String) {
        assertFalse(session.permissions(name) is PermissionCheckResult.MigrationSource, "marked: ${session.permissions(name)}")
        assertTrue(pillText() != "RabbitMQ 3.x: migration source only")
    }

    @Then("the broker was asked for its overview once")
    fun overviewOnce() = assertEquals(1, gateway.overviewReads, "GET /api/overview reads for one connect and one analysis")

    // ---- Then: safety and the scan --------------------------------------------------------------------------------------------------

    @Then("every request sent to {string} is a read request")
    fun everyRequestIsARead(name: String) {
        val calls = reads(name).calls
        assertTrue(calls.isNotEmpty(), "the analysis made no read at all")
        assertTrue(calls.all { it == "definitions" || it == "deprecated-features" || it.startsWith("storage-versions:") }, "calls: $calls")
    }

    @Then("nothing on {string} has changed")
    fun nothingChanged(name: String) {
        assertEquals(emptyList<Any>(), gateway.managerOf(name).createdStreams + gateway.managerOf(name).deletedStreams, "streams were created or deleted")
        assertEquals(emptyList<Any>(), gateway.publisherOf(name).publishedMessages, "a message was published")
        assertEquals(emptyList<Any>(), gateway.mqttPublisherOf(name).publishedMessages, "an MQTT message was published")
        assertEquals(emptyList<Any>(), gateway.queueInspectorOf(name).requests, "queue messages were peeked at")
        assertEquals(emptyList<String>(), gateway.topologyOf(name).calls.filter { it.startsWith("create") || it.startsWith("delete") || it.startsWith("purge") }, "topology calls: ${gateway.topologyOf(name).calls}")
    }

    @Then("the storage version of {string} is read after a retry")
    fun readAfterRetry(queue: String) {
        assertEquals(2, reads(only()).storageReads("/"), "read at once and once more")
        assertEquals(listOf(STORAGE_VERSION_PAUSE_MILLIS), pauses.toList(), "one pause between the two reads")
        assertEquals(ClassicIndexVersion.V2, facts().classicIndexVersions[QueueRef("/", queue)])
        assertTrue(findings(queue).none { it.severity == Severity.Blocker }, "a queue with index version 2 has no message-loss blocker")
    }

    @Then("a queue whose version never appears is treated as version 1 and reported as unknown")
    fun neverAppearsIsUnknown() {
        // A second analysis of the same connection with a queue that never gets a version (the scenario's Given has it appear).
        val name = only()
        reads(name).storageVersionNeverAppears("/", "orders")
        runBlocking { analyse(name) }
        assertEquals(ClassicIndexVersion.Unknown, facts().classicIndexVersions[QueueRef("/", "orders")])
        val blocker = findings("orders").firstOrNull { it.severity == Severity.Blocker } ?: fail("no blocker for a queue whose version never appears")
        assertTrue(blocker.message.contains("treated as version 1"), blocker.message)
    }

    @Then("the storage version of {string} was read at most {int} times")
    fun readAtMost(queue: String, max: Int) {
        assertEquals(STORAGE_VERSION_READS, max, "the scenario's number is the constant")
        val reads = reads(only()).storageReads("/")
        assertTrue(reads in 1..max, "storage version of '$queue' read $reads times, at most $max allowed")
        assertTrue(pauses.size <= max - 1 && pauses.all { it == STORAGE_VERSION_PAUSE_MILLIS }, "pauses: $pauses")
    }

    @Then("the storage version of {string} is reported as unknown")
    fun reportedUnknown(queue: String) {
        assertEquals(ClassicIndexVersion.Unknown, facts().classicIndexVersions[QueueRef("/", queue)])
        val blocker = findings(queue).firstOrNull { it.severity == Severity.Blocker } ?: fail("no message-loss blocker for '$queue'")
        assertTrue(blocker.message.contains("could not be read"), blocker.message)
    }

    @Then("the storage version of {string} was asked for")
    fun askedFor(queue: String) {
        assertTrue(reads(only()).storageReads("/") >= 1, "no storage version read happened")
        assertTrue(QueueRef("/", queue) in facts().classicIndexVersions, "the scan has no answer for '$queue': ${facts().classicIndexVersions}")
    }

    @Then("the storage version of {string} was not asked for")
    fun notAskedFor(queue: String) {
        assertFalse(QueueRef("/", queue) in facts().classicIndexVersions, "the scan answered for '$queue', which is not a classic queue: ${facts().classicIndexVersions}")
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------------

    private fun profile(name: String): ConnectionProfile = profiles[name] ?: fail("no connection named $name was set up")

    private fun only(): String = profiles.keys.singleOrNull() ?: fail("this step needs exactly one connection, found ${profiles.keys}")
}
