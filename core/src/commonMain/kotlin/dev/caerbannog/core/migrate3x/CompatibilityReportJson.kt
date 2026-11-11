package dev.caerbannog.core.migrate3x

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The export `caerbannog.compat-report/1`: fixed key order, pretty-printed, enum values in lower camel case. The report types hold
 * no secret (no password hash, no URI, no parameter value), so nothing here can leak one.
 */
object CompatibilityReportJson {
    const val SCHEMA: String = "caerbannog.compat-report/1"

    private val pretty = Json { prettyPrint = true }

    fun encode(report: CompatibilityReport): String {
        val root = buildJsonObject {
            put("schema", SCHEMA)
            put("source", sourceJson(report.source))
            put("summary", summaryJson(report.summary))
            put("findings", JsonArray(report.findings.map(::findingJson)))
        }
        return pretty.encodeToString(JsonElement.serializer(), root)
    }

    /**
     * M8-10 STUB: one page of findings for the MCP tool: the same header and finding JSON as [encode] plus `"page": {"offset", "limit", "returned", "total"}`.
     * The stub returns the whole report without a page.
     */
    fun encodePage(report: CompatibilityReport, limit: Int, offset: Int): String = encode(report) // M8-10 STUB

    private fun sourceJson(source: ReportSource): JsonObject = buildJsonObject {
        put("name", source.name)
        put("sourceVersion", source.sourceVersion)
        put("live", source.live)
        put("singleVirtualHost", source.singleVirtualHost)
    }

    private fun summaryJson(summary: ReportSummary): JsonObject = buildJsonObject {
        put("topologyItems", summary.topologyItems)
        put("compatibleItems", summary.compatibleItems)
        put("needDecision", summary.needDecision)
        put("blockers", summary.blockers)
        put("warnings", summary.warnings)
        put("infos", summary.infos)
        put("messageLoss", summary.messageLoss)
    }

    private fun findingJson(finding: Finding): JsonObject = buildJsonObject {
        put("id", finding.id)
        put("rule", finding.rule)
        put("severity", finding.severity.json())
        put("class", finding.findingClass.json())
        put("kind", finding.kind.json())
        put("vhost", finding.vhost)
        put("name", finding.name)
        put("about", strings(finding.about))
        put("appliesWhen", finding.appliesWhen.json())
        put("message", finding.message)
        put("why", finding.why)
        put("proposal", ProposalJson.encode(finding.proposal))
        put("alternatives", JsonArray(finding.alternatives.map(ProposalJson::encode)))
        put("defaultDecision", finding.defaultDecision.json())
        put("verified", finding.verified)
        put("evidence", finding.evidence.json())
    }

    private fun Enum<*>.json(): String = name.replaceFirstChar { it.lowercase() }
}

internal fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })