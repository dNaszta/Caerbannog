package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamMessageReader
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.migrations.MigrationFolderChooser
import dev.caerbannog.core.planner.OnlyThePlanCause
import dev.caerbannog.core.planner.PlanGraphNotice
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-04 step 2: the adapter of `KnownTopologyReader` over the existing catalogs, and what `createPlannerViewModel` passes. Safety (spec
 * "Guardrails that stay as they are"): no connection and a 3.x connection (no executor) get NO reader, so no request of any kind is sent;
 * on a 4.x connection the reader makes only list calls: `TopologyCatalog.exchanges` and `queues`, and `StreamCatalog.listStreams`, never a call
 * under one queue's name (`queueDetail`, `streamDetail`, publishers, consumers) and never `bindings` (the broker's bindings are not drawn). The
 * catalogs the app holds are wrapped in proxies that record every method asked, so "only these three" is a fact, not a reading of the code.
 * (The catalogs have no write method and the management client sends GET for every one of them; its own request recorder is in `data-rabbit`.)
 *
 * Contract (test developer's proposal, to be confirmed by the architect), all in `desktopApp`:
 *  - `MigrationsInputs.streams: StreamCatalog? = null`, filled from the same source as `topology` (`source?.catalog`);
 *  - `knownTopologyReaderOf(inputs): KnownTopologyReader?`: null for no connection, for any `unavailableReason`, and when a catalog is missing;
 *  - `CatalogKnownTopologyReader(topology, streams)`: the adapter; every page of the queues, every page of the streams, the exchanges; the
 *    default exchange `""` is not a name a plan can bind to and is left out; any failure of any call (also of a later page) answers null,
 *    because half a list would call things "not in this plan" that exist;
 *  - `createPlannerViewModel(inputs, scope)` passes `knownTopologyReaderOf(inputs)`.
 */
class CompositionRootPlannerReaderTest {
    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private object Permissive : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String) = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
    }

    private class Cancelling : MigrationFolderChooser {
        override suspend fun choose(startIn: String?): String? = null
    }

    /** Wraps [target] so that every method called on it is recorded by name in [calls], then runs unchanged. */
    private inline fun <reified T : Any> recording(target: T, calls: MutableList<String>, label: String): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            calls += "$label.${method.name}"
            try { method.invoke(target, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        } as T

    private val everything: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val topologyFake = FakeTopologyCatalog()
    private val streamFake = FakeStreamCatalog()
    private val threeX = "This broker runs RabbitMQ 3.x: it is a migration source only"

    private inner class Gateway : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog: StreamCatalog = recording<StreamCatalog>(streamFake, everything, "streams")
            override val offsets = FakeStreamOffsets()
            override val topology: TopologyCatalog = recording<TopologyCatalog>(topologyFake, everything, "topology")
            override val messages = FakeStreamMessageReader()
            override val brokerVersion: String? = if (profile.name == "legacy-eu") "3.13.7" else "4.1.0"
            override val migrationExecutor: TopologyMigrationExecutor? = if (profile.name == "legacy-eu") null else dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor()
            override val migrationUnavailableReason: String? = if (profile.name == "legacy-eu") threeX else null
            override fun close() = Unit
        })
    }

    private val compositions = mutableListOf<AppComposition>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun input(name: String) = ConnectionProfileInput(name = name, host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", topologyControlLevel = TopologyControlLevel.FULL_CONTROL)

    private fun compose(): AppComposition =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = Gateway(),
            appSettings = JsonFileAppSettings(File(dir, "settings.json")), uiDispatcher = Dispatchers.Unconfined,
            migrationHistoryFile = File(dir, "history.json").toPath(), migrationFolderChooser = Cancelling(), planDraftFile = File(dir, "data/planner-draft.json").toPath(),
        ).also { c ->
            compositions += c
            listOf("uat-eu", "legacy-eu").forEach { c.viewModel.addConnection(input(it)) }
        }

    private fun AppComposition.connect(name: String) { runBlocking { viewModel.connect(name) } }

    private fun inputsOf(c: AppComposition) = c.migrationsInputs(c.viewModel.state.value)

    private fun plannerOf(c: AppComposition): PlannerViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return c.createPlannerViewModel(inputsOf(c), scope)
    }

    @AfterEach
    fun cleanUp() { scopes.forEach { it.cancel() }; compositions.forEach { it.close() } }

    private fun seedBroker() {
        topologyFake.addExchange(FakeTopologyCatalog.exchange("events", "topic")).addExchange(FakeTopologyCatalog.exchange("amq.topic", "topic"))
        (1..600).forEach { topologyFake.addQueue(FakeTopologyCatalog.quorum("queue-%03d".format(it))) } // two pages of 500
        (1..130).forEach { streamFake.addStream(FakeStreamCatalog.stream("stream-%03d".format(it))) }
    }

    // ---- no reader, no request ----

    @Test
    fun `not connected gives no reader, no request, and a planner that says only the plan is drawn`() {
        val c = compose()
        assertNull(knownTopologyReaderOf(inputsOf(c)))
        val planner = plannerOf(c)
        assertNull(planner.state.value.known)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.NotConnected), planner.state.value.graph.notice)
        assertEquals(emptyList(), everything.toList(), "nothing may be asked of a broker without a connection")
    }

    @Test
    fun `a 3x connection gives no reader and not one request of any kind`() {
        seedBroker()
        val c = compose().also { it.connect("legacy-eu") }
        everything.clear() // what the connection itself asked is not the planner's
        val inputs = inputsOf(c)
        assertNotNull(inputs.unavailableReason, "the 3.x connection must be the one without an executor")
        assertNull(knownTopologyReaderOf(inputs))
        val planner = plannerOf(c)
        planner.setVirtualHost("billing")
        assertNull(planner.state.value.known)
        assertEquals(emptyList(), everything.toList(), "RabbitMQ 3.x is a read-only migration source: the planner sends it nothing")
    }

    // ---- the adapter on a 4.x connection ----

    @Test
    fun `on a 4x connection the planner reads exchanges, every page of the queues and the streams, with list calls only`() {
        seedBroker()
        val c = compose().also { it.connect("uat-eu") }
        everything.clear()
        val reader = assertNotNull(knownTopologyReaderOf(inputsOf(c)))
        val known = assertNotNull(runBlocking { reader.read("/") })
        assertEquals("topic", known.exchanges["events"])
        assertTrue("amq.topic" in known.exchanges, "the broker's own exchanges are names a plan can bind to")
        assertTrue("" !in known.exchanges, "the default exchange is not a name to bind to")
        assertEquals(600, known.queues.size, "every page of the queues")
        assertTrue("queue-600" in known.queues && "queue-001" in known.queues)
        assertEquals(130, known.streams.size, "every page of the streams")
        // M9-09: one more GET, the queue declarations (the queue's own x-dead-letter-exchange); still no call under one queue's name and no bindings
        val allowed = setOf("topology.exchanges", "topology.queues", "topology.queueDeclarations", "streams.listStreams")
        assertEquals(emptySet(), everything.toSet() - allowed, "the planner asked for more than the four list calls")
        assertEquals(allowed, everything.toSet(), "the planner must read exchanges, queues, their declarations and streams")
    }

    @Test
    fun `the planner view model of a 4x connection starts with the broker's names and the graph draws the existing end`() {
        seedBroker()
        val c = compose().also { it.connect("uat-eu") }
        val planner = plannerOf(c)
        assertEquals("topic", assertNotNull(planner.state.value.known).exchanges["events"])
        planner.openWizard(); planner.chooseKind("Queue"); planner.updateForm(dev.caerbannog.core.planner.QueueForm("orders")); planner.next(); planner.addToPlan()
        planner.openWizard(); planner.chooseKind("Binding")
        planner.updateForm(dev.caerbannog.core.planner.BindingForm(exchange = "events", destination = "orders", routingKey = "k")); planner.next(); planner.addToPlan()
        val graph = planner.state.value.graph
        assertEquals(setOf("q:orders"), graph.newNodeIds)
        assertEquals(emptySet(), graph.notInPlanNodeIds, "events exists on the broker")
        assertNull(graph.notice)
    }

    @Test
    fun `a virtual host that the user cannot see, or no management tag, or a refused call gives null and the broker not read notice`() {
        seedBroker()
        val c = compose().also { it.connect("uat-eu") }
        val reader = assertNotNull(knownTopologyReaderOf(inputsOf(c)))
        assertNull(runBlocking { reader.read("no-such-vhost") }, "a virtual host the broker answers 404 for")
        topologyFake.failQueuePage(2, CatalogResult.Unreachable)
        assertNull(runBlocking { reader.read("/") }, "a failing second page must not give half of the queues")
        topologyFake.clearFailures()
        streamFake.failCall("listStreams")
        assertNull(runBlocking { reader.read("/") }, "a failing stream list must not give a list without streams")
        streamFake.clearFailures()
        topologyFake.hasManagementTag = false
        assertNull(runBlocking { reader.read("/") })
        val planner = plannerOf(c)
        assertEquals(PlanGraphNotice.OnlyThePlan(OnlyThePlanCause.BrokerNotRead), planner.state.value.graph.notice)
    }

    @Test
    fun `the reader follows the plan's virtual host and never asks twice for the same one`() {
        seedBroker()
        topologyFake.addQueue(FakeTopologyCatalog.quorum("invoices", vhost = "billing"))
        val c = compose().also { it.connect("uat-eu") }
        val planner = plannerOf(c)
        everything.clear()
        planner.setVirtualHost("billing")
        assertEquals(setOf("invoices"), planner.state.value.known?.queues)
        val firstRound = everything.toList()
        planner.setVirtualHost("billing")
        planner.selectReviewView(dev.caerbannog.core.planner.ReviewView.Json)
        assertEquals(firstRound, everything.toList(), "no further request: no polling, no read for an unchanged virtual host")
    }

    private fun PlannerViewModel.selectReviewViewForTest() = selectReviewView(dev.caerbannog.core.planner.ReviewView.Json)
}
