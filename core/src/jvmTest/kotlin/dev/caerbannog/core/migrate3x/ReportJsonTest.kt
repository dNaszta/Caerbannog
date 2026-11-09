package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-03 JSON export `caerbannog.compat-report/1`: schema, fixed key order, golden, every finding complete. */
class ReportJsonTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the schema constant is fixed`() {
        assertEquals("caerbannog.compat-report/1", CompatibilityReportJson.SCHEMA)
    }

    @Test
    fun `the encoded small report equals the golden file`() {
        val actual = parse(CompatibilityReportJson.encode(smallReport()))
        val expected = parse(DefinitionsFixtures.readM803("report-expected.json"))
        assertEquals(expected, actual)
    }

    @Test
    fun `keys come in the documented fixed order`() {
        val actual = parse(CompatibilityReportJson.encode(smallReport()))
        assertEquals(listOf("schema", "source", "summary", "findings"), actual.keys.toList())
        assertEquals(listOf("name", "sourceVersion", "live", "singleVirtualHost"), actual["source"]!!.jsonObject.keys.toList())
        assertEquals(listOf("topologyItems", "compatibleItems", "needDecision", "blockers", "warnings", "infos", "messageLoss"), actual["summary"]!!.jsonObject.keys.toList())
        val first = (actual["findings"] as JsonArray).first().jsonObject
        assertEquals(
            listOf("id", "rule", "severity", "class", "kind", "vhost", "name", "about", "appliesWhen", "message", "why", "proposal", "alternatives", "defaultDecision", "verified", "evidence"),
            first.keys.toList(),
        )
    }

    @Test
    fun `the output is pretty-printed`() {
        val text = CompatibilityReportJson.encode(smallReport())
        assertTrue(text.lines().size > 20 && text.lines().any { it.startsWith("  ") }, "expected an indented multi-line document but got:\n$text")
    }

    @Test
    fun `the report of the full fixture encodes with the required fields on every finding`() {
        val report = analyze(fullFixture())
        val root = parse(CompatibilityReportJson.encode(report))
        assertEquals(CompatibilityReportJson.SCHEMA, (root["schema"] as JsonPrimitive).content)
        val findings = root["findings"] as JsonArray
        assertEquals(report.findings.size, findings.size)
        val allowed = setOf("blocker", "warning", "info")
        findings.forEach {
            val f = it.jsonObject
            for (key in listOf("severity", "rule", "kind", "name", "proposal", "id", "message", "why")) assertTrue(key in f, "finding without \"$key\": $f")
            assertTrue((f["severity"] as JsonPrimitive).content in allowed, "severity ${f["severity"]}")
            assertTrue("type" in f["proposal"]!!.jsonObject, "a proposal has a type: ${f["proposal"]}")
            assertTrue((f["rule"] as JsonPrimitive).content.isNotBlank())
        }
        assertEquals(report.summary.blockers, findings.count { (it.jsonObject["severity"] as JsonPrimitive).content == "blocker" })
        assertTrue(findings.any { !(it.jsonObject["verified"] as JsonPrimitive).boolean }, "unverified findings carry \"verified\": false")
    }

    @Test
    fun `the tick text is in the JSON of a conversion proposal`() {
        val text = CompatibilityReportJson.encode(analyzeQueues(D.classicQueue("q")))
        assertContainsText(text, "\"acknowledgement\": \"Ordering by priority may change\"", "the JSON")
    }

    @Test
    fun `every proposal type encodes with its lower camel case type name`() {
        val proposals = mapOf(
            "convertQueueType" to Proposal.ConvertQueueType(QueueKind.Quorum, null),
            "keepClassic" to Proposal.KeepClassic,
            "removeArgument" to Proposal.RemoveArgument("x-a"),
            "setArgument" to Proposal.SetArgument("x-queue-version", ArgumentValue.Whole(2)),
            "clearAutoDelete" to Proposal.ClearAutoDelete,
            "makeDurable" to Proposal.MakeDurable,
            "dropPolicyKeys" to Proposal.DropPolicyKeys(listOf("ha-mode")),
            "declareVirtualHost" to Proposal.DeclareVirtualHost("v"),
            "declareUser" to Proposal.DeclareUser("u", listOf("t"), "alg", true, Acknowledgement.PubliclyKnownPassword),
            "setPermissions" to Proposal.SetPermissions("v", "u"),
            "setTopicPermissions" to Proposal.SetTopicPermissions("v", "u", "e"),
            "manual" to Proposal.Manual("a", "b", false),
            "none" to Proposal.None,
        )
        val base = smallReport().findings.first()
        val report = smallReport().copy(findings = proposals.values.map { base.copy(proposal = it, alternatives = emptyList()) })
        val encoded = (parse(CompatibilityReportJson.encode(report))["findings"] as JsonArray).map { (it.jsonObject["proposal"]!!.jsonObject["type"] as JsonPrimitive).content }
        assertEquals(proposals.keys.toList(), encoded)
    }

    @Test
    fun `declareUser carries name, tags, the hash flag and the tick and never a hash`() {
        val base = smallReport().findings.first()
        val report = smallReport().copy(findings = listOf(base.copy(proposal = Proposal.DeclareUser("guest", listOf("administrator"), "rabbit_password_hashing_sha256", true, Acknowledgement.PubliclyKnownPassword))))
        val p = (parse(CompatibilityReportJson.encode(report))["findings"] as JsonArray).first().jsonObject["proposal"]!!.jsonObject
        assertEquals("guest", (p["name"] as JsonPrimitive).content)
        assertEquals(JsonArray(listOf(JsonPrimitive("administrator"))), p["tags"])
        assertTrue(p["hasPasswordHash"]!!.let { (it as JsonPrimitive).boolean })
        assertEquals("This user has a publicly known password", (p["acknowledgement"] as JsonPrimitive).content)
    }

    @Test
    fun `an empty report encodes with empty findings`() {
        val empty = CompatibilityReport(ReportSource(null, null, false, null), ReportSummary(0, 0, 0, 0, 0, 0, 0), emptyList())
        val root = parse(CompatibilityReportJson.encode(empty))
        assertEquals(JsonArray(emptyList()), root["findings"])
        assertEquals("caerbannog.compat-report/1", (root["schema"] as JsonPrimitive).content)
    }
}
