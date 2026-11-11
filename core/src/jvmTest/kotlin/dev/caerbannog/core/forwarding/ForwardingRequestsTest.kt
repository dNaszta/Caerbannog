package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.migrate3x.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** M8-09: the one pure function that builds the request from the form (D5: same queue name, the virtual host of the connected 4.x profile). */
class ForwardingRequestsTest {
    private val source = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc-reader", tls = true)
    private val destination = ConnectionProfile("new-eu", "new.example", "prod", "svc", tls = true)
    private fun form(host: String = "legacy.example", port: Int = 5671, other: Boolean = false, user: String = "", password: String? = null, keep: Boolean = false) =
        ForwardingForm(host, port, other, user, password?.let { SecretValue.of(it) }, keep)

    @Test
    fun `the destination is the same queue name in the virtual host of the 4x profile, the source is the queue of the analysis`() {
        val r = ForwardingRequests.of(source, "sales", "orders", destination, form())
        assertEquals(listOf("legacy-eu", "sales", "orders"), listOf(r.source.connectionName, r.source.vhost, r.source.queue))
        assertEquals(listOf("new-eu", "prod", "orders"), listOf(r.destinationConnection, r.destinationVhost, r.destinationQueue))
        assertEquals(ShovelRef("prod", "caerbannog-migrate-orders"), r.shovel)
    }

    @Test
    fun `the address follows the TLS switch of the source profile, the host and port of the form, and the vhost of the queue`() {
        val tls = ForwardingRequests.of(source, "sales", "orders", destination, form(host = "amqp.legacy", port = 6000))
        assertEquals("amqps://svc-reader@amqp.legacy:6000/sales", tls.source.address.toString())
        val plain = ForwardingRequests.of(source.copy(tls = false), "/", "orders", destination, form(port = 5672))
        assertEquals("amqp://svc-reader@legacy.example:5672/%2F", plain.source.address.toString())
    }

    @Test
    fun `by default the profile user is used and no credentials are in the request`() {
        assertNull(ForwardingRequests.of(source, "/", "orders", destination, form()).source.credentials)
    }

    @Test
    fun `another user replaces the profile user in the address and carries the typed password, which no text shows`() {
        val r = ForwardingRequests.of(source, "/", "orders", destination, form(other = true, user = "mover", password = "s3cret-pw"))
        assertEquals("amqps://mover@legacy.example:5671/%2F", r.source.address.toString())
        assertEquals("mover", r.source.credentials?.username)
        assertEquals("s3cret-pw", r.source.credentials?.passwordForRequest())
        assertFalse(r.toString().contains("s3cret-pw"))
    }

    @Test
    fun `another user switched off ignores a stale user name and password`() {
        assertNull(ForwardingRequests.of(source, "/", "orders", destination, form(other = false, user = "mover", password = "pw")).source.credentials)
    }

    @Test
    fun `keep running is never, the default is queue-length`() {
        assertEquals(SrcDeleteAfter.NEVER, ForwardingRequests.of(source, "/", "orders", destination, form(keep = true)).srcDeleteAfter)
        assertEquals(SrcDeleteAfter.QUEUE_LENGTH, ForwardingRequests.of(source, "/", "orders", destination, form(keep = false)).srcDeleteAfter)
    }
}
