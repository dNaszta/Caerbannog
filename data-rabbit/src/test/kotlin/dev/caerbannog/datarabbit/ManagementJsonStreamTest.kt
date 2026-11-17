package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.Retention
import dev.caerbannog.core.streams.StreamPage
import dev.caerbannog.core.streams.StreamSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-08 slice 1 (docs/tasks/M9-08-policy-step.md), written first: BOTH parse sites of a stream (`ManagementJson.parseStreamDetail` and the list parser
 * `parseStreamPage`) read the retention from `effective_policy_definition` as well as from `arguments`, through the one core rule `RetentionSources.merge`.
 * Fixtures captured from RabbitMQ 4.4.0 by `RealBrokerM9PolicyStepTest` (the bytes are the broker's own): the detail GET and the list row (with the column
 * `effective_policy_definition` added to the column list, which is what the live test measured: the row lacks the field WITHOUT it).
 * The precedence when both are present is `RetentionSourcesTest`'s; here only that both parse sites apply it.
 */
class ManagementJsonStreamTest {

    private fun detail(body: String): StreamSummary =
        (ManagementJson.parseStreamDetail(body) as? CatalogResult.Success)?.value ?: error("detail did not parse: $body")

    private fun listed(row: String): StreamSummary = page(row).rows.single()

    private fun page(vararg rows: String): StreamPage {
        val body = """{"items":[${rows.joinToString(",")}],"page":1,"page_count":1,"page_size":100,"filtered_count":${rows.size},"item_count":${rows.size},"total_count":${rows.size}}"""
        return (ManagementJson.parseStreamPage(body) as? CatalogResult.Success)?.value ?: error("page did not parse: $body")
    }

    private fun row(arguments: String = """{"x-queue-type":"stream"}""", policyDefinition: String? = null): String =
        """{"name":"telemetry","vhost":"/","type":"stream","messages":0,"consumers":0,"segments":1,"first_timestamp":0,"leader":"rabbit@n","node":"rabbit@n","members":["rabbit@n"],"online":["rabbit@n"],"arguments":$arguments""" +
            (policyDefinition?.let { ""","effective_policy_definition":$it""" } ?: "") + "}"

    private val sevenDaysAndOneMillion = Retention(maxAge = "7D", maxLengthBytes = 1_000_000)

    // ---- the fixtures the live test captured ----

    @Test
    fun `the detail fixture with a policy gives the retention of the policy`() {
        val summary = detail(FixtureFiles.text("m9-00/s-f1d-stream-detail-with-policy.json"))
        assertEquals(sevenDaysAndOneMillion, summary.retention, "the stream's own arguments hold no retention, the policy sets max-age 7D and max-length-bytes 1000000")
    }

    @Test
    fun `the list row fixture with a policy gives the retention of the policy`() {
        val summary = listed(FixtureFiles.text("m9-00/s-f1d-stream-list-row-with-policy.json"))
        assertEquals(sevenDaysAndOneMillion, summary.retention)
    }

    // ---- the four cases of the brief, at both parse sites ----

    @Test
    fun `arguments only`() {
        val r = row("""{"x-queue-type":"stream","x-max-age":"1D","x-max-length-bytes":500,"x-stream-max-segment-size-bytes":100}""")
        assertEquals(Retention("1D", 500, 100), detail(r).retention)
        assertEquals(Retention("1D", 500, 100), listed(r).retention)
    }

    @Test
    fun `policy only`() {
        val r = row(policyDefinition = """{"max-age":"7D","max-length-bytes":1000000,"stream-max-segment-size-bytes":50000000}""")
        assertEquals(Retention("7D", 1_000_000, 50_000_000), detail(r).retention)
        assertEquals(Retention("7D", 1_000_000, 50_000_000), listed(r).retention)
    }

    @Test
    fun `neither`() {
        assertEquals(Retention(), detail(row()).retention)
        assertEquals(Retention(), listed(row()).retention)
    }

    @Test
    fun `a row without the field behaves exactly as before`() {
        val r = row("""{"x-queue-type":"stream","x-max-age":"3h"}""")
        assertEquals(Retention(maxAge = "3h"), detail(r).retention)
        assertEquals(Retention(maxAge = "3h"), listed(r).retention)
    }

    @Test
    fun `an effective_policy_definition that is JSON null (no policy) is no policy`() {
        val r = row("""{"x-queue-type":"stream","x-max-length-bytes":5}""", policyDefinition = "null")
        assertEquals(Retention(maxLengthBytes = 5), detail(r).retention)
        assertEquals(Retention(maxLengthBytes = 5), listed(r).retention)
    }

    @Test
    fun `a policy key that is not a retention key is ignored`() {
        val r = row(policyDefinition = """{"queue-leader-locator":"balanced","max-length":10,"alternate-exchange":"ae"}""")
        assertEquals(Retention(), detail(r).retention)
        assertEquals(Retention(), listed(r).retention)
    }

    // ---- precedence is applied at both sites (the rule itself: RetentionSourcesTest) ----

    @Test
    fun `when both hold max-length-bytes the policy wins at both sites (measured, S-14c-4)`() {
        val r = row("""{"x-queue-type":"stream","x-max-length-bytes":500}""", policyDefinition = """{"max-length-bytes":1000000}""")
        assertEquals(1_000_000L, detail(r).retention.maxLengthBytes)
        assertEquals(1_000_000L, listed(r).retention.maxLengthBytes)
    }

    @Test
    fun `when both hold max-age the argument stays at both sites (not measured)`() {
        val r = row("""{"x-queue-type":"stream","x-max-age":"1D"}""", policyDefinition = """{"max-age":"7D"}""")
        assertEquals("1D", detail(r).retention.maxAge)
        assertEquals("1D", listed(r).retention.maxAge)
    }

    // ---- hostile or odd answers: never a failed page, never a guess ----

    @Test
    fun `a wrong-typed policy value is treated as absent and the page still parses`() {
        val r = row(policyDefinition = """{"max-age":7,"max-length-bytes":"big","stream-max-segment-size-bytes":1.5}""")
        assertEquals(Retention(), detail(r).retention)
        assertEquals(Retention(), listed(r).retention)
    }

    @Test
    fun `an effective_policy_definition that is not an object is treated as absent`() {
        for (odd in listOf("\"text\"", "[1,2]", "5", "true")) {
            val r = row("""{"x-queue-type":"stream","x-max-age":"2D"}""", policyDefinition = odd)
            assertEquals(Retention(maxAge = "2D"), detail(r).retention, "detail with $odd")
            assertEquals(Retention(maxAge = "2D"), listed(r).retention, "list with $odd")
        }
    }

    @Test
    fun `the stream list asks for the policy definition column, else the broker leaves it out of the row (measured)`() {
        val url = ManagementRequests("http://broker.test:15672", BrokerTransport.Plain)
            .listStreams(dev.caerbannog.core.streams.StreamListRequest(vhost = "/"))
        assertTrue("effective_policy_definition" in java.net.URLDecoder.decode(url, Charsets.UTF_8), "the columns of the stream list request are: $url")
    }
}
