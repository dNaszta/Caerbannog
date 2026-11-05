package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.SuperStreamLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * M2-07 step 2 (no Docker): `ManagementJson.parseSuperStreams(vhost, exchangesBody, bindingsBody)` against the bodies recorded for spike S-A
 * and S-B (`fixtures/m2-07/`). Contract (test developer, flagged in the report; the layout is 2 calls per tick, see the brief Status S-A):
 *  - a super stream is an exchange of type `direct` whose `arguments.x-super-stream` is the JSON boolean `true` (false, absent, the string
 *    "true", a number, null and a fanout exchange with the marker are NOT);
 *  - its partitions are the `destination`s of the bindings whose `source` is the exchange and `destination_type` is `queue` (an exchange
 *    destination is ignored), ordered by the numeric `arguments.x-stream-partition-order`; a binding without a numeric order comes after the
 *    numbered ones, ordered by `routing_key`, then `destination`; a queue bound twice appears once (its first place);
 *  - the result is a Success even when there is no super stream (empty list); a body that is not what the spec records is `Unexpected`.
 * The order of the layouts in the result is not specified; the tests look them up by name.
 */
class ManagementJsonSuperStreamTest {

    private fun layouts(exchanges: String, bindings: String, vhost: String = "/"): Map<String, SuperStreamLayout> {
        val result = ManagementJson.parseSuperStreams(vhost, FixtureFiles.text(exchanges), FixtureFiles.text(bindings))
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<List<SuperStreamLayout>>).value.associateBy { it.name }
    }

    private fun parse(exchanges: String, bindings: String = "[]") = ManagementJson.parseSuperStreams("/", exchanges, bindings)

    private fun names(exchanges: String, bindings: String = "[]"): Set<String> {
        val result = parse(exchanges, bindings)
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<List<SuperStreamLayout>>).value.map { it.name }.toSet()
    }

    private fun exchange(name: String, type: String = "direct", arguments: String) =
        """{"name":"$name","vhost":"/","type":"$type","durable":true,"auto_delete":false,"internal":false,"arguments":$arguments}"""

    // --- recorded on the dev broker (S-A) ---

    @Test
    fun `the dev broker's vhost gives the super stream orders with its three partitions in order`() {
        val found = layouts("m2-07/exchanges-vhost-root.json", "m2-07/bindings-vhost-root.json")

        assertEquals(setOf("orders"), found.keys, "the default and amq exchanges and the plain queues' bindings are no super streams")
        assertEquals(SuperStreamLayout("orders", "/", listOf("orders-0", "orders-1", "orders-2")), found.getValue("orders"))
    }

    @Test
    fun `partitions follow x-stream-partition-order, not the order of the bindings list, and custom routing keys do not matter`() {
        val found = layouts("m2-07/exchanges-vhost-regions.json", "m2-07/bindings-vhost-regions.json")

        // The broker lists these bindings alphabetically by destination: apac, eu, us. The order argument is us 0, eu 1, apac 2.
        assertEquals(listOf("regions-us", "regions-eu", "regions-apac"), found.getValue("regions").partitions)
        assertEquals(listOf("orders-0", "orders-1", "orders-2"), found.getValue("orders").partitions)
        assertEquals(setOf("orders", "regions"), found.keys)
    }

    @Test
    fun `the vhost of the layout is the vhost asked for`() {
        val found = layouts("m2-07/exchanges-vhost-root.json", "m2-07/bindings-vhost-root.json", vhost = "my vhost/x")

        assertEquals("my vhost/x", found.getValue("orders").vhost)
    }

    // --- hand-made shapes (S-B) ---

    @Test
    fun `a hand-made super stream orders numbered bindings first, then the others by routing key, an exchange destination is ignored and a queue bound twice appears once`() {
        val found = layouts("m2-07/exchanges-vhost-hand-made.json", "m2-07/bindings-vhost-hand-made.json", vhost = "m207")

        // hand: hand-0 (order 1, key a), hand-1 (no order, key b), hand-shared (order 0 key c, and order 5 key c2), only-q (order "abc", key o),
        // and an exchange binding to hand2 (order 9, ignored).
        assertEquals(listOf("hand-shared", "hand-0", "hand-1", "only-q"), found.getValue("hand").partitions)
        assertEquals(listOf("hand-shared"), found.getValue("hand2").partitions, "a queue shared by two super streams is listed under both here; the core grouping decides")
    }

    @Test
    fun `an exchange that is not a direct exchange with the marker true is no super stream`() {
        val found = layouts("m2-07/exchanges-vhost-hand-made.json", "m2-07/bindings-vhost-hand-made.json", vhost = "m207")

        assertEquals(setOf("hand", "hand2"), found.keys, "notsuper (false), strsuper (\"true\") and fansuper (fanout) are left out")
    }

    // --- the marker, inline ---

    @Test
    fun `the marker must be the JSON boolean true on a direct exchange`() {
        val exchanges = listOf(
            exchange("yes", arguments = """{"x-super-stream":true}"""),
            exchange("no-false", arguments = """{"x-super-stream":false}"""),
            exchange("no-absent", arguments = "{}"),
            exchange("no-string", arguments = """{"x-super-stream":"true"}"""),
            exchange("no-number", arguments = """{"x-super-stream":1}"""),
            exchange("no-null", arguments = """{"x-super-stream":null}"""),
            exchange("no-fanout", type = "fanout", arguments = """{"x-super-stream":true}"""),
            exchange("no-topic", type = "topic", arguments = """{"x-super-stream":true}"""),
        ).joinToString(",", "[", "]")

        assertEquals(setOf("yes"), names(exchanges))
    }

    @Test
    fun `an exchange without arguments is no super stream and not an error`() {
        assertEquals(emptySet<String>(), names("""[{"name":"x","vhost":"/","type":"direct"}]"""))
    }

    @Test
    fun `a name that looks like a partition name is never a super stream by name alone`() {
        // "orders-0" style names without the marker: nothing is inferred.
        assertEquals(emptySet<String>(), names("[" + exchange("orders", arguments = "{}") + "]", """[{"source":"orders","destination":"orders-0","destination_type":"queue","routing_key":"0","arguments":{}}]"""))
    }

    // --- bindings, inline ---

    private fun binding(source: String, destination: String, key: String, order: String?, type: String = "queue") =
        """{"source":"$source","vhost":"/","destination":"$destination","destination_type":"$type","routing_key":"$key","arguments":${order?.let { """{"x-stream-partition-order":$it}""" } ?: "{}"}}"""

    private fun partitions(vararg bindings: String): List<String> {
        val exchanges = "[" + exchange("s", arguments = """{"x-super-stream":true}""") + "]"
        val result = parse(exchanges, bindings.joinToString(",", "[", "]"))
        assertInstanceOf(CatalogResult.Success::class.java, result, "expected a value, got $result")
        @Suppress("UNCHECKED_CAST")
        return (result as CatalogResult.Success<List<SuperStreamLayout>>).value.single().partitions
    }

    @Test
    fun `order numbers sort numerically, not as text`() {
        assertEquals(
            listOf("p-2", "p-10", "p-11"),
            partitions(binding("s", "p-11", "11", "11"), binding("s", "p-2", "2", "2"), binding("s", "p-10", "10", "10")),
        )
    }

    @Test
    fun `without any order argument the routing key decides, then the destination name`() {
        assertEquals(
            listOf("b-queue", "a-queue", "c-queue"),
            partitions(binding("s", "c-queue", "k2", null), binding("s", "a-queue", "k1", null), binding("s", "b-queue", "k0", null)),
            "by routing key",
        )
        assertEquals(
            listOf("a-queue", "b-queue"),
            partitions(binding("s", "b-queue", "same", null), binding("s", "a-queue", "same", null)),
            "same routing key: by destination name",
        )
    }

    @Test
    fun `a binding of another exchange or to an exchange destination is not a partition`() {
        assertEquals(
            listOf("mine"),
            partitions(binding("s", "mine", "0", "0"), binding("other", "theirs", "0", "0"), binding("s", "an-exchange", "1", "1", type = "exchange")),
        )
    }

    @Test
    fun `a super stream with no bindings is a layout with no partitions`() {
        assertEquals(emptyList<String>(), partitions())
    }

    @Test
    fun `a fractional or negative order is treated like any number or like none, never a crash`() {
        val result = ManagementJson.parseSuperStreams(
            "/", "[" + exchange("s", arguments = """{"x-super-stream":true}""") + "]",
            "[" + listOf(binding("s", "a", "a", "1.5"), binding("s", "b", "b", "-1"), binding("s", "c", "c", """null""")).joinToString(",") + "]",
        )
        assertInstanceOf(CatalogResult.Success::class.java, result, "got $result")
    }

    // --- bodies that are not what the spec records ---

    @Test
    fun `a body that is not JSON, not an array, or an item that is not an object is Unexpected, never an exception`() {
        for (bad in listOf("", "not json", "{}", "\"x\"", "null", """["x"]""", """[{"name":5,"type":"direct"}]""")) {
            assertEquals(CatalogResult.Unexpected, parse(bad), "exchanges body <$bad>")
        }
        val ok = "[" + exchange("s", arguments = """{"x-super-stream":true}""") + "]"
        for (bad in listOf("", "not json", "{}", """["x"]""", """[{"source":"s"}]""")) {
            assertEquals(CatalogResult.Unexpected, parse(ok, bad), "bindings body <$bad>")
        }
    }

    @Test
    fun `an error body of the broker is Unexpected`() {
        assertEquals(CatalogResult.Unexpected, parse("""{"error":"not_authorised","reason":"Not management user"}"""))
    }
}
