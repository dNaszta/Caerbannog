package dev.caerbannog.mcp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileRepository
import dev.caerbannog.core.connections.SaveConnectionResult
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.fakes.FakeShovelStatusReader
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.CompatibilityReportJson
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.LiveSourceTexts
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M8-10: the two read-only migration tools of the MCP server and the guards around them. Red against the stubs (the tools are not built, the server lists 11 tools). */
class McpMigrationToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sentinels = listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://", "amqps://", "src-uri", "dest-uri")

    private class Repo(vararg profiles: ConnectionProfile) : ConnectionProfileRepository {
        val list = profiles.toMutableList()
        override fun list(): List<ConnectionProfile> = list
        override fun add(profile: ConnectionProfile): SaveConnectionResult { list += profile; return SaveConnectionResult.Saved(profile) }
        override fun update(profile: ConnectionProfile): SaveConnectionResult = SaveConnectionResult.Saved(profile)
        override fun delete(name: String) { list.removeAll { it.name == name } }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    private val legacy = ConnectionProfile("legacy-eu", "legacy.example", "/", "svc")
    private val modern = ConnectionProfile("new-eu", "new.example", "/", "svc")

    private fun server(
        reads: FakeThreeXReads? = null,
        version: String = "3.13.7",
        files: Map<String, FileLoadResult> = emptyMap(),
        shovels: FakeShovelStatusReader? = null,
        providers: Boolean = true,
    ): McpServer = McpServer(
        profileRepository = Repo(legacy, modern), connectionChecker = Reachable,
        streamCatalogProvider = { _, _ -> FakeStreamCatalog() }, streamOffsetsProvider = { _, _ -> FakeStreamOffsets() }, topologyCatalogProvider = { _, _ -> FakeTopologyCatalog() },
        definitionsSourceProvider = if (providers) ({ p -> LiveDefinitionsSource(p.name, version, reads ?: FakeThreeXReads()) { } }) else null,
        definitionsFileLoader = if (providers) FakeDefinitionsFileLoader(files) else null,
        shovelStatusProvider = if (providers) ({ _, _ -> shovels ?: FakeShovelStatusReader() }) else null,
    )

    private class Called(val isError: Boolean, val text: String) {
        val obj: JsonObject get() = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw AssertionError("the tool answered text that is not a JSON object: ${text.take(120)}") }
    }

    private fun McpServer.call(tool: String, args: String): Called {
        val resp = handleRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""")
        val result = Json.parseToJsonElement(requireNotNull(resp)).jsonObject["result"]?.jsonObject
        val text = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        return Called(result?.get("isError")?.jsonPrimitive?.booleanOrNull() == true || result == null, text)
    }

    private fun JsonPrimitive.booleanOrNull(): Boolean? = if (content == "true") true else if (content == "false") false else null

    private fun toolNames(s: McpServer): List<String> =
        Json.parseToJsonElement(requireNotNull(s.handleRequest("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun fileServer(text: String, path: String = "/data/legacy.json") = server(files = mapOf(path to FileLoadResult.Loaded(text)))
    private fun analyseFile(s: McpServer, args: String = "{}", path: String = "/data/legacy.json") = s.call("caerbannog_analyze_3x_definitions", """{"file":"$path"${if (args == "{}") "" else "," + args.trim('{', '}')}}""")

    // ---- the tool list and the guards ----

    @Test
    fun `the tool list is the eleven existing tools and the two new ones, thirteen`() {
        val names = toolNames(server())
        assertEquals(13, names.size, "tools: $names")
        assertTrue("caerbannog_analyze_3x_definitions" in names && "caerbannog_list_shovels" in names, "tools: $names")
    }

    @Test
    fun `without the providers the two tools are not listed`() {
        assertEquals(11, toolNames(server(providers = false)).size)
    }

    @Test
    fun `no tool name says create, remove, delete, apply, forward, convert, accept or purge`() {
        val bad = listOf("create", "remove", "delete", "apply", "forward", "convert", "accept", "purge", "shovel_start")
        toolNames(server()).forEach { name -> assertTrue(bad.none { it in name.lowercase() }, "a write-like tool: $name") }
        assertEquals(13, toolNames(server()).size, "the premise: the new tools are listed")
    }

    @Test
    fun `the mcp server sources never mention the shovel gateway, the forwarding service or expose`() {
        val dir = File("src/main/kotlin/dev/caerbannog/mcp")
        assertTrue(dir.isDirectory, "cannot find ${dir.absolutePath}")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 2)
        for (f in files) {
            val text = f.readLines().joinToString("\n") { it.substringBefore("//") }
            listOf("ShovelGateway", "ForwardingService", "ManagementShovelGateway", ".expose(").forEach { assertFalse(text.contains(it), "${f.name} mentions $it") }
        }
    }

    // ---- caerbannog_analyze_3x_definitions: input ----

    @Test
    fun `exactly one of profile and file is needed, a relative path is refused, and the answers are fixed texts`() {
        val s = server(files = mapOf("/data/legacy.json" to FileLoadResult.Loaded(DefinitionsFiles.classicQueues(3))))
        val neither = s.call("caerbannog_analyze_3x_definitions", "{}")
        val both = s.call("caerbannog_analyze_3x_definitions", """{"profile":"legacy-eu","file":"/data/legacy.json"}""")
        val relative = s.call("caerbannog_analyze_3x_definitions", """{"file":"data/legacy.json"}""")
        assertTrue(neither.isError && both.isError && relative.isError, "refusals: ${neither.text} / ${both.text} / ${relative.text}")
        assertEquals("Give exactly one of \"profile\" and \"file\"", neither.text)
        assertEquals(neither.text, both.text)
        assertEquals("The file must be an absolute path", relative.text)
    }

    @Test
    fun `an unknown profile, a file that is not read, a too large file and a text that is not definitions are refused with fixed texts`() {
        val s = server(files = mapOf("/data/big.json" to FileLoadResult.TooLarge(70_000_000, MAX_BYTES), "/data/notes.json" to FileLoadResult.Loaded("""{"hello":"world"}""")))
        assertEquals("Profile 'nope' not found", s.call("caerbannog_analyze_3x_definitions", """{"profile":"nope"}""").text)
        assertEquals(MigrateFromThreeTexts.FILE_UNREADABLE, s.call("caerbannog_analyze_3x_definitions", """{"file":"/data/missing.json"}""").text)
        assertEquals(MigrateFromThreeTexts.FILE_TOO_LARGE, s.call("caerbannog_analyze_3x_definitions", """{"file":"/data/big.json"}""").text)
        val rejected = (DefinitionsParser.parse("""{"hello":"world"}""") as DefinitionsParseResult.Rejected).reason.message
        val notes = s.call("caerbannog_analyze_3x_definitions", """{"file":"/data/notes.json"}""")
        assertTrue(notes.isError)
        assertEquals(rejected, notes.text)
    }

    @Test
    fun `a per-virtual-host export is refused, the tool cannot ask for a virtual host name`() {
        val text = DefinitionsFixtures.read(DefinitionsFixtures.VHOST_V1)
        val out = analyseFile(fileServer(text))
        assertTrue(out.isError, out.text)
        assertTrue(out.text.contains("one virtual host"), out.text)
        assertFalse(out.text.contains("\"findings\""), "no report for a per-virtual-host export")
    }

    // ---- caerbannog_analyze_3x_definitions: the page ----

    private fun big(n: Int = 60_000) = DefinitionsFiles.classicQueues(n)

    @Test
    fun `a page has the summary, the limit findings from the offset and the total`() {
        val s = fileServer(big())
        val out = analyseFile(s, """{"limit":50,"offset":100}""")
        assertFalse(out.isError, out.text)
        val o = out.obj
        assertEquals("caerbannog.compat-report/1", o["schema"]?.jsonPrimitive?.content)
        assertNotNull(o["summary"]); assertNotNull(o["source"])
        val findings = o["findings"]!!.jsonArray
        assertEquals(50, findings.size)
        val page = o["page"]!!.jsonObject
        assertEquals(listOf(100, 50, 50), listOf("offset", "limit", "returned").map { page[it]!!.jsonPrimitive.int })
        val document = (DefinitionsParser.parse(big()) as DefinitionsParseResult.Parsed).document
        val report = CompatibilityAnalyzer.analyze(document)
        assertEquals(report.findings.size, page["total"]!!.jsonPrimitive.int)
        val full = Json.parseToJsonElement(CompatibilityReportJson.encode(report)).jsonObject["findings"]!!.jsonArray
        assertEquals(full.subList(100, 150).toList(), findings.toList(), "the page is the slice of the full report")
    }

    @Test
    fun `the default limit is 50, a limit above 200 is clamped to 200, an offset past the end gives no findings and the total`() {
        val s = fileServer(big(2_000))
        assertEquals(50, analyseFile(s).obj["findings"]!!.jsonArray.size)
        val clamped = analyseFile(s, """{"limit":5000}""").obj
        assertEquals(200, clamped["page"]!!.jsonObject["limit"]!!.jsonPrimitive.int)
        assertTrue(clamped["findings"]!!.jsonArray.size <= 200)
        val past = analyseFile(s, """{"offset":999999}""").obj
        assertEquals(0, past["findings"]!!.jsonArray.size)
        assertTrue(past["page"]!!.jsonObject["total"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun `the response stays under 256 KB and says when the page was cut`() {
        val out = analyseFile(fileServer(big()), """{"limit":200}""")
        assertTrue(out.text.length <= 256 * 1024, "the response is ${out.text.length} characters")
        val page = out.obj["page"]!!.jsonObject
        val returned = page["returned"]!!.jsonPrimitive.int
        assertEquals(out.obj["findings"]!!.jsonArray.size, returned)
        if (returned < 200) assertTrue(page["truncated"]?.jsonPrimitive?.boolean == true, "a cut page must say so")
    }

    @Test
    fun `no hash, no password and no shovel uri in the output of a file with users and a shovel parameter`() {
        val text = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)
        val out = analyseFile(fileServer(text), """{"limit":200}""")
        assertFalse(out.isError, out.text)
        sentinels.forEach { assertFalse(out.text.contains(it), "\"$it\" is in the output") }
    }

    // ---- caerbannog_analyze_3x_definitions: a live 3.x profile ----

    @Test
    fun `a 3x profile is read through its definitions source with read requests only and returns the report`() {
        val reads = FakeThreeXReads().apply {
            definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("jobs"))))
            storageVersionNeverAppears("/", "orders"); storageVersionNeverAppears("/", "jobs")
        }
        val out = server(reads = reads).call("caerbannog_analyze_3x_definitions", """{"profile":"legacy-eu"}""")
        assertFalse(out.isError, out.text)
        assertEquals("caerbannog.compat-report/1", out.obj["schema"]?.jsonPrimitive?.content)
        assertTrue(reads.calls.isNotEmpty() && reads.calls.all { it == "definitions" || it == "deprecated-features" || it.startsWith("storage-versions:") }, "calls: ${reads.calls}")
        sentinels.forEach { assertFalse(out.text.contains(it)) }
    }

    @Test
    fun `a 4x profile and an unreachable profile give the fixed texts of the live source`() {
        assertEquals(LiveSourceTexts.NOT_THREE_X, server(version = "4.1.0").call("caerbannog_analyze_3x_definitions", """{"profile":"new-eu"}""").text)
        val down = FakeThreeXReads().apply { definitions = ReadOutcome.Unreachable }
        val out = server(reads = down).call("caerbannog_analyze_3x_definitions", """{"profile":"legacy-eu"}""")
        assertTrue(out.isError)
        assertEquals(LiveSourceTexts.unreachable("legacy-eu"), out.text)
    }

    // ---- caerbannog_list_shovels ----

    private val ref = ShovelRef("/", "caerbannog-migrate-orders")

    @Test
    fun `shovels are listed with name, vhost, state and counts, a terminated reason is scrubbed, and no address is returned`() {
        val reader = FakeShovelStatusReader(
            ShovelListOutcome.Listed(
                listOf(
                    ShovelStatus(ref, ShovelRunState.RUNNING, null, 40, 80),
                    ShovelStatus(ShovelRef("/", "other"), ShovelRunState.TERMINATED, "failed to connect to amqp://mover:SENTINEL-PASSWORD@old:5672", null, null),
                ),
            ),
        )
        val out = server(shovels = reader).call("caerbannog_list_shovels", """{"profile":"new-eu"}""")
        assertFalse(out.isError, out.text)
        sentinels.forEach { assertFalse(out.text.contains(it), "\"$it\" is in the output: ${out.text}") }
        val list = out.obj["shovels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, list.size)
        val first = list.first()
        assertEquals(listOf("caerbannog-migrate-orders", "/", "running", 40, 80), listOf(first["name"]!!.jsonPrimitive.content, first["vhost"]!!.jsonPrimitive.content, first["state"]!!.jsonPrimitive.content, first["forwarded"]!!.jsonPrimitive.int, first["remaining"]!!.jsonPrimitive.int))
        val keys = list.flatMap { it.keys }.map { it.lowercase() }
        assertTrue(keys.none { "uri" in it || "url" in it || "address" in it || "host" in it }, "an address-like key: $keys")
        assertEquals("terminated", list[1]["state"]!!.jsonPrimitive.content)
        assertTrue(list[1]["reason"]!!.jsonPrimitive.content.contains("failed to connect"))
    }

    @Test
    fun `an unlimited shovel has no remaining count and no shovels is an empty list`() {
        val reader = FakeShovelStatusReader(ShovelListOutcome.Listed(listOf(ShovelStatus(ref, ShovelRunState.RUNNING, null, 7, null))))
        val one = server(shovels = reader).call("caerbannog_list_shovels", """{"profile":"new-eu"}""").obj["shovels"]!!.jsonArray.single().jsonObject
        assertTrue(one["remaining"] == null || one["remaining"] is JsonNull, "remaining: ${one["remaining"]}")
        assertEquals(0, server(shovels = FakeShovelStatusReader()).call("caerbannog_list_shovels", """{"profile":"new-eu"}""").obj["shovels"]!!.jsonArray.size)
    }

    @Test
    fun `not permitted, plugins off, unreachable and failed are fixed texts that echo nothing`() {
        fun text(o: ShovelListOutcome) = server(shovels = FakeShovelStatusReader(o)).call("caerbannog_list_shovels", """{"profile":"new-eu"}""")
        assertEquals("The user cannot read the shovel status and needs the monitoring tag", text(ShovelListOutcome.NotPermitted).text)
        val others = listOf(ShovelListOutcome.PluginsOff, ShovelListOutcome.Unreachable, ShovelListOutcome.Failed("raw broker text SENTINEL-PASSWORD amqp://u:p@h")).map { text(it) }
        assertTrue(others.all { it.isError && it.text.isNotBlank() }, others.map { it.text }.toString())
        assertEquals(3, others.map { it.text }.toSet().size, "each outcome has its own text")
        others.forEach { o -> sentinels.forEach { assertFalse(o.text.contains(it), o.text) } }
        assertTrue(others[0].text.contains("rabbitmq_shovel"), others[0].text)
    }

    @Test
    fun `an unknown profile is refused and the tool reads nothing`() {
        val reader = FakeShovelStatusReader()
        val out = server(shovels = reader).call("caerbannog_list_shovels", """{"profile":"nope"}""")
        assertEquals("Profile 'nope' not found", out.text)
        assertEquals(0, reader.listCalls)
        assertEquals(1, run { server(shovels = reader).call("caerbannog_list_shovels", """{"profile":"new-eu"}"""); reader.listCalls }, "a known profile reads once")
    }

    private companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
