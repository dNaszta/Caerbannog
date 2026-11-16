package dev.caerbannog.acceptance.migrations

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import dev.caerbannog.core.topology.BindingRow
import dev.caerbannog.core.topology.QueueDeclaration
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * M9-14b step definitions: the `@m9-14b @fake-broker` scenarios of `10_topology_migrations.feature` (the note wording, the cap of ten entries, the sentence for a
 * setting the file cannot carry, the completeness check that covers only what is written). Same scenario state as [M914ExportSteps]
 * (`TopologyMigrationsSteps.current`); the REAL view model exports from the honest fake catalog. The note is read as the one text `exportNote`, because
 * the wording IS the behaviour here; the unit tests of `TopologyExport` pin each row of the wording table.
 */
class M914bExportSteps {

    private val s: TopologyMigrationsSteps get() = checkNotNull(TopologyMigrationsSteps.current) { "no scenario state" }
    private val catalog get() = s.fakeTopologyCatalog
    private val note: String? get() = s.getVm().state.value.exportNote

    // ---- Given ----------------------------------------------------------------------------------------------------------------

    @Given("the broker has the stream {string} with the leader locator {string}")
    fun streamWithLeaderLocator(name: String, locator: String) {
        catalog.addStream(name, arguments = mapOf("x-queue-leader-locator" to ArgumentValue.Text(locator)))
    }

    @Given("the broker has the stream {string} with retention {string} as the text {string} and the leader locator {string}")
    fun streamWithRetentionAndLeaderLocator(name: String, key: String, value: String, locator: String) {
        catalog.addStream(name, arguments = mapOf(key to ArgumentValue.Text(value), "x-queue-leader-locator" to ArgumentValue.Text(locator)))
    }

    /** `conn-1` to `conn-12`: the number in the last name is the count, the names are the prefix of the first plus the running number. */
    @Given("the broker has {int} exclusive queues named {string} to {string}")
    fun exclusiveQueues(count: Int, first: String, last: String) {
        val prefix = first.dropLastWhile { it.isDigit() }
        assertEquals("$prefix$count", last, "the last name must be the prefix and the count")
        for (i in 1..count) catalog.declare(QueueDeclaration("$prefix$i", "/", "classic", durable = false, exclusive = true))
    }

    @Given("the broker has the quorum queue {string} with an argument that cannot be written to a migration file")
    fun quorumQueueWithUnwritableArgument(name: String) {
        catalog.declare(QueueDeclaration(name, "/", "quorum", typedArgumentsComplete = false))
    }

    @Given("the broker has the quorum queue {string} and an exclusive queue {string}")
    fun quorumQueueAndExclusive(quorum: String, exclusive: String) {
        catalog.declare(QueueDeclaration(quorum, "/", "quorum"))
        catalog.declare(QueueDeclaration(exclusive, "/", "classic", durable = false, exclusive = true))
    }

    @Given("the broker has a binding from {string} to {string} with an argument that cannot be written to a migration file")
    fun bindingWithUnwritableArgument(source: String, destination: String) {
        catalog.addBinding(BindingRow(source, "/", destination, BindingDestinationType.QUEUE, "k", typedArgumentsComplete = false))
    }

    // ---- Then -----------------------------------------------------------------------------------------------------------------

    @Then("the export result reads {string}")
    fun exportResultReads(expected: String) {
        assertNotNull(s.exportedFile, "nothing was exported: ${s.getVm().state.value.errorMessage}")
        assertEquals(expected, note, "the line under the export result")
    }

    @Then("the export result does not name {string}, {string} or {string}")
    fun exportResultDoesNotName(a: String, b: String, c: String) {
        val text = checkNotNull(note) { "the export said nothing about what it left out" }
        for (name in listOf(a, b, c)) assertTrue(!text.contains(name), "the note names \"$name\": $text")
    }

    @Then("the export result names {int} entries and ends with {string}")
    fun exportResultNamesEntries(count: Int, ending: String) {
        val text = checkNotNull(note) { "the export said nothing about what it left out" }
        val entries = text.removePrefix("Left out: ").split("; ")
        assertEquals(count + 1, entries.size, "ten named entries then the closing one: $text")
        assertEquals(ending, entries.last(), text)
        assertTrue(entries.dropLast(1).none { it.startsWith("and ") }, text)
    }

    @Then("I am told the topology was read but a setting in it cannot be written to a migration file, and no migration file was written")
    fun toldTopologyNotWritable() = runBlocking {
        assertEquals(null, s.exportedFile, "the export returned a file although a setting cannot be written")
        assertEquals("The topology was read, but a setting in it cannot be written to a migration file.", s.getVm().state.value.errorMessage)
        assertTrue(s.fileRepository.listFiles().isEmpty(), "a migration file was written: ${s.fileRepository.listFiles().map { it.version }}")
    }

    @Then("the export result says that the exclusive queue {string} was left out")
    fun exportResultSaysExclusiveLeftOut(queue: String) {
        val file = s.exportedFile ?: error("nothing was exported: ${s.getVm().state.value.errorMessage}")
        assertTrue(file.up.none { it is MigrationAction.DeclareQueue && it.name == queue }, "the queue was written: ${file.up}")
        assertEquals("Left out: exclusive queue $queue", note)
    }
}
