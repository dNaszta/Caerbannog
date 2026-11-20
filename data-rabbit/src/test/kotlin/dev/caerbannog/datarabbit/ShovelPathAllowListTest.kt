package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-08 scenario "The app never reads the shovel parameters": the allow-list refuses everything but the five shovel requests. */
class ShovelPathAllowListTest {
    @Test
    fun `the six shovel requests are allowed`() {
        listOf(
            "GET" to "/api/whoami", "GET" to "/api/overview", "GET" to "/api/shovels", "GET" to "/api/queues/%2F/orders", "GET" to "/api/queues/my%20vhost/a%20b%2Fc%25d",
            "PUT" to "/api/parameters/shovel/%2F/caerbannog-migrate-orders", "DELETE" to "/api/parameters/shovel/%2F/caerbannog-migrate-a%20b%2Fc%25d",
        ).forEach { (m, p) -> assertTrue(ShovelPathAllowList.isAllowed(m, p), "$m $p should be allowed") }
    }

    @Test
    fun `everything else is refused, above all the parameter reads that show the source password`() {
        listOf(
            "GET" to "/api/parameters", "GET" to "/api/parameters/shovel", "GET" to "/api/parameters/shovel/%2F/caerbannog-migrate-orders",
            "GET" to "/api/global-parameters", "GET" to "/api/definitions", "GET" to "/api/definitions/%2F",
            "POST" to "/api/queues/%2F/orders/get", "GET" to "/api/queues/%2F/orders/get", "POST" to "/api/queues/%2F/orders/contents",
            "DELETE" to "/api/queues/%2F/orders/contents", "DELETE" to "/api/queues/%2F/orders", "PUT" to "/api/queues/%2F/orders",
            "POST" to "/api/shovels", "PUT" to "/api/shovels", "DELETE" to "/api/shovels", "PUT" to "/api/whoami", "POST" to "/api/whoami",
            "POST" to "/api/parameters/shovel/%2F/x", "GET" to "/api/exchanges/%2F/amq.default/publish", "POST" to "/api/exchanges/%2F/amq.default/publish",
            "PUT" to "/api/overview", "POST" to "/api/overview", "DELETE" to "/api/overview", "GET" to "/api/overview?columns=x", "GET" to "/api/overview/x",
            "GET" to "/api/overview#x", "GET" to "/api/extensions", "GET" to "/api/vhosts", "GET" to "/api/queues/%2F", "GET" to "/api/queues",
            "PUT" to "/api/parameters/shovel/%2F", "DELETE" to "/api/parameters/shovel/%2F/a/b", "PUT" to "/api/parameters/federation-upstream/%2F/x",
            "DELETE" to "/api/parameters/shovel/%2F/..", "GET" to "/api/queues/%2F/..", "GET" to "/api/queues/%2F/a?x=1", "GET" to "/api/shovels#x",
            "GET" to "/api/queues/%2F/ord ers", "GET" to "api/shovels", "GET" to "/other/api/shovels", "GET" to "", "TRACE" to "/api/shovels",
        ).forEach { (m, p) -> assertFalse(ShovelPathAllowList.isAllowed(m, p), "$m $p must be refused") }
    }

    @Test
    fun `a queue that is named get is a name like any other, a suffix is not`() {
        assertTrue(ShovelPathAllowList.isAllowed("GET", "/api/queues/%2F/get"))
        assertFalse(ShovelPathAllowList.isAllowed("GET", "/api/queues/%2F/get/get"))
    }

    @Test
    fun `checked returns the path when allowed and throws before any request when not`() {
        assertEquals("/api/shovels", runCatching { ShovelPathAllowList.checked("GET", "/api/shovels") }.getOrElse { "THROWN" })
        assertThrows(PathNotAllowedException::class.java) { ShovelPathAllowList.checked("GET", "/api/parameters/shovel/%2F/x") }
    }
}
