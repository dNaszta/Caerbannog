package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.DecisionRefusal
import dev.caerbannog.core.migrate3x.DecisionResult
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.DefinitionsOrigin
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.ConversionReview
import dev.caerbannog.core.migrate3x.FindingClass
import dev.caerbannog.core.migrate3x.GenerationRequest
import dev.caerbannog.core.migrate3x.GenerationResult
import dev.caerbannog.core.migrate3x.ItemDecision
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.Proposal
import dev.caerbannog.core.migrate3x.ReviewItem
import dev.caerbannog.core.migrate3x.SkipReason
import dev.caerbannog.core.migrate3x.fakes.ConversionReviews
import dev.caerbannog.core.migrate3x.fakes.DefinitionsDocuments
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrations.InMemoryMigrationFileRepository
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

private typealias Docs = DefinitionsDocuments

/**
 * M8-05 step definitions for the `@m8-05` scenarios of 16_migrate_from_3x.feature (apply scenarios: `M805GeneratedApplySteps`). Givens build a
 * hand-made document in [Migrate3xWorld]; Whens decide items and call `ConversionReview.generate`; Thens read the result. No review logic here.
 * The review and its report are built on demand from the document, so a Given never has to know the order of the steps.
 */
class Migrate3xConversionSteps {
    private val world = Migrate3xWorld
    private val tick = Acknowledgement.OrderingByPriorityMayChange

    // ---- plumbing ----

    private fun document(): DefinitionsDocument = world.document ?: fail("the scenario built no definitions document")

    private fun review(): ConversionReview {
        world.review?.let { return it }
        val (review, report) = ConversionReviews.start(document(), world.live)
        world.report = report
        world.review = review
        return review
    }

    private fun onlyItem(): ReviewItem = review().items.singleOrNull() ?: fail("expected exactly one item that needs a decision but got ${review().items}")

    private fun item(name: String): ReviewItem =
        review().items.singleOrNull { it.name == name } ?: fail("\"$name\" is not an item that needs a decision; items: ${review().items.map { it.name }}")

    private fun ack(text: String): Acknowledgement = Acknowledgement.entries.singleOrNull { it.text == text } ?: fail("no tick with the text \"$text\"")

    private fun findingId(rule: String, name: String): String {
        review()
        return world.report!!.findings.single { it.rule == rule && it.name == name }.id
    }

    private fun proposalIndex(name: String, matches: (Proposal) -> Boolean): Int {
        review()
        val all = world.report!!.findings.single { it.rule == "classic-not-replicated" && it.name == name }.let { listOf(it.proposal) + it.alternatives }
        return all.indexOfFirst(matches).also { assertTrue(it >= 0, "no matching proposal in $all") }
    }

    private fun decide(item: ReviewItem, decision: ItemDecision): DecisionResult = review().decide(item, decision).also { world.lastDecision = it }

    private fun mustDecide(item: ReviewItem, decision: ItemDecision) =
        assertEquals(DecisionResult.Decided, decide(item, decision), "deciding ${item.name} with $decision")

    private fun request() = GenerationRequest(world.existingVersions, "legacy-eu")

    private fun generate(): GenerationResult = review().generate(request()).also { world.generation = it }

    private fun generated(): GenerationResult.Generated =
        when (val result = world.generation?.takeIf { world.review != null } ?: generate()) {
            is GenerationResult.Generated -> result
            is GenerationResult.Incomplete -> fail("expected a migration but ${result.pending.size} items still need a decision: ${result.pending.map { it.name }}")
        }

    private fun fresh(): GenerationResult.Generated = generate().let { generated() }

    private fun actions(): List<MigrationAction> = fresh().migrations.flatMap { it.file.up }

    private fun queues(): List<MigrationAction.DeclareQueue> = actions().filterIsInstance<MigrationAction.DeclareQueue>()

    private fun queue(name: String): MigrationAction.DeclareQueue = queues().singleOrNull { it.name == name } ?: fail("no declared queue \"$name\" in ${actions().map { it.description }}")

    private fun namesDeclared(): List<String> = actions().mapNotNull {
        when (it) {
            is MigrationAction.DeclareQueue -> it.name
            is MigrationAction.DeclareExchange -> it.name
            is MigrationAction.DeclarePolicy -> it.name
            is MigrationAction.DeclareOperatorPolicy -> it.name
            is MigrationAction.DeclareStream -> it.name
            else -> null
        }
    }

    private fun value(n: Int): ArgumentValue = ArgumentValue.Whole(n.toLong())

    // ---- Given ----

    @Given("the report proposes to declare the classic queue {string} as a quorum queue")
    fun reportProposesQuorum(name: String) {
        world.document = Docs.document(queues = listOf(Docs.classicQueue(name)))
        world.review = null
        assertEquals(listOf(name), review().items.map { it.name }, "the premise: the queue needs one decision")
    }

    @Given("the report has {int} items that need a decision and I decided only {int}")
    fun reportHasItems(total: Int, decided: Int) {
        world.document = Docs.document(queues = (1..total).map { Docs.classicQueue("queue-$it") })
        world.review = null
        assertEquals(total, review().items.size)
        review().items.take(decided).forEach { mustDecide(it, ItemDecision.Skip) }
    }

    @Given("a definitions file with the non-durable queue {string}")
    fun nonDurableQueue(name: String) {
        world.document = Docs.document(queues = listOf(Docs.classicQueue(name, durable = false)))
        world.review = null
    }

    @Given("a definitions file with the classic queue {string} with {string} {int}")
    fun classicQueueWith(name: String, argument: String, value: Int) {
        world.document = Docs.document(queues = listOf(Docs.classicQueue(name, arguments = Docs.args(argument to value))))
        world.review = null
    }

    @Given("a definitions file with the classic queue {string} and no {string}")
    fun classicQueueWithout(name: String, argument: String) {
        world.document = Docs.document(queues = listOf(Docs.classicQueue(name)))
        world.review = null
        assertFalse(argument in document().queues.single().arguments)
    }

    @Given("the report has a message-loss warning for {string}")
    fun reportHasMessageLoss(name: String) {
        review()
        assertTrue(world.report!!.findings.any { it.findingClass == FindingClass.MessageLoss && it.name == name }, "the report has no message-loss finding for $name")
    }

    @Given("a definitions file with the policy {string} that only sets {string} and {string}")
    fun policyOnlySets(name: String, first: String, second: String) {
        world.document = Docs.document(policies = listOf(Docs.policy(name, "^a", Docs.args(first to "all", second to "automatic"))))
        world.review = null
    }

    @Given("a definitions file with the policy {string} that sets {string} and {string} {int}")
    fun policySetsTwo(name: String, ha: String, other: String, number: Int) {
        world.document = Docs.document(policies = listOf(Docs.policy(name, "^a", Docs.args(ha to "all", other to number))))
        world.review = null
    }

    @Given("a definitions file with the queue {string}, the exchange {string} and a binding between them")
    fun queueExchangeBinding(queue: String, exchange: String) {
        world.document = Docs.document(
            queues = listOf(Docs.classicQueue(queue)), exchanges = listOf(Docs.exchange(exchange)), bindings = listOf(Docs.binding(exchange, queue, "key")),
        )
        world.review = null
    }

    @Given("a definitions file with a queue named {string}")
    fun queueNamed(name: String) {
        world.document = Docs.document(queues = listOf(Docs.quorumQueue(name), Docs.quorumQueue("ordinary")))
        world.review = null
    }

    @Given("a definitions file with a binding, a policy, a queue and an exchange that are all compatible")
    fun allCompatible() {
        world.document = Docs.document(
            bindings = listOf(Docs.binding("events", "orders", "k")), policies = listOf(Docs.policy("limits", "^orders$", Docs.args("max-length" to 1000))),
            queues = listOf(Docs.quorumQueue("orders")), exchanges = listOf(Docs.exchange("events")),
        )
        world.review = null
        assertEquals(emptyList<ReviewItem>(), review().items, "the premise: nothing needs a decision")
    }

    @Given("I accepted every proposal for {string}")
    fun acceptedEverything(vhost: String) {
        world.document = Docs.document(
            queues = listOf(Docs.classicQueue("orders", vhost), Docs.quorumQueue("jobs", vhost)), exchanges = listOf(Docs.exchange("events", "topic", vhost)),
            bindings = listOf(Docs.binding("events", "orders", "order.#", vhost)), policies = listOf(Docs.policy("limits", "^orders$", Docs.args("max-length" to 1000), vhost = vhost)),
        )
        world.review = null
        assertEquals(emptyList<ReviewItem>(), ConversionReviews.acceptEveryDefault(review(), world.report!!), "every default must be acceptable")
    }

    @Given("the report covers the virtual hosts {string} and {string}")
    fun reportCoversTwo(first: String, second: String) {
        world.document = Docs.document(queues = listOf(Docs.quorumQueue("a", first), Docs.quorumQueue("b", second)))
        world.review = null
    }

    @Given("the per-virtual-host export was given the virtual host name {string}")
    fun singleVhost(name: String) {
        world.document = Docs.document(queues = listOf(Docs.quorumQueue("a", name)), origin = DefinitionsOrigin.SingleVirtualHost(name))
        world.review = null
    }

    @Given("a definitions file with a user {string} that has a password hash and a shovel parameter whose URI contains a password")
    fun userAndShovel(user: String) {
        val text = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)
        assertTrue(text.contains("SENTINEL-HASH-$user") && text.contains("SENTINEL-PASSWORD"), "the fixture must hold the sentinel hash of $user and the sentinel password")
        if (Migrate3xScreenWorld.active) return Migrate3xScreenWorld.setFile(text) // M8-06a: the screen reads the file text
        world.document = (DefinitionsParser.parse(text) as? DefinitionsParseResult.Parsed)?.document ?: fail("the fixture was not parsed")
        world.review = null
        // Accept what can be accepted and skip the rest, so that the migration can be generated from the whole file.
        ConversionReviews.acceptEveryDefault(review(), world.report!!).forEach { mustDecide(it, ItemDecision.Skip) }
    }

    // ---- When: decisions ----

    @When("I try to accept that proposal without ticking {string}")
    fun tryAcceptWithoutTick(text: String) {
        ack(text)
        decide(onlyItem(), ItemDecision.Accept())
    }

    @When("I accept that proposal with the tick {string}")
    fun acceptWithTick(text: String) = mustDecide(onlyItem(), ItemDecision.Accept(acknowledged = setOf(ack(text))))

    @When("I replace the proposal by a quorum queue {string} with the argument {string} {int}")
    fun replaceProposal(name: String, argument: String, number: Int) =
        mustDecide(onlyItem(), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue(name, "quorum", arguments = mapOf(argument to value(number))))))

    @When("I skip that item")
    fun skipThat() = mustDecide(onlyItem(), ItemDecision.Skip)

    @When("I try to accept the default proposal for {string}")
    fun tryAcceptDefault(name: String) {
        decide(item(name), ItemDecision.Accept(acknowledged = setOf(tick)))
    }

    @When("I accept the default proposal for {string}")
    fun acceptDefault(name: String) = mustDecide(item(name), ItemDecision.Accept())

    @When("I choose the quorum alternative without the tick")
    fun quorumWithoutTick() {
        val name = onlyItem().name!!
        decide(onlyItem(), ItemDecision.Accept(mapOf(findingId("classic-not-replicated", name) to proposalIndex(name) { it is Proposal.ConvertQueueType })))
    }

    @When("I choose the quorum alternative with the tick {string}")
    fun quorumWithTick(text: String) {
        val name = onlyItem().name!!
        mustDecide(onlyItem(), ItemDecision.Accept(mapOf(findingId("classic-not-replicated", name) to proposalIndex(name) { it is Proposal.ConvertQueueType }), setOf(ack(text))))
    }

    @When("I accept the proposal for {string} with the tick and generate the migration for {string}")
    fun acceptWithTickAndGenerate(name: String, vhost: String) {
        mustDecide(item(name), ItemDecision.Accept(acknowledged = setOf(tick)))
        generate()
    }

    @When("I accept the proposal for {string} and generate the migration for {string}")
    fun acceptAndGenerate(name: String, vhost: String) {
        val target = review().items.singleOrNull { it.name == name } ?: fail("\"$name\" needs no decision; items ${review().items}")
        mustDecide(target, ItemDecision.Accept())
        generate()
    }

    @When("I skip {string} and generate the migration for {string}")
    fun skipAndGenerate(name: String, vhost: String) {
        mustDecide(item(name), ItemDecision.Skip)
        // The other items of the scenario (none here) stay as they are.
        generate()
    }

    // ---- When: generation ----

    @When("I generate the migration for {string}")
    fun generateFor(vhost: String) {
        generate()
    }

    @When("I generate the migrations")
    fun generateAll() {
        generate()
    }

    @When("I try to generate the migration")
    fun tryGenerate() {
        generate()
    }

    // ---- Then: decisions ----

    @Then("the proposal is not accepted")
    fun notAccepted() {
        val refused = world.lastDecision as? DecisionResult.Refused ?: fail("the proposal was accepted: ${world.lastDecision}")
        assertTrue(refused.reason is DecisionRefusal.TickMissing, "refused for another reason: ${refused.reason}")
    }

    @Then("after I tick it I can accept the proposal")
    fun afterTick() = mustDecide(onlyItem(), ItemDecision.Accept(acknowledged = setOf(tick)))

    @Then("it is refused")
    fun itIsRefused() {
        assertTrue(world.lastDecision is DecisionResult.Refused, "the decision was not refused: ${world.lastDecision}")
    }

    @Then("it is refused and I can only skip it, choose the alternative {string}, or replace it with my own action")
    fun refusedAndOptions(alternative: String) {
        val name = onlyItem().name!!
        val refused = world.lastDecision as? DecisionResult.Refused ?: fail("the default was accepted: ${world.lastDecision}")
        assertTrue(refused.reason is DecisionRefusal.ManualNeeded, "refused for another reason: ${refused.reason}")
        assertEquals("declare it durable", alternative)
        val finding = world.report!!.findings.single { it.rule == "queue-not-durable" && it.name == name }
        assertTrue(finding.alternatives.contains(Proposal.MakeDurable), "the alternative \"declare it durable\" is missing: ${finding.alternatives}")
        mustDecide(onlyItem(), ItemDecision.Accept(mapOf(finding.id to 1 + finding.alternatives.indexOf(Proposal.MakeDurable))))
        mustDecide(onlyItem(), ItemDecision.Skip)
        mustDecide(onlyItem(), ItemDecision.Replace(listOf(MigrationAction.DeclareQueue(name, "quorum"))))
    }

    @Then("I am told that {int} item still needs a decision")
    fun toldPending(count: Int) {
        val result = world.generation as? GenerationResult.Incomplete ?: fail("the migration was generated: ${world.generation}")
        assertEquals(count, result.pending.size, "pending: ${result.pending}")
    }

    @Then("no migration is produced")
    fun noMigration() {
        assertTrue(world.generation is GenerationResult.Incomplete, "a migration was produced: ${world.generation}")
    }

    // ---- Then: the generated migration ----

    @Then("I get a migration file that declares the quorum queue {string}")
    fun declaresQuorum(name: String) = assertEquals("quorum", queue(name).type)

    @Then("the declared queue {string} carries {string} {int}")
    fun carries(name: String, argument: String, number: Int) = assertEquals(value(number), queue(name).arguments[argument], "arguments of $name: ${queue(name).arguments}")

    @Then("the migration does not declare {string}")
    fun doesNotDeclare(name: String) {
        val declared = namesDeclared()
        assertFalse(name in declared, "\"$name\" is declared: $declared")
    }

    @Then("the result lists {string} as skipped")
    fun listsSkipped(name: String) {
        assertTrue(fresh().skipped.any { it.item.name == name && it.reason == SkipReason.SkippedByUser }, "skipped: ${fresh().skipped}")
    }

    @Then("the migration declares {string} as a classic queue with {string} {int}")
    fun classicWith(name: String, argument: String, number: Int) {
        assertEquals("classic", queue(name).type)
        assertEquals(value(number), queue(name).arguments[argument])
    }

    @Then("the migration declares {string} as a quorum queue without {string}")
    fun quorumWithout(name: String, argument: String) {
        assertEquals("quorum", queue(name).type)
        assertFalse(argument in queue(name).arguments, "arguments: ${queue(name).arguments}")
    }

    @Then("the migration declares {string} without {string}")
    fun declaresWithout(name: String, argument: String) = assertFalse(argument in queue(name).arguments, "arguments: ${queue(name).arguments}")

    @Then("the result still lists the message-loss note for {string} with the recommendation of a new broker and a Shovel")
    fun messageLossNote(name: String) {
        val note = fresh().notes.singleOrNull { it.text.contains("uses the old classic queue index") && it.text.contains(name) } ?: fail("no message-loss note for $name: ${fresh().notes}")
        assertTrue(note.text.contains("a new 4.x broker with this migration and a Shovel is the recommended path"), note.text)
    }

    @Then("the result says the policy held only keys that 4.x rejects")
    fun emptiedNote() {
        assertTrue(fresh().notes.any { it.text.contains("held only keys that 4.x rejects") }, "notes: ${fresh().notes.map { it.text }}")
    }

    @Then("the migration declares {string} with {string} {int} and without {string}")
    fun policyWith(name: String, kept: String, number: Int, dropped: String) {
        val policy = actions().filterIsInstance<MigrationAction.DeclarePolicy>().singleOrNull { it.name == name } ?: fail("policy $name is not declared: ${actions().map { it.description }}")
        assertEquals(value(number), policy.definition[kept])
        assertFalse(dropped in policy.definition, "definition: ${policy.definition}")
    }

    @Then("the migration declares {string} and no binding")
    fun declaresAndNoBinding(name: String) {
        assertTrue(name in namesDeclared(), "\"$name\" is not declared: ${namesDeclared()}")
        assertEquals(emptyList<MigrationAction>(), actions().filterIsInstance<MigrationAction.DeclareBinding>())
    }

    @Then("the result lists the binding as left out because {string} is skipped")
    fun bindingLeftOut(name: String) {
        val note = fresh().notes.singleOrNull { it.item?.kind == ItemKind.Binding } ?: fail("no note about a left-out binding: ${fresh().notes}")
        assertTrue(note.text.contains(name) && note.text.contains("skipped"), note.text)
    }

    @Then("the result lists the queue as left out because its name cannot be written")
    fun nameLeftOut() {
        assertTrue(fresh().skipped.any { it.item.kind == ItemKind.Queue && it.item.name == ".." && it.reason == SkipReason.NameCannotBeWritten }, "skipped: ${fresh().skipped}")
    }

    @Then("nothing else is lost silently")
    fun nothingElseLost() {
        assertEquals(listOf("ordinary"), queues().map { it.name }, "every other queue must still be written")
        assertEquals(1, fresh().skipped.size, "skipped: ${fresh().skipped}")
    }

    @Then("the actions are in the order exchange, queue, policy, binding")
    fun order() {
        assertEquals(listOf("declareExchange", "declareQueue", "declarePolicy", "declareBinding"), actions().map { it.actionName })
    }

    // ---- Then: an ordinary migration file ----

    @Then("the file has the next free version number")
    fun nextFree() {
        assertEquals(listOf("0002"), fresh().migrations.map { it.file.version }, "the folder holds ${world.existingVersions}")
    }

    @Then("the file is valid for the migrations screen")
    fun validForScreen() = runBlocking {
        val repository = InMemoryMigrationFileRepository()
        for (m in fresh().migrations) {
            val result = repository.importFile(m.file.rawJson)
            assertTrue(result is MigrationValidationResult.Valid, "the migrations screen refuses the file: $result")
        }
    }

    @Then("a dry run of it shows each action and whether it is allowed")
    fun dryRunShows() {
        val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor(FakeTopologyCatalog(), FakeStreamCatalog()))
        for (m in fresh().migrations) {
            val dry = runner.dryRun(m.file, true, TopologyControlLevel.FULL_CONTROL, m.vhost)
            assertEquals(m.file.up, dry.actions.map { it.action })
            assertTrue(dry.actions.isNotEmpty() && dry.actions.all { it.allowed }, "blocked: ${dry.blockedReason}")
        }
    }

    @Then("the file contains no delete action")
    fun noDelete() {
        assertTrue(actions().isNotEmpty(), "the file has no action at all")
        assertTrue(actions().none { it.isDestructive || it.actionName.startsWith("delete") }, "a delete action: ${actions().map { it.actionName }}")
    }

    @Then("I get one migration for {string} and one for {string}")
    fun oneEach(first: String, second: String) {
        assertEquals(setOf(first, second), fresh().migrations.map { it.vhost }.toSet())
        assertEquals(2, fresh().migrations.size)
    }

    @Then("each can only be applied on a connection whose virtual host matches")
    fun onlyMatching() {
        val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), FakeTopologyMigrationExecutor(FakeTopologyCatalog(), FakeStreamCatalog()))
        for (m in fresh().migrations) {
            assertTrue(runner.dryRun(m.file, true, TopologyControlLevel.FULL_CONTROL, m.vhost).canApply, "${m.vhost}: not allowed on its own virtual host")
            val other = if (m.vhost == "/") "/elsewhere" else "/"
            assertFalse(runner.dryRun(m.file, true, TopologyControlLevel.FULL_CONTROL, other).canApply, "${m.vhost}: allowed on $other")
        }
    }

    @Then("I get one migration for {string}")
    fun oneFor(vhost: String) = assertEquals(listOf(vhost), fresh().migrations.map { it.vhost })

    @Then("the result says the file holds no users, virtual hosts or permissions and that {string} must already exist on the 4.x broker")
    fun singleVhostNote(vhost: String) {
        val expected = "This file is an export of one virtual host and holds no users, virtual hosts or permissions; the virtual host \"$vhost\" must already exist on the 4.x broker"
        assertTrue(expected in fresh().notes.map { it.text }, "notes: ${fresh().notes.map { it.text }}")
    }

    // ---- Then: no request, no secret ----

    @Then("no request was sent to any broker")
    fun noRequest() {
        if (Migrate3xScreenWorld.active) return Migrate3xScreenWorld.assertNoRequest() // M8-06a: the same words on the screen
        // Generation takes the document and the report only; there is no broker, client or URL to call (ConversionSecretsTest guards the source).
        assertTrue(fresh().migrations.isNotEmpty(), "nothing was generated, so \"no request\" proves nothing")
        assertEquals(emptyList<String>(), world.brokerRequests)
    }

    // M8-12b: the generation now writes the access migration, which holds the hash in its own text by design (the broker needs it). The hash may be there and
    // nowhere else: not in the topology files, the result, the notes, any toString (the access file's own toString is checked by AccessSecretsToStringTest).
    @Then("neither the migration file nor the result nor its text contains the password hash or the password")
    fun noSecret() {
        val result = fresh()
        val texts = listOf(result.toString(), review().toString()) + result.migrations.filter { !it.access }.flatMap { listOf(it.file.rawJson, it.file.toString()) } + result.notes.map { "${it.text} ${it.item}" } + result.skipped.map { it.toString() }
        for (text in texts) listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://").forEach { assertFalse(text.contains(it), "\"$it\" appears in: ${text.take(200)}") }
    }
}
