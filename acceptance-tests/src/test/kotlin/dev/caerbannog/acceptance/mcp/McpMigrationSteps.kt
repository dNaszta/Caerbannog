package dev.caerbannog.acceptance.mcp

import dev.caerbannog.acceptance.connections.FakeBrokerReachabilityChecker
import dev.caerbannog.acceptance.connections.InMemoryConnectionProfileRepository
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.forwarding.ShovelListOutcome
import dev.caerbannog.core.forwarding.ShovelRef
import dev.caerbannog.core.forwarding.ShovelRunState
import dev.caerbannog.core.forwarding.ShovelStatus
import dev.caerbannog.core.forwarding.fakes.FakeShovelStatusReader
import dev.caerbannog.core.migrate3x.FileLoadResult
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import dev.caerbannog.mcp.McpServer
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-10: the `@m8-10` scenarios of feature 16 through the real MCP server over fakes. Steps only call the tool and read what it returned. */
class McpMigrationSteps {
    private val repo = InMemoryConnectionProfileRepository().also {
        it.add(ConnectionProfile("legacy-eu", "legacy.example", "/", "svc"))
        it.add(ConnectionProfile("new-eu", "new.example", "/", "svc"))
    }
    private val reads = FakeThreeXReads().apply {
        definitions = ReadOutcome.Ok(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders"))))
        storageVersionNeverAppears("/", "orders")
    }
    private val files = mutableMapOf<String, FileLoadResult>()
    private val shovels = FakeShovelStatusReader()
    private val sentinels = listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://", "amqps://")
    private var text = ""
    private var isError = false
    private var toolNames: List<String> = emptyList()
    private var filePath = "/data/legacy.json"

    private fun server() = McpServer(
        profileRepository = repo, connectionChecker = FakeBrokerReachabilityChecker(),
        streamCatalogProvider = { _, _ -> FakeStreamCatalog() }, streamOffsetsProvider = { _, _ -> FakeStreamOffsets() }, topologyCatalogProvider = { _, _ -> FakeTopologyCatalog() },
        definitionsSourceProvider = { p -> LiveDefinitionsSource(p.name, "3.13.7", reads) { } },
        definitionsFileLoader = FakeDefinitionsFileLoader(files),
        shovelStatusProvider = { _, _ -> shovels },
    )

    private fun call(tool: String, args: String) {
        val resp = requireNotNull(server().handleRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}"""))
        val result = Json.parseToJsonElement(resp).jsonObject["result"]?.jsonObject
        text = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
        isError = result == null || result["isError"]?.jsonPrimitive?.content == "true"
    }

    @When("an agent calls {string} for {string}")
    fun callForProfile(tool: String, profile: String) = call(tool, """{"profile":"$profile"}""")

    @Then("it returns the compatibility report")
    fun report() {
        assertFalse(isError, text)
        assertEquals("caerbannog.compat-report/1", Json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.content)
    }

    @Then("only read requests were sent to {string}")
    fun readOnly(profile: String) {
        assertTrue(reads.calls.isNotEmpty(), "nothing was read")
        assertTrue(reads.calls.all { it == "definitions" || it == "deprecated-features" || it.startsWith("storage-versions:") }, "calls: ${reads.calls}")
    }

    @Then("the output contains no password, no password hash and no shovel URI")
    fun noSecrets() = sentinels.forEach { assertFalse(text.contains(it), "\"$it\" is in the output") }

    @Given("a definitions file with a user {string} that has a password hash")
    fun fileWithUser(user: String) {
        val t = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)
        assertTrue(t.contains("SENTINEL-HASH-$user"), "the fixture must hold the sentinel hash of $user")
        files[filePath] = FileLoadResult.Loaded(t)
    }

    @When("an agent calls {string} for that file")
    fun callForFile(tool: String) = call(tool, """{"file":"$filePath","limit":200}""")

    @Then("the output does not contain the hash")
    fun noHash() {
        assertFalse(isError, "the tool did not return a report: $text")
        assertFalse(text.contains("SENTINEL-HASH"), "the hash is in the output")
    }

    @Then("no other tool returns a hash for a generated or imported migration")
    fun noMigrationTool() {
        listTools()
        assertTrue(toolNames.isNotEmpty() && toolNames.none { "migration" in it }, "tools: $toolNames")
    }

    @When("an agent lists the available tools")
    fun listTools() {
        val resp = requireNotNull(server().handleRequest("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))
        toolNames = Json.parseToJsonElement(resp).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    @Then("there is no tool that creates or removes a shovel")
    fun noShovelWrite() {
        assertEquals(13, toolNames.size, "tools: $toolNames")
        assertTrue(toolNames.none { n -> listOf("create", "remove", "delete", "start").any { it in n } && "shovel" in n }, toolNames.toString())
    }

    @Then("there is no tool that converts or forwards on its own")
    fun noConvertForward() = assertTrue(toolNames.none { n -> listOf("convert", "forward", "apply", "accept").any { it in n } }, toolNames.toString())

    @Given("a definitions file with 60,000 classic queues")
    fun bigFile() { files[filePath] = FileLoadResult.Loaded(DefinitionsFiles.classicQueues(60_000)) }

    @When("an agent calls {string} for that file with limit {int} and offset {int}")
    fun callPaged(tool: String, limit: Int, offset: Int) = call(tool, """{"file":"$filePath","limit":$limit,"offset":$offset}""")

    @Then("it returns the summary and {int} findings starting at the finding {int}")
    fun page(n: Int, start: Int) {
        assertFalse(isError, text)
        val o = Json.parseToJsonElement(text).jsonObject
        assertTrue(o.containsKey("summary"))
        assertEquals(n, o["findings"]!!.jsonArray.size)
        assertEquals(start, o["page"]!!.jsonObject["offset"]!!.jsonPrimitive.int)
    }

    @Then("it says how many findings there are in all")
    fun total() = assertTrue(Json.parseToJsonElement(text).jsonObject["page"]!!.jsonObject["total"]!!.jsonPrimitive.int > 150)

    @Then("the response stays under the size cap")
    fun cap() = assertTrue(text.length in 1..(256 * 1024), "the response is ${text.length} characters")

    @Given("a shovel {string} is running on {string}")
    fun running(name: String, connection: String) {
        shovels.outcome = ShovelListOutcome.Listed(listOf(ShovelStatus(ShovelRef("/", name), ShovelRunState.RUNNING, null, 40, 80)))
    }

    @Then("it returns the name, state and message counts")
    fun statusRow() {
        assertFalse(isError, text)
        val row = Json.parseToJsonElement(text).jsonObject["shovels"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("caerbannog-migrate-orders", "running", 40, 80), listOf(row["name"]!!.jsonPrimitive.content, row["state"]!!.jsonPrimitive.content, row["forwarded"]!!.jsonPrimitive.int, row["remaining"]!!.jsonPrimitive.int))
    }

    @Then("it returns no URI")
    fun noUri() = assertFalse(text.contains("uri", ignoreCase = true) || text.contains("amqp"), text)
}
