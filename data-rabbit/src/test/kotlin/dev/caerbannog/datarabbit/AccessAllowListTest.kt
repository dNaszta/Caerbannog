package dev.caerbannog.datarabbit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-12c: the executor allow-list gets exactly the access shapes; no delete of a vhost, user or permission; the shovel allow-list still refuses the definitions. */
class AccessAllowListTest {
    @Test
    fun `the four PUT shapes and their GETs are allowed, and the definitions GET`() {
        listOf(
            "PUT" to "/api/vhosts/%2Forders", "GET" to "/api/vhosts/%2Forders",
            "PUT" to "/api/users/svc", "GET" to "/api/users/svc",
            "PUT" to "/api/permissions/%2Forders/svc", "GET" to "/api/permissions/%2Forders/svc",
            "PUT" to "/api/topic-permissions/%2Forders/svc", "GET" to "/api/topic-permissions/%2Forders/svc",
            "GET" to "/api/definitions",
        ).forEach { (m, p) -> assertTrue(ExecutorPathAllowList.isAllowed(m, p), "$m $p should be allowed") }
    }

    @Test
    fun `no delete of a virtual host, user or permission, no list and no other write is allowed`() {
        listOf(
            "DELETE" to "/api/vhosts/%2Forders", "DELETE" to "/api/users/svc", "DELETE" to "/api/permissions/%2Forders/svc", "DELETE" to "/api/topic-permissions/%2Forders/svc",
            "POST" to "/api/users/svc", "PUT" to "/api/users", "GET" to "/api/users", "GET" to "/api/vhosts", "PUT" to "/api/definitions", "POST" to "/api/definitions",
            "PUT" to "/api/users/svc/permissions", "PUT" to "/api/permissions/%2Forders", "PUT" to "/api/users/svc/limits", "DELETE" to "/api/definitions",
        ).forEach { (m, p) -> assertFalse(ExecutorPathAllowList.isAllowed(m, p), "$m $p must be refused") }
    }

    @Test
    fun `the shovel gateway still may not read the definitions`() {
        assertFalse(ShovelPathAllowList.isAllowed("GET", "/api/definitions"))
        assertFalse(ShovelPathAllowList.isAllowed("PUT", "/api/users/svc"))
    }
}
