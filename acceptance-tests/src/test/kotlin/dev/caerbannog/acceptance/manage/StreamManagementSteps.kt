package dev.caerbannog.acceptance.manage

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.streams.StreamManagementViewModel
import dev.caerbannog.core.streams.StreamSummary
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

class StreamManagementSteps {

    private val fakeCatalog = FakeStreamCatalog()
    private val fakeManager = FakeStreamManager(fakeCatalog)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var connectionName: String = "uat-eu"
    private var vhost: String = "/"
    private var configurePermission: Boolean = true
    private val existingStreams = mutableSetOf<String>()
    private val consumerCounts = mutableMapOf<String, Int>()
    private var viewModel: StreamManagementViewModel? = null

    @Before
    fun setUp() {
        fakeManager.clear()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        connectionName = "uat-eu"
        vhost = "/"
        configurePermission = true
        existingStreams.clear()
        existingStreams += "orders-events"
        consumerCounts.clear()
        viewModel = null
        fakeCatalog.addStream(
            StreamSummary(
                name = "orders-events",
                vhost = "/",
                messages = 0L,
                consumers = 0,
                segments = 1,
                firstTimestamp = null,
                leader = null,
                node = null,
                members = emptyList(),
                online = emptyList(),
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun ensureViewModel(): StreamManagementViewModel {
        return viewModel ?: StreamManagementViewModel(
            manager = fakeManager,
            connectionName = connectionName,
            vhost = vhost,
            configurePermission = configurePermission,
            existingStreams = { existingStreams },
            consumerCounts = { consumerCounts[it] ?: 0 },
            onStreamCreated = { existingStreams += it },
            onStreamDeleted = { existingStreams -= it },
            scope = scope,
        ).also { viewModel = it }
    }

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        connectionName = name
    }

    @Given("the user has configure permission on {string}")
    fun theUserHasConfigurePermissionOn(vhostPath: String) {
        vhost = vhostPath
        configurePermission = true
    }

    @Given("the user has no configure permission on {string}")
    fun theUserHasNoConfigurePermissionOn(vhostPath: String) {
        vhost = vhostPath
        configurePermission = false
    }

    @Given("a stream {string}")
    fun aStream(name: String) {
        existingStreams += name
        fakeCatalog.addStream(
            StreamSummary(
                name = name,
                vhost = vhost,
                messages = 0L,
                consumers = 0,
                segments = 1,
                firstTimestamp = null,
                leader = null,
                node = null,
                members = emptyList(),
                online = emptyList(),
            ),
        )
    }

    @Given("a stream {string} with {int} consumers")
    fun aStreamWithConsumers(name: String, count: Int) {
        existingStreams += name
        consumerCounts[name] = count
        fakeCatalog.addStream(
            StreamSummary(
                name = name,
                vhost = vhost,
                messages = 0L,
                consumers = count,
                segments = 1,
                firstTimestamp = null,
                leader = null,
                node = null,
                members = emptyList(),
                online = emptyList(),
            ),
        )
    }

    @Given("the broker refuses to create streams with an error")
    fun theBrokerRefusesToCreateStreamsWithError() {
        fakeManager.refuseCreateWith = "Broker error: out of storage"
    }

    @When("I create a stream named {string}")
    fun iCreateAStreamNamed(name: String) = runBlocking {
        val vm = ensureViewModel()
        vm.openCreate()
        vm.setCreateName(name)
        vm.confirmCreate()
    }

    @When("I try to create a stream named {string}")
    fun iTryToCreateAStreamNamed(name: String) = runBlocking {
        val vm = ensureViewModel()
        vm.openCreate()
        vm.setCreateName(name)
        vm.confirmCreate()
    }

    @Then("the broker is asked to create the stream {string}")
    fun theBrokerIsAskedToCreateTheStream(name: String) {
        assertTrue(
            fakeManager.createdStreams.contains(vhost to name),
            "Expected create stream '$name' on '$vhost'",
        )
    }

    @Then("{string} appears in the stream list")
    fun appearsInTheStreamList(name: String) {
        assertTrue(existingStreams.contains(name), "Stream '$name' should appear in the stream list")
    }

    @Then("{string} is not added to the list")
    fun isNotAddedToTheList(name: String) {
        assertFalse(existingStreams.contains(name), "Stream '$name' should not be in the stream list")
    }

    @Then("the create action is refused with the message {string}")
    fun theCreateActionIsRefusedWithMessage(expectedMessage: String) {
        val error = ensureViewModel().createState.value.error
        assertEquals(expectedMessage, error)
    }

    @Then("I see the broker's error")
    fun iSeeTheBrokersError() {
        val error = ensureViewModel().createState.value.error
        assertNotNull(error, "Expected broker error to be displayed")
        assertTrue(
            error!!.contains("error", ignoreCase = true) || error.contains("refused", ignoreCase = true),
            "Expected error or refused message, got: $error",
        )
    }

    @When("I choose to delete the stream {string}")
    fun iChooseToDeleteTheStream(name: String) {
        ensureViewModel().openDelete(name)
    }

    @Then("the delete is disabled until I type {string}")
    fun theDeleteIsDisabledUntilIType(name: String) {
        val vm = ensureViewModel()
        assertTrue(vm.deleteState.value.isOpen)
        assertEquals(name, vm.deleteState.value.streamName)
        assertFalse(vm.deleteState.value.canConfirm, "Delete should be disabled before typing the stream name")
    }

    @When("I type {string} and confirm")
    fun iTypeAndConfirm(typed: String) = runBlocking {
        val vm = ensureViewModel()
        vm.setDeleteConfirmation(typed)
        vm.confirmDelete()
    }

    @Then("the broker is asked to delete the stream {string}")
    fun theBrokerIsAskedToDeleteTheStream(name: String) {
        assertTrue(
            fakeManager.deletedStreams.contains(vhost to name),
            "Expected delete stream '$name' on '$vhost'",
        )
    }

    @Then("{string} is no longer in the stream list")
    fun isNoLongerInTheStreamList(name: String) {
        assertFalse(existingStreams.contains(name), "Stream '$name' should no longer be in the stream list")
    }

    @When("I choose to delete the stream {string} and type {string}")
    fun iChooseToDeleteTheStreamAndType(name: String, typed: String) {
        val vm = ensureViewModel()
        vm.openDelete(name)
        vm.setDeleteConfirmation(typed)
    }

    @Then("the delete stays disabled")
    fun theDeleteStaysDisabled() {
        val vm = ensureViewModel()
        assertFalse(vm.deleteState.value.canConfirm, "Delete should remain disabled when typed name does not match")
    }

    @When("I choose to delete the stream {string} and cancel")
    fun iChooseToDeleteTheStreamAndCancel(name: String) {
        val vm = ensureViewModel()
        vm.openDelete(name)
        vm.closeDelete()
    }

    @Then("nothing is sent to the broker")
    fun nothingIsSentToTheBroker() {
        assertEquals(0, fakeManager.createdStreams.size, "No create requests should have been sent")
        assertEquals(0, fakeManager.deletedStreams.size, "No delete requests should have been sent")
    }

    @When("I open the stream list")
    fun iOpenTheStreamList() {
        ensureViewModel()
    }

    @Then("the {string} action is disabled")
    fun theActionIsDisabled(actionName: String) {
        val vm = ensureViewModel()
        when (actionName) {
            "Create stream" -> assertFalse(vm.canCreate, "Action '$actionName' should be disabled")
            "Delete stream" -> assertFalse(vm.canDelete, "Action '$actionName' should be disabled")
            else -> error("Unknown action: $actionName")
        }
    }

    @Then("it explains that topology is locked because the user has no configure permission")
    fun itExplainsThatTopologyIsLockedBecauseTheUserHasNoConfigurePermission() {
        val reason = ensureViewModel().gateReason
        assertNotNull(reason, "Expected gate reason explaining why action is locked")
        assertTrue(
            reason!!.contains("topology is locked", ignoreCase = true),
            "Reason should say topology is locked: $reason",
        )
        assertTrue(
            reason.contains("configure permission", ignoreCase = true),
            "Reason should mention configure permission: $reason",
        )
    }

    @Then("I am warned that {int} consumers are connected")
    fun iAmWarnedThatConsumersAreConnected(count: Int) {
        val warning = ensureViewModel().deleteState.value.consumersWarning
        assertNotNull(warning, "Expected consumer warning")
        assertEquals("$count consumers are connected", warning)
    }
}
