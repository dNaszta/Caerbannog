package dev.caerbannog.core.topology

import dev.caerbannog.core.browser.PayloadViewKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class QueueMessageInspectionViewModelTest {

    private class FakeInspector : QueueMessageInspector {
        val calls = mutableListOf<Triple<String, String, Int>>()
        var nextResult: QueueInspectionResult = QueueInspectionResult.Success(emptyList())

        override suspend fun inspectQueueMessages(vhost: String, queue: String, count: Int): QueueInspectionResult {
            calls += Triple(vhost, queue, count)
            return nextResult
        }
    }

    @Test
    fun `inspectMessages fetches via QueueMessageInspector in requeue mode and marks messages as not acknowledged`() = runTest {
        val inspector = FakeInspector()
        val msg1 = InspectedQueueMessage(
            index = 1,
            payload = """{"orderId":"ORD-101","status":"PENDING"}""".encodeToByteArray(),
            payloadBytes = 40L,
            payloadEncoding = "string",
            redelivered = true,
            exchange = "orders.exchange",
            routingKey = "order.created",
            messageCount = 5L,
            properties = mapOf("content_type" to "application/json", "message_id" to "m-101"),
            headers = mapOf("tenant" to "eu"),
        )
        val msg2 = InspectedQueueMessage(
            index = 2,
            payload = "plain-payload".encodeToByteArray(),
            payloadBytes = 13L,
            payloadEncoding = "string",
            redelivered = false,
            exchange = "",
            routingKey = "orders",
            messageCount = 4L,
        )
        inspector.nextResult = QueueInspectionResult.Success(listOf(msg1, msg2))

        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = QueueMessageInspectionViewModel(
            inspector = inspector,
            vhost = "/",
            queue = "orders",
            scope = scope,
        )

        assertIs<QueueInspectionState.Idle>(vm.state.value)

        vm.setCount(5)
        vm.inspectMessages()
        scope.advanceUntilIdle()

        assertEquals(listOf(Triple("/", "orders", 5)), inspector.calls)
        val loaded = assertIs<QueueInspectionState.Loaded>(vm.state.value)
        assertEquals(2, loaded.messages.size)
        assertEquals(1, loaded.selectedIndex)
        assertEquals(QueueInspectionConstants.NOT_ACKNOWLEDGED_BADGE, loaded.statusBadgeText)

        // Every message is explicitly marked not acknowledged (requeued)
        loaded.messages.forEach { m ->
            assertFalse(m.acknowledged)
            assertEquals(QueueInspectionConstants.ACK_REQUEUE_TRUE, m.ackMode)
            assertEquals("Not acknowledged (requeued)", m.acknowledgementStatusText)
        }

        // Selecting message 2 and switching payload view kind
        vm.selectMessage(2)
        scope.advanceUntilIdle()
        val afterSelect = assertIs<QueueInspectionState.Loaded>(vm.state.value)
        assertEquals(2, afterSelect.selectedIndex)
        assertEquals("plain-payload", assertNotNull(afterSelect.inspectSelected()).displayText)

        vm.setPayloadViewKind(PayloadViewKind.HEX)
        scope.advanceUntilIdle()
        val afterHex = assertIs<QueueInspectionState.Loaded>(vm.state.value)
        assertEquals(PayloadViewKind.HEX, afterHex.payloadViewKind)

        // Clearing returns to Idle
        vm.clear()
        scope.advanceUntilIdle()
        assertIs<QueueInspectionState.Idle>(vm.state.value)
    }

    @Test
    fun `empty queue inspection transitions to Empty state with requeue notice`() = runTest {
        val inspector = FakeInspector()
        inspector.nextResult = QueueInspectionResult.Success(emptyList())
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = QueueMessageInspectionViewModel(inspector, "/", "orders", scope)

        vm.inspectMessages()
        scope.advanceUntilIdle()

        val empty = assertIs<QueueInspectionState.Empty>(vm.state.value)
        assertEquals(QueueInspectionConstants.REQUEUE_MODE_NOTICE, empty.notice)
    }

    @Test
    fun `refused or failed inspection transitions to Error state`() = runTest {
        val inspector = FakeInspector()
        inspector.nextResult = QueueInspectionResult.Refused("User does not have read access to queue 'orders'")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val vm = QueueMessageInspectionViewModel(inspector, "/", "orders", scope)

        vm.inspectMessages()
        scope.advanceUntilIdle()

        val error = assertIs<QueueInspectionState.Error>(vm.state.value)
        assertEquals("User does not have read access to queue 'orders'", error.reason)
    }
}
