package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.streams.Capabilities
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.ConsumerRow
import dev.caerbannog.core.streams.FirstOffset
import dev.caerbannog.core.streams.PublisherRow
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import dev.caerbannog.datarabbit.JsonFileAppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2-05a step 2: the composition root of the stream list (brief Scope items 1 and 3, Acceptance "CompositionRootWiringTest").
 * Safety as in [CompositionRootWiringTest]: a temp folder, an in-memory secret store, a fake gateway; never `~/.caerbannog`,
 * never the keychain, no broker. The tests always pass `appSettings` (a temp file); the older wiring tests that pass none read the
 * default `~/.caerbannog/settings.json` and never write it (none of them sets the interval).
 *
 * Contract: `createAppComposition(..., appSettings: AppSettings = JsonFileAppSettings(JsonFileAppSettings.defaultFile()),
 *   uiDispatcher: CoroutineDispatcher = Dispatchers.Main)`. The parameter name `uiDispatcher` is the test developer's choice (the brief
 *   says "a parameter"); rename it here if the developer picks another name. The scope of the stream list is
 *   `CoroutineScope(SupervisorJob() + uiDispatcher)`; `AppComposition.close()` cancels it.
 *
 * The dispatcher test uses a QUEUE dispatcher: it runs nothing until the test says so, on the test thread. So "the view model
 * works on the dispatcher it was given" is observed as "the broker was asked from the test thread, inside runAll()", not as a timing.
 */
class CompositionRootStreamListWiringTest {

    @TempDir
    lateinit var dir: File

    private class MapSecrets(private val map: MutableMap<String, String> = mutableMapOf()) : SecretStore {
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

    /** A dispatcher that only queues; [runAll] runs everything queued, on the calling thread, until the queue is empty. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    /** Answers every call with Unexpected (the first load fails, which is enough) and writes down the thread that asked. */
    private class AskedCatalog : StreamCatalog {
        val askedOn: MutableList<String> = Collections.synchronizedList(ArrayList())
        val firstCall = CountDownLatch(1)
        private fun <T> asked(): CatalogResult<T> {
            askedOn += Thread.currentThread().name.substringBefore(" @coroutine")
            firstCall.countDown()
            return CatalogResult.Unexpected
        }
        override suspend fun listStreams(request: StreamListRequest): CatalogResult<StreamPage> = asked()
        override suspend fun streamDetail(vhost: String, name: String): CatalogResult<StreamSummary> = asked()
        override suspend fun publishers(vhost: String, stream: String): CatalogResult<List<PublisherRow>> = asked()
        override suspend fun publisherCounts(vhost: String): CatalogResult<Map<String, Int>> = asked()
        override suspend fun consumers(vhost: String, stream: String): CatalogResult<List<ConsumerRow>> = asked()
        override suspend fun superStreams(vhost: String): CatalogResult<List<dev.caerbannog.core.streams.SuperStreamLayout>> = asked()
        override suspend fun capabilities(): CatalogResult<Capabilities> = asked()
    }

    private val catalog = AskedCatalog()

    private val gateway = object : ConnectionGateway {
        override suspend fun open(profile: ConnectionProfile): OpenResult = OpenResult.Opened(object : HeldConnection {
            override val catalog = this@CompositionRootStreamListWiringTest.catalog
            override val offsets = object : StreamOffsets {
                override suspend fun firstOffset(vhost: String, stream: String) = FirstOffset.Unavailable
            }
            override val topology = FakeTopologyCatalog()
            override fun close() = Unit
        })
    }

    private val input = ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret")
    private val settingsFile get() = File(dir, "settings.json")

    private fun compose(
        uiDispatcher: CoroutineDispatcher,
        settingsFile: File = this.settingsFile,
    ) = createAppComposition(
        File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
        appSettings = JsonFileAppSettings(settingsFile), uiDispatcher = uiDispatcher,
    ).also { it.viewModel.addConnection(input) }

    // --- F-2: the dispatcher given is the dispatcher used ------------------------------------------------------------

    @Test
    fun `the stream list works on the dispatcher it is given, and on no other thread`() = runBlocking<Unit> {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.viewModel.connect("uat-eu")
            composition.streamList.setShown(true)
            queue.runAll()

            val me = Thread.currentThread().name.substringBefore(" @coroutine")
            assertTrue(catalog.askedOn.isNotEmpty(), "the broker was never asked after runAll(): the stream list does not run on the dispatcher given")
            assertEquals(
                List(catalog.askedOn.size) { me }, catalog.askedOn.toList(),
                "the broker was asked from other threads than the one that ran the given dispatcher (the test thread '$me')",
            )
        } finally {
            composition.close()
        }
    }

    /**
     * The default is the UI dispatcher of the app: Swing's `Dispatchers.Main`, whose thread is the AWT event thread (Compose
     * Desktop's UI thread). Needs `kotlinx-coroutines-swing` on the desktopApp runtime classpath (brief item 1). No window opens.
     */
    @Test
    fun `by default the stream list works on the Swing UI thread`() = runBlocking<Unit> {
        val composition = createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = Permissive, reachabilityChecker = Reachable, gateway = gateway,
            appSettings = JsonFileAppSettings(settingsFile),
        ).also { it.viewModel.addConnection(input) }
        try {
            composition.viewModel.connect("uat-eu")
            composition.streamList.setShown(true)
            assertTrue(catalog.firstCall.await(20, TimeUnit.SECONDS), "the stream list never asked the broker")
            val threads = catalog.askedOn.toList().distinct()
            assertTrue(threads.all { it.startsWith("AWT-EventQueue") }, "the default scope must run on the Swing UI thread (AWT-EventQueue-*), the broker was asked from $threads")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `closing the composition stops the stream list, nothing reaches the broker afterwards`() = runBlocking<Unit> {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        composition.viewModel.connect("uat-eu")
        composition.streamList.setShown(true)
        queue.runAll()
        val before = catalog.askedOn.size

        composition.close()
        composition.streamList.setShown(true)
        composition.streamList.refreshNow()
        queue.runAll()

        assertEquals(before, catalog.askedOn.size, "the broker was asked after the window closed")
    }

    // --- item 3: the settings file ---------------------------------------------------------------------------------------

    @Test
    fun `the interval is written to the settings file when it changes`() {
        val queue = QueueDispatcher()
        val composition = compose(queue)
        try {
            composition.streamList.setRefreshInterval(RefreshInterval.Every30)
            queue.runAll()

            assertTrue(settingsFile.exists(), "no settings file was written at $settingsFile; the composition must use the AppSettings it was given")
            assertTrue(Regex("\"streamListRefreshSeconds\"\\s*:\\s*30\\b").containsMatchIn(settingsFile.readText()), "settings file: <${settingsFile.readText()}>")
        } finally {
            composition.close()
        }
    }

    @Test
    fun `a new composition on the same settings file shows the interval of the last run`() {
        settingsFile.writeText("""{"streamListRefreshSeconds": 60}""")
        val composition = compose(QueueDispatcher())
        try {
            assertEquals(RefreshInterval.Every60, composition.streamList.state.value.refreshInterval)
        } finally {
            composition.close()
        }
    }

    @Test
    fun `a corrupt settings file does not stop the app from starting`() {
        settingsFile.writeText("{ this is not json")
        val composition = compose(QueueDispatcher())
        try {
            assertEquals(RefreshInterval.Every5, composition.streamList.state.value.refreshInterval)
        } finally {
            composition.close()
        }
    }
}
