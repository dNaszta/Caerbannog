package dev.caerbannog.mcp

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.forwarding.ForwardingTexts
import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelScrubber
import dev.caerbannog.core.forwarding.ShovelStatusReader
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.CompatibilityReport
import dev.caerbannog.core.migrate3x.CompatibilityReportJson
import dev.caerbannog.core.migrate3x.DefinitionsFileLoader
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.DefinitionsSource
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.analyseLive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * The two read-only migration tools (M8-10): the 3.x compatibility report, one page at a time, and the status of the shovels of a 4.x connection.
 * Nothing here creates, removes, converts, forwards or applies anything; every text is fixed (a name from the agent's own input or from the file
 * is the only thing echoed), and the free text of a shovel is scrubbed here whatever the reader returned.
 */
internal class McpMigrationTools(
    private val profiles: () -> List<ConnectionProfile>,
    private val definitionsSourceProvider: ((ConnectionProfile) -> DefinitionsSource)?,
    private val definitionsFileLoader: DefinitionsFileLoader?,
    private val shovelStatusProvider: ((ConnectionProfile, String) -> ShovelStatusReader)?,
) {
    val tools: List<McpTool> = listOf(
        McpTool(
            name = ANALYZE,
            description = "Read the RabbitMQ 3.x compatibility report of a 3.x connection or of an absolute path to a full definitions export (.json), one page of findings at a time. Read only; no password, hash or address is ever returned.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("profile") { put("type", "string"); put("description", "A saved 3.x connection profile (read with GET requests only)") }
                    putJsonObject("file") { put("type", "string"); put("description", "Absolute path to a definitions export .json (instead of profile)") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Findings per page (default $DEFAULT_LIMIT, at most $MAX_LIMIT)") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "Findings to skip (default 0)") }
                }
            },
        ),
        McpTool(
            name = LIST_SHOVELS,
            description = "List the shovels of a 4.x connection with state and counts. Read only; never an address or credential.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("profile") { put("type", "string"); put("description", "A saved 4.x connection profile") } }
                putJsonArray("required") { add(JsonPrimitive("profile")) }
            },
        ),
    )

    suspend fun analyze(args: JsonObject): McpCallToolResult {
        val sourceProvider = definitionsSourceProvider ?: return error(NOT_AVAILABLE)
        val profileName = args.text("profile")
        val path = args.text("file")
        if ((profileName == null) == (path == null)) return error(EXACTLY_ONE)
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val report = if (profileName != null) liveReport(profileName, sourceProvider) else fileReport(path!!)
        return when (report) {
            is Reported.Failure -> error(report.text)
            is Reported.Report -> McpCallToolResult(listOf(McpContent(text = page(report.report, limit, offset))))
        }
    }

    private sealed interface Reported {
        data class Report(val report: CompatibilityReport) : Reported
        data class Failure(val text: String) : Reported
    }

    private suspend fun liveReport(name: String, sourceProvider: (ConnectionProfile) -> DefinitionsSource): Reported {
        val profile = profiles().firstOrNull { it.name == name } ?: return Reported.Failure("Profile '$name' not found")
        val outcome = try {
            analyseLive(sourceProvider(profile))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The connection error may carry broker text: the agent gets the fixed text.
            return Reported.Failure(LiveSourceTexts.unreachable(name))
        }
        return when (outcome) {
            is LiveAnalysisOutcome.Analysed -> Reported.Report(outcome.held.report)
            is LiveAnalysisOutcome.OfferFileImport -> Reported.Failure(outcome.text)
            is LiveAnalysisOutcome.Unreachable -> Reported.Failure(LiveSourceTexts.unreachable(outcome.connectionName))
            is LiveAnalysisOutcome.Failed -> Reported.Failure(outcome.text)
        }
    }

    private suspend fun fileReport(path: String): Reported {
        val loader = definitionsFileLoader ?: return Reported.Failure(NOT_AVAILABLE)
        if (!File(path).isAbsolute) return Reported.Failure(NOT_ABSOLUTE)
        if (!path.endsWith(".json", ignoreCase = true)) return Reported.Failure(NOT_JSON)
        val text = when (val loaded = loader.load(path)) {
            is FileLoadResult.Loaded -> loaded.text
            is FileLoadResult.TooLarge -> return Reported.Failure(MigrateFromThreeTexts.FILE_TOO_LARGE)
            is FileLoadResult.Unreadable -> return Reported.Failure(MigrateFromThreeTexts.FILE_UNREADABLE)
        }
        return when (val parsed = DefinitionsParser.parse(text)) {
            is DefinitionsParseResult.Parsed -> Reported.Report(CompatibilityAnalyzer.analyze(parsed.document))
            is DefinitionsParseResult.NeedsVirtualHost -> Reported.Failure(MigrateFromThreeTexts.NEEDS_VHOST_NAME)
            is DefinitionsParseResult.Rejected -> Reported.Failure(parsed.reason.message)
        }
    }

    /** One page, cut (and marked `truncated`) when it would pass [MAX_RESPONSE_CHARS]. */
    private fun page(report: CompatibilityReport, limit: Int, offset: Int): String {
        val whole = CompatibilityReportJson.encodePage(report, limit, offset)
        if (whole.length <= MAX_RESPONSE_CHARS) return whole
        var keep = limit
        var cut = whole
        while (cut.length > MAX_RESPONSE_CHARS && keep > 0) {
            keep = keep * CUT_NUMERATOR / CUT_DENOMINATOR
            cut = CompatibilityReportJson.encodePageCut(report, limit, offset, keep)
        }
        return cut
    }

    suspend fun listShovels(args: JsonObject): McpCallToolResult {
        val provider = shovelStatusProvider ?: return error(NOT_AVAILABLE)
        val name = args.text("profile") ?: return error("Missing 'profile' argument")
        val profile = profiles().firstOrNull { it.name == name } ?: return error("Profile '$name' not found")
        val outcome = try {
            provider(profile, profile.virtualHost).list()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ShovelListOutcome.Unreachable
        }
        return when (outcome) {
            is ShovelListOutcome.Listed -> McpCallToolResult(listOf(McpContent(text = Json.encodeToString(JsonObject.serializer(), shovelsJson(outcome)))))
            ShovelListOutcome.NotPermitted -> error(SHOVELS_NOT_PERMITTED)
            ShovelListOutcome.PluginsOff -> error(SHOVELS_PLUGINS_OFF)
            ShovelListOutcome.Unreachable -> error(ForwardingTexts.BROKER_UNREACHABLE)
            is ShovelListOutcome.Failed -> error(SHOVELS_FAILED)
        }
    }

    private fun shovelsJson(listed: ShovelListOutcome.Listed): JsonObject = buildJsonObject {
        put("shovels", JsonArray(listed.shovels.map { s ->
            buildJsonObject {
                put("name", s.ref.name)
                put("vhost", s.ref.vhost)
                put("state", s.state.name.lowercase())
                put("forwarded", s.forwarded)
                put("remaining", s.remaining)
                // Free text from the broker: scrubbed here, whatever the reader did.
                s.reason?.let { put("reason", ShovelScrubber.scrub(it)) }
            }
        }))
    }

    private fun error(text: String) = McpCallToolResult(listOf(McpContent(text = text)), isError = true)
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    companion object {
        const val ANALYZE = "caerbannog_analyze_3x_definitions"
        const val LIST_SHOVELS = "caerbannog_list_shovels"
        const val NOT_AVAILABLE = "This tool is not available in this server"
        private const val EXACTLY_ONE = "Give exactly one of \"profile\" and \"file\""
        private const val NOT_ABSOLUTE = "The file must be an absolute path"
        private const val NOT_JSON = "The file must be a .json file"
        private const val SHOVELS_NOT_PERMITTED = "The user cannot read the shovel status and needs the monitoring tag"
        private const val SHOVELS_PLUGINS_OFF = "The shovel plugins are not enabled on this broker: enable rabbitmq_shovel and rabbitmq_shovel_management"
        private const val SHOVELS_FAILED = "The broker did not list the shovels"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 200
        private const val MAX_RESPONSE_CHARS = 256 * 1024
        private const val CUT_NUMERATOR = 3
        private const val CUT_DENOMINATOR = 4
    }
}
