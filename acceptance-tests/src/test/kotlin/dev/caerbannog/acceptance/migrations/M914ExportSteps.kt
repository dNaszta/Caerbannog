package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.ExchangeRow
import dev.caerbannog.core.topology.PolicyRow
import dev.caerbannog.core.topology.QueueDeclaration
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M9-14 step definitions: the `@m9-14 @fake-broker` scenarios of `10_topology_migrations.feature` (and the policy line of "Export the current topology as a
 * migration"). The REAL view model exports from the honest [dev.caerbannog.core.connections.fakes.FakeTopologyCatalog]; the shared scenario state is
 * that of [TopologyMigrationsSteps] (`TopologyMigrationsSteps.current`). Every check reads the parsed actions or the raw file, never a display text,
 * except the one line under the export result (`MigrationsScreenState.exportNote`), where the names and the two words (retention, exclusive) are looked for.
 */
class M914ExportSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }
    private val catalog get() = s.fakeTopologyCatalog
    private val file get() = s.exportedFile ?: error("nothing was exported: ${s.getVm().state.value.errorMessage}")

    private fun declared(name: String, type: String = "classic", durable: Boolean = true, autoDelete: Boolean = false, exclusive: Boolean = false, arguments: Map<String, ArgumentValue> = emptyMap()) =
        QueueDeclaration(name = name, vhost = "/", type = type, durable = durable, autoDelete = autoDelete, exclusive = exclusive, arguments = arguments)

    // ---- Given ----------------------------------------------------------------------------------------------------------------

    @Given("the broker has the quorum queue {string}")
    fun quorumQueue(name: String) { catalog.declare(declared(name, "quorum")) }

    @Given("the broker has the quorum queue {string} with the argument {string} as the number {int} and {string} as the text {string}")
    fun quorumQueueWithArguments(name: String, number: String, value: Int, text: String, textValue: String) {
        catalog.declare(declared(name, "quorum", arguments = mapOf(number to ArgumentValue.Whole(value.toLong()), text to ArgumentValue.Text(textValue))))
    }

    @Given("the broker has the headers exchange {string} with the argument {string} as the text {string}")
    fun headersExchange(name: String, key: String, value: String) {
        catalog.addExchange(ExchangeRow(name, "/", "headers", true, false, false, typedArguments = mapOf(key to ArgumentValue.Text(value))))
    }

    @Given("the broker has a binding from {string} to {string} with the arguments {string} as the number {int}, {string} as the text {string} and {string} as the flag true")
    fun bindingWithArguments(source: String, destination: String, numberKey: String, number: Int, textKey: String, text: String, flagKey: String) {
        catalog.addBinding(
            BindingRow(
                source, "/", destination, BindingDestinationType.QUEUE, "",
                typedArguments = mapOf(numberKey to ArgumentValue.Whole(number.toLong()), textKey to ArgumentValue.Text(text), flagKey to ArgumentValue.Flag(true)),
            ),
        )
    }

    @Given("the broker has the policy {string} for the pattern {string} with {string} {int}")
    fun policyDefault(name: String, pattern: String, key: String, value: Int) {
        catalog.addPolicy(PolicyRow(name, "/", pattern, "all", 0, mapOf(key to ArgumentValue.Whole(value.toLong()))))
    }

    @Given("the broker has the policy {string} for the pattern {string} applying to {string} with priority {int} and {string} {int}")
    fun policy(name: String, pattern: String, applyTo: String, priority: Int, key: String, value: Int) {
        catalog.addPolicy(PolicyRow(name, "/", pattern, applyTo, priority, mapOf(key to ArgumentValue.Whole(value.toLong()))))
    }

    @Given("the broker has the operator policy {string} for the pattern {string} with {string} {int}")
    fun operatorPolicy(name: String, pattern: String, key: String, value: Int) {
        catalog.addOperatorPolicy(PolicyRow(name, "/", pattern, "queues", 0, mapOf(key to ArgumentValue.Whole(value.toLong()))))
    }

    @Given("the broker has a super stream {string} with {int} partitions")
    fun superStream(name: String, partitions: Int) {
        catalog.addExchange(ExchangeRow(name, "/", "direct", true, false, false, arguments = mapOf("x-super-stream" to "true"), superStream = true, typedArguments = mapOf("x-super-stream" to ArgumentValue.Flag(true))))
        for (i in 0 until partitions) {
            // measured: the CLI gives each partition stream x-queue-leader-locator=balanced
            catalog.addStream("$name-$i", arguments = mapOf("x-queue-leader-locator" to ArgumentValue.Text("balanced")))
            catalog.addBinding(BindingRow(name, "/", "$name-$i", BindingDestinationType.QUEUE, "$i", typedArguments = mapOf("x-stream-partition-order" to ArgumentValue.Whole(i.toLong()))))
        }
    }

    @Given("the broker has an exclusive queue {string}, a server-named queue {string} and the MQTT subscription queues {string} and {string}")
    fun connectionQueues(exclusive: String, serverNamed: String, qos0: String, qos1: String) {
        catalog.declare(declared(exclusive, "classic", durable = false, exclusive = true))
        catalog.declare(declared(serverNamed, "classic", durable = false, autoDelete = true, exclusive = true))
        catalog.declare(declared(qos0, "rabbit_mqtt_qos0_queue", exclusive = true))
        catalog.declare(declared(qos1, "classic", exclusive = true))
    }

    @Given("all of them are bound to the exchange {string}")
    fun allBound(exchange: String) {
        catalog.addExchange(ExchangeRow(exchange, "/", "topic", true, false, false))
        val names = (runBlocking { catalog.queueDeclarations("/") } as CatalogResult.Success).value.map { it.name }
        for (name in names) catalog.addBinding(BindingRow(exchange, "/", name, BindingDestinationType.QUEUE, "k"))
        catalog.calls.clear(); catalog.requests.clear()
    }

    @Given("the broker has the stream {string} with retention {string} as the text {string}")
    fun streamWithRetention(name: String, key: String, value: String) {
        catalog.addStream(name, arguments = mapOf(key to ArgumentValue.Text(value)))
    }

    @Given("the broker has an exclusive queue {string}")
    fun exclusiveQueue(name: String) { catalog.declare(declared(name, "classic", durable = false, exclusive = true)) }

    @Given("the broker refuses to list the policies of {string} for this user")
    fun refusesPolicies(vhost: String) {
        assertEquals("/", vhost)
        catalog.failCall("policies", CatalogResult.NotAuthorised)
    }

    // ---- Then -----------------------------------------------------------------------------------------------------------------

    private fun queue(name: String) = file.up.filterIsInstance<MigrationAction.DeclareQueue>().singleOrNull { it.name == name }
        ?: error("the export has no queue \"$name\": ${file.up}")

    @Then("the new migration declares {string} with {string} as the number {int} and {string} as the text {string}")
    fun declaresQueueWithArguments(name: String, numberKey: String, number: Int, textKey: String, text: String) {
        assertEquals(mapOf(numberKey to ArgumentValue.Whole(number.toLong()), textKey to ArgumentValue.Text(text)), queue(name).arguments)
    }

    @Then("the new migration declares the exchange {string} with {string} as the text {string}")
    fun declaresExchangeText(name: String, key: String, value: String) {
        val exchange = file.up.filterIsInstance<MigrationAction.DeclareExchange>().singleOrNull { it.name == name } ?: error("no exchange $name in ${file.up}")
        assertEquals(mapOf(key to ArgumentValue.Text(value)), exchange.arguments)
    }

    @Then("the new migration declares the exchange {string} with {string} as the flag true")
    fun declaresExchangeFlag(name: String, key: String) {
        val exchange = file.up.filterIsInstance<MigrationAction.DeclareExchange>().singleOrNull { it.name == name } ?: error("no exchange $name in ${file.up}")
        assertEquals(mapOf(key to ArgumentValue.Flag(true)), exchange.arguments)
    }

    @Then("the binding from {string} to {string} keeps {string} as the number {int}, {string} as the text {string} and {string} as the flag true")
    fun bindingKeeps(source: String, destination: String, numberKey: String, number: Int, textKey: String, text: String, flagKey: String) {
        val binding = file.up.filterIsInstance<MigrationAction.DeclareBinding>().singleOrNull { it.exchange == source && it.queue == destination } ?: error("no binding in ${file.up}")
        assertEquals(mapOf(numberKey to ArgumentValue.Whole(number.toLong()), textKey to ArgumentValue.Text(text), flagKey to ArgumentValue.Flag(true)), binding.arguments)
    }

    @Then("the file writes the number {int} as a JSON number, not as text")
    fun writesNumber(number: Int) {
        assertTrue(Regex("\"[^\"]+\": $number(?![\\d\"])").containsMatchIn(file.rawJson), "no JSON number $number in ${file.rawJson}")
        assertFalse(file.rawJson.contains("\"$number\""), "the number was written as text: ${file.rawJson}")
    }

    @Then("the file parses back to the same actions")
    fun parsesBack() {
        val parsed = MigrationParser.parse(file.rawJson)
        assertTrue(parsed is MigrationValidationResult.Valid, "the exported file is not a valid migration: $parsed")
        assertEquals(file.up, (parsed as MigrationValidationResult.Valid).migration.up)
    }

    @Then("the new migration declares the policy {string} for {string} applying to {string} with priority {int} and {string} as the number {int}")
    fun declaresPolicy(name: String, pattern: String, applyTo: String, priority: Int, key: String, value: Int) {
        val policy = file.up.filterIsInstance<MigrationAction.DeclarePolicy>().singleOrNull { it.name == name } ?: error("no policy $name in ${file.up}")
        assertEquals(MigrationAction.DeclarePolicy(name, pattern, mapOf(key to ArgumentValue.Whole(value.toLong())), "/", applyTo, priority), policy)
    }

    @Then("the new migration declares the operator policy {string} for {string} with {string} as the number {int}")
    fun declaresOperatorPolicy(name: String, pattern: String, key: String, value: Int) {
        val policy = file.up.filterIsInstance<MigrationAction.DeclareOperatorPolicy>().singleOrNull { it.name == name } ?: error("no operator policy $name in ${file.up}")
        assertEquals(pattern, policy.pattern)
        assertEquals(mapOf(key to ArgumentValue.Whole(value.toLong())), policy.definition)
        assertTrue(file.up.none { it is MigrationAction.DeclarePolicy && it.name == name }, "an operator policy was exported as a plain policy")
    }

    @Then("the policies are the last actions of the file")
    fun policiesLast() {
        val kinds = file.up.map { it.actionName }
        val firstPolicy = kinds.indexOfFirst { it == "declarePolicy" || it == "declareOperatorPolicy" }
        assertTrue(firstPolicy >= 0)
        assertTrue(kinds.drop(firstPolicy).all { it == "declarePolicy" || it == "declareOperatorPolicy" }, "an action follows a policy: $kinds")
    }

    @Then("it declares the streams {string}, {string} and {string}")
    fun declaresStreams(a: String, b: String, c: String) {
        assertEquals(setOf(a, b, c), file.up.filterIsInstance<MigrationAction.DeclareStream>().map { it.name }.toSet())
    }

    @Then("each partition binding keeps its {string} as a number")
    fun partitionOrder(key: String) {
        val bindings = file.up.filterIsInstance<MigrationAction.DeclareBinding>().sortedBy { it.queue }
        assertEquals(3, bindings.size)
        bindings.forEachIndexed { i, b -> assertEquals(ArgumentValue.Whole(i.toLong()), b.arguments[key], "binding to ${b.queue}") }
        assertTrue(Regex("\"$key\": \\d").containsMatchIn(file.rawJson), file.rawJson)
    }

    @Then("the new migration declares only the queue {string}")
    fun onlyQueue(name: String) {
        assertEquals(listOf(name), file.up.filterIsInstance<MigrationAction.DeclareQueue>().map { it.name })
        assertTrue(file.up.none { it is MigrationAction.DeclareStream })
    }

    @Then("it declares only the binding of {string}")
    fun onlyBinding(queue: String) {
        assertEquals(listOf(queue), file.up.filterIsInstance<MigrationAction.DeclareBinding>().map { it.queue })
    }

    @Then("the new migration declares the stream {string} without any retention")
    fun streamWithoutRetention(name: String) {
        assertEquals(listOf(name), file.up.filterIsInstance<MigrationAction.DeclareStream>().map { it.name })
        assertFalse(file.rawJson.contains("x-max-age") || file.rawJson.contains("max-age"), "nothing may be invented: ${file.rawJson}")
    }

    @Then("the export result says that retention of {string} and the exclusive queue {string} were left out")
    fun noteSays(stream: String, queue: String) {
        val note = s.getVm().state.value.exportNote
        assertNotNull(note, "the export said nothing about what it left out")
        assertTrue(note!!.contains(stream) && note.contains("retention", ignoreCase = true), "the note does not name the retention of $stream: $note")
        assertTrue(note.contains(queue) && note.contains("exclusive", ignoreCase = true), "the note does not name the exclusive queue $queue: $note")
    }

    @Then("the export result has no line about what was left out")
    fun noNote() {
        assertNotNull(s.exportedFile, "nothing was exported")
        assertNull(s.getVm().state.value.exportNote)
    }
}
