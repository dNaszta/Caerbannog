package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationDryRun
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

private val settingItem = Regex("\"([^\"]+)\" (?:as the text \"([^\"]*)\"|as the number (-?\\d+)|(-?\\d+))")

/** The settings of a clause, in the order written. Fails on a clause it cannot read, so a typo in a scenario never means "no settings". */
internal fun parseStreamSettings(clause: String): Map<String, ArgumentValue> {
    val found = settingItem.findAll(clause).toList()
    assertTrue(found.isNotEmpty(), "no setting could be read from: $clause")
    val rest = settingItem.replace(clause, "").replace(Regex("[ ,]|and"), "")
    assertEquals("", rest, "text left over in the settings clause: $clause")
    return found.associate { m ->
        val text = m.groups[2]?.value
        val number = (m.groups[3] ?: m.groups[4])?.value
        m.groupValues[1] to (if (text != null) ArgumentValue.Text(text) else ArgumentValue.Whole(number!!.toLong()))
    }
}

/**
 * M9-14c step definitions: the `@m9-14c @fake-broker` scenarios of `10_topology_migrations.feature` that apply, validate or dry-run a stream with
 * settings (the export ones are in `M914ExportSteps`). The REAL parser, runner and management executor run over the honest `FakeManagementBroker`
 * of `M508aExecutorSteps` (the same scenario broker and the same real executor, so "no request deleted or recreated anything" looks at the broker
 * these steps seeded). A settings clause reads `"x-max-age" as the text "7D", "x-max-length-bytes" 1000000000 and "x-queue-leader-locator" as the text "balanced"`:
 * a quoted key, then `as the text "..."`, `as the number N` or a bare number.
 */
class M914cStreamSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }
    private val exec: M508aExecutorSteps get() = checkNotNull(M508aExecutorSteps.current) { "no executor state" }
    private val broker get() = exec.broker

    private var snapshotBefore: String? = null
    private var dryRun: MigrationDryRun? = null
    private var fileJson: String = ""
    private var validation: MigrationValidationResult? = null

    @Before
    fun reset() { snapshotBefore = null; dryRun = null; fileJson = ""; validation = null }

    private fun json(value: ArgumentValue): JsonElement = when (value) {
        is ArgumentValue.Text -> JsonPrimitive(value.value)
        is ArgumentValue.Whole -> JsonPrimitive(value.value)
        else -> error("not a stream setting value: $value")
    }

    private fun declare(name: String, arguments: Map<String, ArgumentValue>) {
        exec.real()
        fileJson = MigrationParser.exportToJson("0001", "m9-14c", listOf(MigrationAction.DeclareStream(name, "/", arguments)))
        exec.import(fileJson)
    }

    // ---- Given: a pending migration ----

    @Given("^a pending migration declares the stream \"([^\"]*)\" with (\"[^\"]+\" .+)$")
    fun pendingWithSettings(name: String, clause: String) = declare(name, parseStreamSettings(clause))

    @Given("^a pending migration declares the stream \"([^\"]*)\" with no settings$")
    fun pendingWithoutSettings(name: String) = declare(name, emptyMap())

    @Then("^the file of the migration holds the stream \"([^\"]*)\" with no \"arguments\" key$")
    fun fileHasNoArguments(name: String) {
        assertTrue(fileJson.contains("\"name\": \"$name\""), fileJson)
        assertFalse(fileJson.contains("arguments"), "the old shape must not gain an arguments key: $fileJson")
        assertEquals(listOf<MigrationAction>(MigrationAction.DeclareStream(name)), (MigrationParser.parse(fileJson) as MigrationValidationResult.Valid).migration.up)
    }

    // ---- Given: what the broker has ----

    /**
     * A stream as the product or the CLI makes it: the declared settings, `x-queue-type: stream` and, when no locator is declared, the default
     * `x-queue-leader-locator: balanced` (S-14c-5). The broker is the one of the executor, so the apply sees it.
     */
    @Given("^the broker has the stream \"([^\"]*)\" with (\"[^\"]+\" .+)$")
    fun brokerHasStream(name: String, clause: String) {
        exec.real()
        val declared = parseStreamSettings(clause).mapValues { json(it.value) }
        broker.addQueue("/", name, type = "stream", arguments = mapOf("x-queue-leader-locator" to JsonPrimitive("balanced")) + declared)
        snapshotBefore = broker.snapshot()
    }

    // ---- Then: what the broker holds ----

    @Then("^the broker has the stream \"([^\"]*)\"$")
    fun brokerHasPlainStream(name: String) {
        assertTrue(broker.hasQueue("/", name), "the broker has no stream $name")
        assertEquals(JsonPrimitive("stream"), broker.queueArguments("/", name)!!["x-queue-type"])
    }

    @Then("^the stream \"([^\"]*)\" on the broker has (\"[^\"]+\" .+)$")
    fun streamHas(name: String, clause: String) {
        val actual = checkNotNull(broker.queueArguments("/", name)) { "the broker has no stream $name" }
        assertEquals(JsonPrimitive("stream"), actual["x-queue-type"])
        for ((key, value) in parseStreamSettings(clause)) assertEquals(json(value), actual[key], "$key of $name as the broker shows it: $actual")
    }

    @Then("^\"([^\"]*)\" on the broker still has (\"[^\"]+\" .+)$")
    fun streamStillHas(name: String, clause: String) {
        streamHas(name, clause)
        snapshotBefore?.let { assertEquals(it, broker.snapshot(), "the broker changed") }
    }

    @Then("the migration is recorded as applied and nothing on the broker changed")
    fun recordedAndUnchanged() = runBlocking {
        assertTrue(s.lastApplyResult is MigrationApplyResult.Success, "the apply gave ${s.lastApplyResult}")
        assertEquals(setOf("0001"), s.historyRepository.getAppliedMigrations(s.profileName).keys)
        assertEquals(snapshotBefore, broker.snapshot(), "the broker changed")
        assertTrue(broker.streamCreateCalls.isEmpty(), "the stream manager was asked to create: ${broker.streamCreateCalls}")
        assertTrue(broker.writes.isEmpty() && broker.deletes.isEmpty() && broker.streamDeleteCalls.isEmpty(), "something was written or deleted")
    }

    // ---- validate a file ----

    @Given("^a migration file declares the stream \"([^\"]*)\" with the setting \"([^\"]*)\" as (.+)$")
    fun fileWithSetting(name: String, key: String, value: String) {
        val rendered = Regex("^the text \"(.*)\"$").matchEntire(value)?.let { "\"" + it.groupValues[1].replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            ?: Regex("^the number (-?\\d+)$").matchEntire(value)?.groupValues?.get(1)
            ?: error("cannot read the value: $value")
        fileJson = """{ "version": "0001", "description": "m9-14c", "up": [ { "action": "declareStream", "name": "$name", "arguments": { "$key": $rendered } } ] }"""
    }

    @When("I validate the file")
    fun validate() { validation = MigrationParser.parse(fileJson) }

    @Then("^the file is refused with \"(.*)\"$")
    fun refusedWith(reason: String) {
        val result = assertNotNull(validation, "the file was not validated")
        assertTrue(result is MigrationValidationResult.Invalid, "the file was accepted: $result")
        assertEquals(reason, (result as MigrationValidationResult.Invalid).reason)
    }

    // ---- the dry run of a pending migration ----

    @When("I run a dry run of it")
    fun dryRunOfIt() {
        val file = runBlocking { s.fileRepository.listFiles().single() }
        dryRun = runBlocking { s.runner.dryRun(file, s.configurePermission, s.controlLevel, vhost = "/") }
        assertTrue(broker.requests.isEmpty() && broker.streamCreateCalls.isEmpty(), "the dry run sent something to the broker: ${broker.requests}")
    }

    @Then("^the action reads \"([^\"]*)\" with the detail \"([^\"]*)\"$")
    fun actionReads(description: String, detail: String) {
        val row = assertNotNull(dryRun, "no dry run").actions.single()
        assertEquals(description, row.action.description)
        assertEquals(detail, row.action.detail)
        assertTrue(row.allowed, "the action is blocked: ${row.reason}")
    }

    @Then("^the action is blocked because the user cannot change topology on \"([^\"]*)\"$")
    fun actionBlocked(profile: String) {
        val dry = assertNotNull(dryRun, "no dry run")
        val row = dry.actions.single()
        assertFalse(row.allowed, "the action is allowed")
        assertFalse(dry.canApply)
        assertTrue(s.getVm().state.value.bannerMessage.orEmpty().contains("cannot change topology on $profile"), "banner: ${s.getVm().state.value.bannerMessage}")
        assertTrue(broker.streamCreateCalls.isEmpty(), "a blocked stream was created")
    }
}
