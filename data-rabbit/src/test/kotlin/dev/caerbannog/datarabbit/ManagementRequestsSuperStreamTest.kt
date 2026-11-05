package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * M2-07 step 2 (no Docker): the two URLs of the super stream layout (spike S-A: `GET /api/exchanges/{vhost}` and `GET /api/bindings/{vhost}`,
 * both unpaged: without `page` the broker answers a plain array with everything; the exchanges path refuses a page size over 500 and the
 * bindings path pages silently, so no paging parameter is ever sent). The vhost is one path segment: "/" is %2F, a space %20, never "+".
 * Reads only: both are GETs by construction (the catalog has no other verb).
 */
class ManagementRequestsSuperStreamTest {

    private val requests = ManagementRequests("https://rabbit.example.com:15671")

    @Test
    fun `exchanges and bindings are the vhost-wide paths without any query`() {
        assertEquals("https://rabbit.example.com:15671/api/exchanges/%2F", requests.exchanges("/"))
        assertEquals("https://rabbit.example.com:15671/api/bindings/%2F", requests.bindings("/"))
    }

    @Test
    fun `a vhost with a space, a slash or a plus is encoded as one path segment`() {
        assertEquals("https://rabbit.example.com:15671/api/exchanges/my%20vhost%2Fx", requests.exchanges("my vhost/x"))
        assertEquals("https://rabbit.example.com:15671/api/bindings/a%2Bb", requests.bindings("a+b"))
        assertEquals("https://rabbit.example.com:15671/api/bindings/%C3%A9t%C3%A9", requests.bindings("été"))
    }

    @Test
    fun `a plain profile asks over http`() {
        val plain = ManagementRequests("http://rabbit.example.com:15672", BrokerTransport.Plain)

        assertEquals("http://rabbit.example.com:15672/api/exchanges/%2F", plain.exchanges("/"))
        assertEquals("http://rabbit.example.com:15672/api/bindings/%2F", plain.bindings("/"))
    }
}
