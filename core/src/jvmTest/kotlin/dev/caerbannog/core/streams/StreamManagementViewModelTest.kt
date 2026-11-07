package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamManagementViewModelTest {

    private lateinit var scope: CoroutineScope
    private lateinit var fakeManager: FakeStreamManager
    private val existingStreams = mutableSetOf("orders-events", "existing-stream")

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fakeManager = FakeStreamManager()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun createViewModel(
        configurePermission: Boolean = true,
        onCreated: suspend (String) -> Unit = {},
        onDeleted: suspend (String) -> Unit = {},
    ): StreamManagementViewModel = StreamManagementViewModel(
        manager = fakeManager,
        connectionName = "uat-eu",
        vhost = "/",
        configurePermission = configurePermission,
        existingStreams = { existingStreams },
        consumerCounts = { if (it == "orders-events") 3 else 0 },
        onStreamCreated = onCreated,
        onStreamDeleted = onDeleted,
        scope = scope,
    )

    @Test
    fun `cannot create stream when name is blank`() = runBlocking<Unit> {
        val vm = createViewModel()
        vm.openCreate()
        vm.setCreateName("")
        val result = vm.confirmCreate()

        assertFalse(result)
        assertEquals("A name is required", vm.createState.value.error)
        assertTrue(fakeManager.createdStreams.isEmpty())
    }

    @Test
    fun `cannot create stream when name already exists`() = runBlocking<Unit> {
        val vm = createViewModel()
        vm.openCreate()
        vm.setCreateName("orders-events")
        val result = vm.confirmCreate()

        assertFalse(result)
        assertEquals("A stream with this name exists", vm.createState.value.error)
        assertTrue(fakeManager.createdStreams.isEmpty())
    }

    @Test
    fun `create stream succeeds with valid name`() = runBlocking<Unit> {
        var createdCallback: String? = null
        val vm = createViewModel(onCreated = { createdCallback = it })
        vm.openCreate()
        vm.setCreateName("payments-stream")
        val result = vm.confirmCreate()

        assertTrue(result)
        assertNull(vm.createState.value.error)
        assertFalse(vm.createState.value.isOpen)
        assertEquals("payments-stream", createdCallback)
        assertEquals(listOf("/" to "payments-stream"), fakeManager.createdStreams)
    }

    @Test
    fun `create stream shows broker error when creation is refused`() = runBlocking<Unit> {
        fakeManager.refuseCreateWith = "Broker error: out of storage"
        val vm = createViewModel()
        vm.openCreate()
        vm.setCreateName("payments-stream")
        val result = vm.confirmCreate()

        assertFalse(result)
        assertEquals("Broker error: out of storage", vm.createState.value.error)
        assertTrue(vm.createState.value.isOpen)
    }

    @Test
    fun `delete stream requires exact name typed to confirm`() = runBlocking<Unit> {
        val vm = createViewModel()
        vm.openDelete("audit-log")

        assertTrue(vm.deleteState.value.isOpen)
        assertEquals("audit-log", vm.deleteState.value.streamName)
        assertFalse(vm.deleteState.value.canConfirm)

        vm.setDeleteConfirmation("audit")
        assertFalse(vm.deleteState.value.canConfirm)

        vm.setDeleteConfirmation("audit-log")
        assertTrue(vm.deleteState.value.canConfirm)
    }

    @Test
    fun `delete stream warns when consumers are connected`() = runBlocking<Unit> {
        val vm = createViewModel()
        vm.openDelete("orders-events")

        assertEquals(3, vm.deleteState.value.consumers)
        assertEquals("3 consumers are connected", vm.deleteState.value.consumersWarning)
    }

    @Test
    fun `cancel delete does not delete stream on broker`() = runBlocking<Unit> {
        val vm = createViewModel()
        vm.openDelete("audit-log")
        vm.setDeleteConfirmation("audit-log")
        vm.closeDelete()

        assertFalse(vm.deleteState.value.isOpen)
        assertTrue(fakeManager.deletedStreams.isEmpty())
    }

    @Test
    fun `confirm delete deletes stream and notifies callback`() = runBlocking<Unit> {
        var deletedCallback: String? = null
        val vm = createViewModel(onDeleted = { deletedCallback = it })
        vm.openDelete("audit-log")
        vm.setDeleteConfirmation("audit-log")
        val result = vm.confirmDelete()

        assertTrue(result)
        assertFalse(vm.deleteState.value.isOpen)
        assertEquals("audit-log", deletedCallback)
        assertEquals(listOf("/" to "audit-log"), fakeManager.deletedStreams)
    }

    @Test
    fun `delete stream shows error when broker refuses`() = runBlocking<Unit> {
        fakeManager.refuseDeleteWith = "Broker error: stream is busy"
        val vm = createViewModel()
        vm.openDelete("audit-log")
        vm.setDeleteConfirmation("audit-log")
        val result = vm.confirmDelete()

        assertFalse(result)
        assertEquals("Broker error: stream is busy", vm.deleteState.value.error)
        assertTrue(vm.deleteState.value.isOpen)
    }

    @Test
    fun `actions are gated when user has no configure permission`() = runBlocking<Unit> {
        val vm = createViewModel(configurePermission = false)

        assertFalse(vm.canCreate)
        assertFalse(vm.canDelete)
        assertNotNull(vm.gateReason)
        val reason = vm.gateReason
        assertTrue(reason.contains("topology is locked", ignoreCase = true))
        assertTrue(reason.contains("configure permission", ignoreCase = true))
    }
}
