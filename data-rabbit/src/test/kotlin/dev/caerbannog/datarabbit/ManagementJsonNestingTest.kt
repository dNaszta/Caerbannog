package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M5-07 call sites #3 to #5 (docs/tasks/M5-07-nesting-guard-for-untrusted-json.md): management API bodies come from the broker, which
 * may be broken or hostile. A 10,000-level body is `CatalogResult.Unexpected` (limit 128 in `ManagementJson.guarded`), never a
 * StackOverflowError (deliberately not caught: JUnit fails the test by itself). `isNotManagementUser` is false for a too-deep body
 * (limit 64).
 */
class ManagementJsonNestingTest {

    private val connectionsOk = """[{"name":"c1","vhost":"/","user":"u","protocol":"MQTT 3.1.1","client_properties":{"client_id":"dev-1"}}]"""

    @Test
    fun `a stream page nested ten thousand levels deep is Unexpected`() {
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseStreamPage(DeepJson.arrays()))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseStreamPage(DeepJson.objects()))
    }

    @Test
    fun `a stream page with a deep queue argument inside an otherwise valid page is Unexpected`() {
        val body = """{"items":[{"name":"s","type":"stream","arguments":{"x":${DeepJson.arrays()}}}],"page":1,"page_count":1,"page_size":100,"filtered_count":1,"item_count":1,"total_count":1}"""
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseStreamPage(body))
    }

    @Test
    fun `a recorded stream page still parses`() {
        val result = ManagementJson.parseStreamPage(FixtureFiles.text("m2-02/queues-page-one-stream.json"))
        assertTrue(result is CatalogResult.Success, "got $result")
    }

    @Test
    fun `a queue page nested ten thousand levels deep is Unexpected`() {
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueuePage(DeepJson.arrays(), "/"))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseQueuePage(DeepJson.objects(), "/"))
    }

    @Test
    fun `other shapes through guarded are Unexpected too`() {
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseConsumers(DeepJson.arrays(), "s"))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parsePublishers(DeepJson.arrays()))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseExchanges(DeepJson.arrays()))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseInspectedQueueMessages(DeepJson.arrays()))
    }

    @Test
    fun `MQTT connections nested ten thousand levels deep are Unexpected`() {
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseMqttClients(DeepJson.arrays(), null, "/"))
        assertEquals(CatalogResult.Unexpected, ManagementJson.parseMqttClients(DeepJson.objects(), "[]", "/"))
    }

    // Decision #4 of the brief: a too-deep bindings body means "no subscription data", like the existing catch of a bad bindings body.
    // The acceptance list says "gives Unexpected" for the deep bindings body too; the decision table wins here (see the question to the architect).
    @Test
    fun `MQTT bindings nested ten thousand levels deep mean no subscription data, the connections still list`() {
        val result = ManagementJson.parseMqttClients(connectionsOk, DeepJson.arrays(), "/")
        assertTrue(result is CatalogResult.Success, "got $result")
        val rows = (result as CatalogResult.Success).value
        assertEquals(listOf("dev-1"), rows.map { it.clientId })
        assertTrue(rows.single().subscriptions.isEmpty())
    }

    @Test
    fun `isNotManagementUser is false for a body nested ten thousand levels deep`() {
        assertFalse(ManagementErrors.isNotManagementUser(DeepJson.arrays()))
        assertFalse(ManagementErrors.isNotManagementUser(DeepJson.objects()))
        assertFalse(ManagementErrors.isNotManagementUser("""{"reason":"Not management user","x":${DeepJson.arrays()}}"""))
    }

    @Test
    fun `isNotManagementUser still recognises the real 401 body`() {
        assertTrue(ManagementErrors.isNotManagementUser("""{"error":"not_authorised","reason":"Not management user"}"""))
    }
}
