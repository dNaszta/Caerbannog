package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.EvidenceSource
import dev.caerbannog.core.migrate3x.FetchResult
import dev.caerbannog.core.migrate3x.FindingClass
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.Proposal
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.STORAGE_VERSION_READS
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.analyseLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M8-02b, the three `@broker-3x @m8-02` scenarios against a REAL RabbitMQ 3.13 broker (`BrokerImages.LEGACY_3X`, one container for the
 * class, no 4.x container). The app's own gateway opens the connection, `ManagementThreeXReads` reads over the management API, and `analyseLive`
 * analyses what came back. The production retry of the storage version scan is the polling: the test never sleeps, it only waits inside
 * `LiveDefinitionsSource` with the production pause (`delay`), and it asserts that the bound (`STORAGE_VERSION_READS` reads one second apart) is enough
 * for a queue declared a moment ago, the worst case.
 *
 * Open question of the brief, "may `columns=` include `storage_version` on the queue list?": see `the columns filter` below; the default of the
 * production call is NO `columns` filter, and this test pins what the broker does so a later optimisation is not guessed.
 *
 * Every queue, exchange, policy and user the tests create has a unique name and is deleted in `cleanUp`, so the container is left clean.
 * Run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8LiveSource*'` (Docker or rootless Podman).
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8LiveSourceTest {

    companion object {
        private val old: RabbitMQContainer = broker(BrokerImages.LEGACY_3X)
        private lateinit var api: MgmtApi

        @JvmStatic @BeforeAll fun start() { old.start(); api = MgmtApi(old) }

        @JvmStatic @AfterAll fun stop() = old.stop()
    }

    private class Passwords(private val passwords: Map<String, String>) : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String? = passwords[profileName]
        override fun delete(profileName: String) = Unit
    }

    private val suffix = UUID.randomUUID().toString().take(8)
    private val queues = mutableListOf<String>()
    private val exchanges = mutableListOf<String>()
    private val policies = mutableListOf<String>()
    private val users = mutableListOf<String>()
    private val held = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanUp() {
        held.forEach { runCatching { it.close() } }
        policies.forEach { api.delete("/api/policies/%2F/$it") }
        exchanges.forEach { api.delete("/api/exchanges/%2F/$it") }
        queues.forEach { api.delete("/api/queues/%2F/$it") }
        users.forEach { api.delete("/api/users/$it") }
    }

    private fun queue(name: String, arguments: String = "{}"): String {
        val unique = "$name-$suffix"
        val reply = api.declareQueue(unique, arguments)
        assertTrue(reply.ok, "declaring $unique: $reply")
        queues += unique
        return unique
    }

    /** The app's own gateway, as a connection profile of the container's management port; the stream port is closed (not needed). */
    private fun connect(user: String, password: String): HeldConnection {
        val profile = ConnectionProfile(
            name = "legacy-eu", host = old.host, virtualHost = "/", username = user,
            managementPort = old.getMappedPort(15672), streamPort = closedLocalPort(), tls = false,
        )
        val result = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Passwords(mapOf(profile.name to password))).open(profile) } }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, result, "opening the 3.13 connection: $result").connection
        held += connection
        return connection
    }

    private val pauses = CopyOnWriteArrayList<Long>()

    /** The production wiring of `ActiveConnection.guardedSource`: version and reads of the held connection, the real `delay` as the pause. */
    private fun liveSource(connection: HeldConnection): LiveDefinitionsSource {
        val reads = assertInstanceOf(ManagementThreeXReads::class.java, connection.threeXReads, "a 3.13 connection must hand over its reads")
        return LiveDefinitionsSource("legacy-eu", connection.brokerVersion, reads) { millis -> pauses += millis; delay(millis) }
    }

    private fun analyse(source: DefinitionsSource): LiveAnalysisOutcome = runBlocking { withTimeout(120_000) { analyseLive(source) } }

    private fun adminConnection() = connect(old.adminUsername, old.adminPassword)

    // ---- @broker-3x @m8-02: A real RabbitMQ 3.13 export is read and analysed ------------------------------------------------------

    @Test
    fun `a real 3_13 export with a mirrored queue, a priority queue and a binding is read and analysed`() {
        val mirrored = queue("mirrored")
        val priority = queue("priority", """{"x-max-priority":5}""")
        val exchange = "ex-$suffix".also { exchanges += it }
        assertTrue(api.put("/api/exchanges/%2F/$exchange", """{"type":"direct","durable":true,"arguments":{}}""").ok)
        assertTrue(api.post("/api/bindings/%2F/e/$exchange/q/$priority", """{"routing_key":"k"}""").ok)
        val policy = "ha-$suffix".also { policies += it }
        assertTrue(api.put("/api/policies/%2F/$policy", """{"pattern":"^$mirrored$","apply-to":"queues","priority":1,"definition":{"ha-mode":"all"}}""").ok)

        val connection = adminConnection()
        val version = connection.brokerVersion
        assertTrue(version != null && version.startsWith("3.13"), "the connection reads the version of the real broker: $version")
        val outcome = assertInstanceOf(LiveAnalysisOutcome.Analysed::class.java, analyse(liveSource(connection)), "analysis")
        val report = outcome.held.report

        assertEquals("legacy-eu", outcome.held.fileLabel)
        assertTrue(report.source.live, "the report is marked as a live one")
        assertEquals("legacy-eu", report.source.name)
        // R4: the report shows the export's rabbit_version (like a file import); brokerVersion (overview) feeds only the pill and the facts.
        assertTrue(report.source.sourceVersion?.startsWith("3.13") == true, "the report names the 3.13 version of the export: ${report.source.sourceVersion}")

        // both queues with their proposals: every durable classic queue gets "not replicated on 4.x" with something to do about it
        for (name in listOf(mirrored, priority)) {
            val finding = report.findings.singleOrNull { it.kind == ItemKind.Queue && it.name == name && it.rule == "classic-not-replicated" }
            assertTrue(finding != null, "no classic-not-replicated finding for queue $name; findings: ${report.findings.map { it.id }}")
            assertTrue(finding!!.proposal != Proposal.None && finding.alternatives.isNotEmpty(), "queue $name needs a proposal and an alternative: $finding")
        }
        assertEquals(Proposal.KeepClassic, report.findings.single { it.name == priority && it.rule == "classic-not-replicated" }.proposal, "a priority queue stays classic by default")
        assertTrue(
            report.findings.any { it.name == mirrored && it.rule == "classic-not-replicated" && policy in it.message },
            "the finding of the mirrored queue names the mirroring policy",
        )
        // the mirroring policy
        assertTrue(
            report.findings.any { it.kind == ItemKind.Policy && it.name == policy },
            "the mirroring policy is listed; policy findings: ${report.findings.filter { it.kind == ItemKind.Policy }.map { it.id }}",
        )
        // mirroring flagged from the broker's own deprecated feature list
        assertTrue(
            report.findings.any { it.rule == "mirroring-in-use" && it.evidence == EvidenceSource.LiveDeprecatedFeatures },
            "mirroring flagged from deprecated-features/used; rules seen: ${report.findings.map { it.rule }.toSet()}",
        )
    }

    // ---- @broker-3x @m8-02: storage version of classic queues ----------------------------------------------------------------------

    /** Bounded poll (30 s, every 500 ms) until the broker shows `storage_version` for [name]; production code is not involved. */
    private fun awaitStorageVersion(name: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        while (api.get("/api/queues/%2F/$name").json?.let { (it as? kotlinx.serialization.json.JsonObject)?.containsKey("storage_version") } != true) {
            check(System.nanoTime() < deadline) { "queue $name never showed storage_version within 30 s" }
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500))
        }
    }

    @Test
    fun `a real 3_13 broker reports the storage version of its classic queues`() {
        val v1 = queue("v1") // 3.13 default: index version 1
        val v2 = queue("v2", """{"x-queue-version":2}""")
        // R3: a real source broker has run for days. The statistics show up with the first collection (5 s by default), so wait for them here,
        // then scan: nothing may need a retry.
        awaitStorageVersion(v1)
        awaitStorageVersion(v2)
        val connection = adminConnection()
        val fetched = assertInstanceOf(FetchResult.Fetched::class.java, runBlocking { withTimeout(120_000) { liveSource(connection).fetch() } }, "fetch")
        assertEquals(ClassicIndexVersion.V1, fetched.facts.classicIndexVersions[QueueRef("/", v1)], "default 3.13 queue is on index version 1")
        assertEquals(ClassicIndexVersion.V2, fetched.facts.classicIndexVersions[QueueRef("/", v2)], "a queue declared with x-queue-version 2")
        assertTrue(pauses.isEmpty(), "the values were already there, so no retry pause is allowed: $pauses")

        val report = assertInstanceOf(LiveAnalysisOutcome.Analysed::class.java, analyse(liveSource(connection)), "analysis").held.report
        val loss = report.findings.filter { it.rule == "classic-index-v1" && it.name == v1 }
        assertEquals(1, loss.size, "one message-loss finding for the version 1 queue; findings: ${report.findings.filter { it.name == v1 }.map { it.id }}")
        assertEquals(Severity.Blocker, loss.single().severity)
        assertEquals(FindingClass.MessageLoss, loss.single().findingClass)
        assertEquals(EvidenceSource.LiveQueueStats, loss.single().evidence, "the finding comes from the live storage version, not from the file shape")
        assertTrue(report.findings.none { it.rule == "classic-index-v1" && it.name == v2 }, "a version 2 queue is not a message-loss blocker")
    }

    /**
     * The honest worst case: scan straight after declaring. A value is never Unknown while retries remain: either it is V1/V2, or it is Unknown
     * after the whole bound (all STORAGE_VERSION_READS - 1 pauses were used). The pauses used are printed (R3: the number is reported in step 4).
     */
    @Test
    fun `a scan straight after declaring never gives up before the bound`() {
        val v1 = queue("fresh1")
        val v2 = queue("fresh2", """{"x-queue-version":2}""")
        val connection = adminConnection()
        val fetched = assertInstanceOf(FetchResult.Fetched::class.java, runBlocking { withTimeout(120_000) { liveSource(connection).fetch() } }, "fetch")
        println("M8-02b live: scan straight after declaring used ${pauses.size} pause(s) of ${STORAGE_VERSION_READS - 1} allowed")
        for ((name, expected) in listOf(v1 to ClassicIndexVersion.V1, v2 to ClassicIndexVersion.V2)) {
            val value = fetched.facts.classicIndexVersions[QueueRef("/", name)]
            assertTrue(
                value == expected || (value == ClassicIndexVersion.Unknown && pauses.size == STORAGE_VERSION_READS - 1),
                "queue $name: $value after ${pauses.size} pause(s); Unknown is only allowed after all ${STORAGE_VERSION_READS - 1} pauses",
            )
        }
    }

    /**
     * The `columns=` question of the brief, answered by the broker here. The production call sends no `columns` filter (the safe default). This pins
     * what a 3.13 broker does with `columns=name,storage_version`, so nobody has to guess when optimising the call.
     */
    @Test
    fun `the columns filter of the queue list and storage_version`() {
        val name = queue("columns")
        awaitUntil("storage_version of $name on the unfiltered list") {
            api.get("/api/queues/%2F?page=1&page_size=500").json?.let { it.toString().contains("\"storage_version\"") && it.toString().contains(name) } == true
        }
        val filtered = api.get("/api/queues/%2F?page=1&page_size=500&columns=name,storage_version")
        assertTrue(filtered.ok, "columns= must be accepted: $filtered")
        val carries = Regex("\"name\":\"$name\"[^}]*\"storage_version\"|\"storage_version\":\\d[^}]*\"name\":\"$name\"").containsMatchIn(filtered.body)
        // ANSWER (measured on the 3.13 image (`BrokerImages.LEGACY_3X`), 2026-11-10): YES, `columns=name,storage_version` returns `storage_version` for a classic queue
        // that has statistics, so a later optimisation could use it. The production call still sends NO columns filter (less to go wrong); the
        // value is absent until the queue's first statistics, with or without the filter. If a newer 3.13 changes this, this fails on purpose.
        assertTrue(carries, "columns=name,storage_version should return storage_version; body: ${filtered.body.take(400)}")
    }

    // ---- @broker-3x @negative @m8-02: a user without the administrator tag --------------------------------------------------------------

    @Test
    fun `a real 3_13 user with the monitoring tag is told it cannot export and is offered the file import`() {
        val user = "mon-$suffix".also { users += it }
        val throwaway = UUID.randomUUID().toString() // created through the management API for this test only, deleted in cleanUp
        assertTrue(api.put("/api/users/$user", """{"password":"$throwaway","tags":"monitoring"}""").ok)
        assertTrue(api.put("/api/permissions/%2F/$user", """{"configure":"","write":"","read":".*"}""").ok)

        val connection = connect(user, throwaway)
        val reads = assertInstanceOf(ManagementThreeXReads::class.java, connection.threeXReads)
        assertEquals(ReadOutcome.NotPermitted, runBlocking { withTimeout(30_000) { reads.definitions() } }, "measured S-A: 401 for the monitoring tag")

        val outcome = analyse(liveSource(connection))
        assertEquals(LiveAnalysisOutcome.OfferFileImport(LiveSourceTexts.CANNOT_EXPORT), outcome)
        assertTrue(pauses.isEmpty(), "nothing else is read once the export is refused: $pauses")
    }
}
