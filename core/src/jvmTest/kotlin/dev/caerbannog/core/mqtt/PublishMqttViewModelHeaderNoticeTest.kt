package dev.caerbannog.core.mqtt

import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10b step 2: `initialHeaders` and `HeaderNotice` of [PublishMqttViewModel]. Both are optional and additive: the callers that pass none behave as before
 * (the old PublishMqttViewModelTest stays unedited). The notice never touches `canPublish`.
 */
class PublishMqttViewModelHeaderNoticeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val publisher = FakeMqttMessagePublisher()
    private val notice = HeaderNotice("correlation-id", "Read the application property.")

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun vm(topic: String = "devices/d42/answer", headers: List<MqttHeader> = listOf(MqttHeader("correlation-id", "abc")), notice: HeaderNotice? = this.notice) =
        PublishMqttViewModel(publisher = publisher, connectionName = "b", scope = scope, initialTopic = topic, initialHeaders = headers, headerNotice = notice)

    // ---- initial headers ----

    @Test
    fun `initial headers are in the state and are sent as user properties when Publish is pressed`() {
        val vm = vm()
        assertEquals(listOf(MqttHeader("correlation-id", "abc")), vm.state.value.headers)
        assertTrue(publisher.publishedMessages.isEmpty(), "opening the dialog must publish nothing")
        vm.publish()
        assertEquals(listOf(MqttHeader("correlation-id", "abc")), publisher.publishedMessages.single().headers)
        assertEquals("devices/d42/answer", publisher.publishedMessages.single().topic)
    }

    @Test
    fun `without the new parameters the state is as before`() {
        val old = PublishMqttViewModel(publisher = publisher, connectionName = "b", scope = scope, initialTopic = "a/b")
        assertEquals(emptyList(), old.state.value.headers)
        assertNull(old.state.value.headerNoticeText)
    }

    @Test
    fun `an initial header can be edited like any other`() {
        val vm = vm(notice = null)
        vm.setHeader(0, "correlation-id", "mine")
        vm.publish()
        assertEquals(listOf(MqttHeader("correlation-id", "mine")), publisher.publishedMessages.single().headers)
    }

    // ---- the notice ----

    @Test
    fun `the notice text shows while a header with the exact key is present`() {
        assertEquals("Read the application property.", vm().state.value.headerNoticeText)
    }

    @Test
    fun `the notice goes away when the header is removed and comes back with a header of that key`() {
        val vm = vm()
        vm.removeHeader(0)
        assertNull(vm.state.value.headerNoticeText)
        vm.addHeader()
        assertNull(vm.state.value.headerNoticeText)
        vm.setHeader(0, "correlation-id", "")
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
    }

    @Test
    fun `renaming the header hides the notice and renaming it back shows it again`() {
        val vm = vm()
        vm.setHeader(0, "trace-id", "abc")
        assertNull(vm.state.value.headerNoticeText)
        vm.setHeader(0, "correlation-id", "abc")
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
    }

    @Test
    fun `the key is trimmed and is case-sensitive`() {
        val vm = vm()
        vm.setHeader(0, "  correlation-id  ", "abc")
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
        for (key in listOf("Correlation-Id", "correlation_id", "correlation-id2", "xcorrelation-id", "")) {
            vm.setHeader(0, key, "abc")
            assertNull(vm.state.value.headerNoticeText, "key \"$key\"")
        }
    }

    @Test
    fun `another header of that key among others is enough`() {
        val vm = vm(headers = listOf(MqttHeader("trace-id", "t"), MqttHeader("correlation-id", "c")))
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
        vm.removeHeader(1)
        assertNull(vm.state.value.headerNoticeText)
    }

    @Test
    fun `the value of the header does not matter`() {
        val vm = vm(headers = listOf(MqttHeader("correlation-id", "")))
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
    }

    @Test
    fun `the notice never changes canPublish`() {
        val with = vm().state.value
        val without = vm(notice = null).state.value
        assertTrue(with.canPublish)
        assertEquals(without.canPublish, with.canPublish)
        // an invalid topic still blocks, the notice does not unblock it
        val bad = vm(topic = "a/+/b").state.value
        assertFalse(bad.canPublish)
        // and no topic still blocks
        assertFalse(vm(topic = "").state.value.canPublish)
        // a hidden notice does not change it either
        val vm = vm()
        vm.removeHeader(0)
        assertTrue(vm.state.value.canPublish)
    }

    @Test
    fun `a dialog opened without a notice shows none, even with the header of that name`() {
        val vm = vm(notice = null)
        assertNull(vm.state.value.headerNoticeText)
        vm.setHeader(0, "correlation-id", "x")
        assertNull(vm.state.value.headerNoticeText)
    }

    @Test
    fun `the notice survives a failed publish and feedback changes`() {
        val vm = vm()
        publisher.refuseNextWith = "broker said no"
        vm.publish()
        assertEquals("broker said no", vm.state.value.error)
        assertEquals("Read the application property.", vm.state.value.headerNoticeText)
    }
}
