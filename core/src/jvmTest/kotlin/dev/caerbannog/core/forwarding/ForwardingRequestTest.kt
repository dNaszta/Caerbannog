package dev.caerbannog.core.forwarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** M8-07: one queue, one request, ack mode fixed, a deterministic shovel name (safeguards 5, 6, 9). */
class ForwardingRequestTest {
    @Test
    fun `the ack mode is on-confirm and no constructor field can change it`() {
        val request = Fwd.request()
        assertEquals("on-confirm", request.ackMode.wireName)
        assertEquals(listOf(AckMode.ON_CONFIRM), AckMode.entries)
        assertFalse(ForwardingRequest::class.java.declaredFields.any { it.name.lowercase().contains("ack") && it.name != "ackMode" }, "an ack field")
        assertFalse(ForwardingRequest::class.java.constructors.flatMap { it.parameterTypes.toList() }.any { it == AckMode::class.java }, "the ack mode is a constructor parameter")
    }

    @Test
    fun `the shovel is named caerbannog-migrate-queue in the destination vhost`() {
        assertEquals(ShovelRef("/", "caerbannog-migrate-orders"), Fwd.request().shovel)
        assertEquals("caerbannog-migrate-a b", ShovelNaming.nameFor("a b"))
    }

    @Test
    fun `a request holds one queue and the default is queue-length`() {
        assertEquals(SrcDeleteAfter.QUEUE_LENGTH, Fwd.request().srcDeleteAfter)
        assertEquals(SrcDeleteAfter.NEVER, Fwd.request(never = true).srcDeleteAfter)
        assertFalse(ForwardingRequest::class.java.declaredFields.any { kotlin.collections.Collection::class.java.isAssignableFrom(it.type) }, "a list form of the request exists")
    }
}
