package dev.caerbannog.core.publish

import dev.caerbannog.core.connections.fakes.FakeMessagePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishMessageViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val fakePublisher = FakeMessagePublisher()

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `publish to stream sends request with properties and updates confirmation`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )

        vm.setTargetKind(PublishTargetKind.STREAM)
        vm.setTargetName("orders-events")
        vm.setBody("""{"orderId":"TEST-1"}""")
        vm.setMessageId("test-0001")
        vm.setContentType("application/json")
        vm.addApplicationProperty("tenant", "eu")

        assertTrue(vm.state.value.canPublish)

        vm.publish()

        val state = vm.state.value
        assertFalse(state.isPublishing)
        assertEquals("Message published to stream 'orders-events'", state.confirmation)
        assertNull(state.error)

        assertEquals(1, fakePublisher.publishedMessages.size)
        val published = fakePublisher.publishedMessages.single()
        assertEquals(PublishTargetKind.STREAM, published.target.kind)
        assertEquals("orders-events", published.target.name)
        assertEquals("""{"orderId":"TEST-1"}""", published.payload.decodeToString())
        assertEquals("test-0001", published.properties["messageId"])
        assertEquals("application/json", published.properties["contentType"])
        assertEquals("eu", published.applicationProperties["tenant"])
    }

    @Test
    fun `publish to exchange with routing key sends request and updates confirmation`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )

        vm.setTargetKind(PublishTargetKind.EXCHANGE)
        vm.setTargetName("orders.events")
        vm.setRoutingKey("order.created")
        vm.setBody("""{"orderId":"TEST-2"}""")

        vm.publish()

        val state = vm.state.value
        assertEquals("Message published to exchange 'orders.events'", state.confirmation)
        assertNull(state.error)

        val published = fakePublisher.publishedMessages.single()
        assertEquals(PublishTargetKind.EXCHANGE, published.target.kind)
        assertEquals("orders.events", published.target.name)
        assertEquals("order.created", published.target.routingKey)
    }

    @Test
    fun `publish to queue sends request with queue target`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )

        vm.setTargetKind(PublishTargetKind.QUEUE)
        vm.setTargetName("orders")
        vm.setBody("hello")

        vm.publish()

        val state = vm.state.value
        assertEquals("Message published to queue 'orders'", state.confirmation)

        val published = fakePublisher.publishedMessages.single()
        assertEquals(PublishTargetKind.QUEUE, published.target.kind)
        assertEquals("orders", published.target.name)
        assertEquals("hello", published.payload.decodeToString())
    }

    @Test
    fun `publishing without write permission is disabled by gate`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = false,
            scope = scope,
        )

        val state = vm.state.value
        assertTrue(state.gate is PublishGate.Disabled)
        assertFalse(state.canPublish)

        vm.setTargetName("orders-events")
        vm.setBody("payload")
        assertFalse(vm.state.value.canPublish)

        vm.publish()

        assertEquals(0, fakePublisher.publishedMessages.size)
        assertTrue(vm.state.value.error?.contains("no write permission") == true)
    }

    @Test
    fun `broker refusal updates error and leaves confirmation null`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )
        fakePublisher.refuseNextWith = "Access error from broker"

        vm.setTargetName("orders-events")
        vm.setBody("payload")
        vm.publish()

        val state = vm.state.value
        assertNull(state.confirmation)
        assertEquals("Access error from broker", state.error)
    }

    @Test
    fun `broker failure updates error`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )
        fakePublisher.failNextWith = "Network connection closed"

        vm.setTargetName("orders-events")
        vm.setBody("payload")
        vm.publish()

        val state = vm.state.value
        assertNull(state.confirmation)
        assertEquals("Network connection closed", state.error)
    }

    @Test
    fun `cancel leaves nothing sent and resets error`(): Unit = runBlocking {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )

        vm.setTargetName("orders-events")
        vm.setBody("payload")
        vm.cancel()

        assertEquals(0, fakePublisher.publishedMessages.size)
        assertFalse(vm.state.value.isPublishing)
    }

    @Test
    fun `warning banner mentions active broker connection name`() {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )
        assertTrue(vm.state.value.warningBanner.contains("prod-eu"))
    }

    @Test
    fun `add and remove application properties updates state`() {
        val vm = PublishMessageViewModel(
            publisher = fakePublisher,
            connectionName = "prod-eu",
            vhost = "/",
            writePermission = true,
            scope = scope,
        )

        vm.addApplicationProperty("tenant", "eu")
        vm.addApplicationProperty("region", "frankfurt")
        assertEquals(listOf("tenant" to "eu", "region" to "frankfurt"), vm.state.value.applicationProperties)

        vm.removeApplicationProperty("tenant")
        assertEquals(listOf("region" to "frankfurt"), vm.state.value.applicationProperties)
    }
}
