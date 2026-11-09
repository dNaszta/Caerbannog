package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.migrate3x.ArgumentValue
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.DefinitionsOrigin
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.QueueKind
import dev.caerbannog.core.migrate3x.UnassignedDefinitions
import dev.caerbannog.core.migrate3x.UnparsedParameter
import dev.caerbannog.core.migrate3x.VirtualHostAssignment
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

/**
 * M8-01 step definitions for the `@m8-01` scenarios of 16_migrate_from_3x.feature. One class: the scenario state (text, result, draft,
 * assignment) lives in plain fields, no dependency injection. No logic in the steps beyond calling `DefinitionsParser` and asserting.
 */
class Migrate3xDefinitionsSteps {
    private var text: String = ""
    private var result: DefinitionsParseResult? = null
    private var assignment: VirtualHostAssignment? = null

    private fun document(): DefinitionsDocument = when (val r = result ?: fail("No file has been read")) {
        is DefinitionsParseResult.Parsed -> r.document
        is DefinitionsParseResult.NeedsVirtualHost -> assignedDocument() ?: fail("Expected a parsed document but the file needs a virtual host name")
        is DefinitionsParseResult.Rejected -> fail("Expected a parsed document but the file was rejected: ${r.reason.message}")
    }

    private fun assignedDocument(): DefinitionsDocument? = (assignment as? VirtualHostAssignment.Assigned)?.document

    private fun draft(): UnassignedDefinitions =
        (result as? DefinitionsParseResult.NeedsVirtualHost)?.draft ?: fail("Expected the file to need a virtual host name but got $result")

    // ---- Given ----

    @Given("^a file whose content is (.+)$")
    fun aFileWhoseContentIs(description: String) {
        text = when (description) {
            "not JSON at all" -> "not JSON at all"
            "an empty JSON object" -> "{}"
            "a JSON array" -> "[]"
            else -> fail("Unknown file content description: $description")
        }
    }

    @Given("the definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike")
    fun theFullExport() {
        text = DefinitionsFixtures.read(DefinitionsFixtures.FULL)
    }

    @Given("the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike")
    fun thePerVhostExport() {
        text = DefinitionsFixtures.read(DefinitionsFixtures.VHOST_V1)
    }

    @Given("a definitions file with a user {string} that has a password hash")
    fun aFileWithAUserWithAHash(user: String) {
        text = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)
        assertTrue(text.contains("SENTINEL-HASH-$user"), "the fixture must hold the sentinel hash of $user")
    }

    @Given("a shovel parameter whose URI contains a password")
    fun aShovelParameterWithAPassword() {
        assertTrue(text.contains("SENTINEL-PASSWORD"), "the fixture must hold the sentinel password")
    }

    // ---- When ----

    @When("I read the file")
    fun iReadTheFile() {
        result = DefinitionsParser.parse(text)
    }

    /** M8-01 binding: the model half. M8-03 re-binds this step to the full analysis. */
    @When("I analyse the file")
    fun iAnalyseTheFile() {
        result = DefinitionsParser.parse(text)
    }

    @When("^I give the virtual host name (.+)$")
    fun iGiveTheVirtualHostName(description: String) {
        val name = when (description) {
            "an empty text" -> ""
            "only spaces" -> "   "
            "a text with a tab" -> "\t"
            else -> if (description.startsWith("\"") && description.endsWith("\"")) description.substring(1, description.length - 1) else fail("Unknown name description: $description")
        }
        assignment = draft().assignVirtualHost(name)
    }

    // ---- Then: rejection and unassigned ----

    @Then("the file is rejected with the reason {string}")
    fun theFileIsRejectedWithTheReason(reason: String) {
        val r = result
        assertTrue(r is DefinitionsParseResult.Rejected, "Expected the file to be rejected but got $r")
        assertEquals(reason, (r as DefinitionsParseResult.Rejected).reason.message)
    }

    @Then("no report is shown")
    fun noReportIsShown() {
        // M8-01: no report exists yet; "no report" means the result is not a parsed document.
        assertFalse(result is DefinitionsParseResult.Parsed, "Expected no document to come out of the file but got $result")
        assertNotNull(result)
    }

    @Then("I am asked which virtual host name to give the content of this file")
    fun iAmAskedForTheName() {
        val d = draft()
        assertEquals(1, d.queueCount, "queues in the draft")
        assertEquals(0, d.exchangeCount)
        assertEquals(0, d.bindingCount)
        assertEquals(0, d.policyCount)
    }

    @Then("nothing is analysed until a name is given")
    fun nothingIsAnalysed() {
        assertFalse(result is DefinitionsParseResult.Parsed, "an unassigned file must not come out as a document")
        assertTrue(result is DefinitionsParseResult.NeedsVirtualHost, "got $result")
    }

    @Then("the name is refused and the file stays unassigned")
    fun theNameIsRefused() {
        val a = assignment
        assertTrue(a is VirtualHostAssignment.InvalidName, "Expected the name to be refused but got $a")
        assertTrue((a as VirtualHostAssignment.InvalidName).reason.isNotBlank(), "the refusal must say why")
        assertTrue(result is DefinitionsParseResult.NeedsVirtualHost, "the file must stay unassigned")
        assertTrue(draft().assignVirtualHost("orders") is VirtualHostAssignment.Assigned, "assigning again with a good name must still work")
    }

    // ---- Then: the document ----

    @Then("the document comes from RabbitMQ version {string}")
    fun theDocumentComesFromVersion(version: String) {
        assertEquals(version, document().sourceVersion)
    }

    @Then("^the document has (\\d+) virtual hosts?, (\\d+) queues?, (\\d+) exchanges?, (\\d+) bindings? and (\\d+) policies$")
    fun theDocumentHasFirstCounts(vhosts: Int, queues: Int, exchanges: Int, bindings: Int, policies: Int) {
        val d = document()
        assertEquals(listOf(vhosts, queues, exchanges, bindings, policies), listOf(d.virtualHosts.size, d.queues.size, d.exchanges.size, d.bindings.size, d.policies.size), "virtual hosts, queues, exchanges, bindings, policies")
    }

    @Then("^the document has (\\d+) operator policy, (\\d+) users, (\\d+) permissions and (\\d+) topic permission$")
    fun theDocumentHasSecondCounts(operatorPolicies: Int, users: Int, permissions: Int, topicPermissions: Int) {
        val d = document()
        assertEquals(listOf(operatorPolicies, users, permissions, topicPermissions), listOf(d.operatorPolicies.size, d.users.size, d.permissions.size, d.topicPermissions.size), "operator policies, users, permissions, topic permissions")
    }

    @Then("the queue {string} in {string} is a classic queue with the argument {string} set to the number {int}")
    fun theQueueIsClassicWithArgument(name: String, vhost: String, argument: String, number: Int) {
        val q = queue(name, vhost)
        assertEquals(QueueKind.Classic, q.type)
        assertEquals(ArgumentValue.Whole(number.toLong()), q.arguments[argument])
    }

    @Then("the queue {string} in {string} is a quorum queue")
    fun theQueueIsQuorum(name: String, vhost: String) {
        assertEquals(QueueKind.Quorum, queue(name, vhost).type)
    }

    @Then("the queue {string} in {string} is not durable")
    fun theQueueIsNotDurable(name: String, vhost: String) {
        assertFalse(queue(name, vhost).durable)
    }

    @Then("the queue {string} is in the virtual host {string}")
    fun theQueueIsInTheVirtualHost(name: String, vhost: String) {
        assertEquals(vhost, document().queues.single { it.name == name }.vhost)
    }

    @Then("the user {string} has the tags {string} and {string} and the algorithm {string}")
    fun theUserHasTagsAndAlgorithm(user: String, tag1: String, tag2: String, algorithm: String) {
        val u = document().users.single { it.name == user }
        assertEquals(listOf(tag1, tag2), u.tags)
        assertEquals(algorithm, u.hashingAlgorithm)
    }

    @Then("the user {string} is listed with its tags and hashing algorithm")
    fun theUserIsListed(user: String) {
        val u = document().users.single { it.name == user }
        assertEquals(listOf("management", "policymaker"), u.tags)
        assertEquals("rabbit_password_hashing_sha256", u.hashingAlgorithm)
    }

    @Then("the document has no virtual hosts, no users, no permissions and no topic permissions")
    fun theDocumentHasNoAccessPart() {
        val d = document()
        assertEquals(listOf(0, 0, 0, 0), listOf(d.virtualHosts.size, d.users.size, d.permissions.size, d.topicPermissions.size), "virtual hosts, users, permissions, topic permissions")
    }

    @Then("the document says it comes from a single virtual host export named {string}")
    fun theDocumentSaysSingleVhost(name: String) {
        assertEquals(DefinitionsOrigin.SingleVirtualHost(name), document().origin)
    }

    @Then("the text form of the document, of the user and of every parameter contains neither the password hash nor the password")
    fun theTextFormsHoldNoSecret() {
        val d = document()
        val forms = listOf(d.toString(), "$d", d.users.toString()) + d.users.map { it.toString() } + d.otherParameters.map { it.toString() }
        assertTrue(d.otherParameters.isNotEmpty() && d.users.isNotEmpty())
        forms.forEach {
            assertFalse(it.contains("SENTINEL-HASH-svc"), "the password hash is readable in: $it")
            assertFalse(it.contains("SENTINEL-PASSWORD"), "the password is readable in: $it")
        }
    }

    @Then("the shovel parameter is listed by its component and name only")
    fun theShovelParameterIsListed() {
        assertEquals(listOf(UnparsedParameter("/", "shovel", "move-orders")), document().otherParameters)
    }

    private fun queue(name: String, vhost: String) =
        document().queues.singleOrNull { it.name == name && it.vhost == vhost } ?: fail("No queue \"$name\" in \"$vhost\"")
}
