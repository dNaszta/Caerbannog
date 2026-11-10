package dev.caerbannog.acceptance.migrate3x

import dev.caerbannog.core.migrate3x.Acknowledgement
import dev.caerbannog.core.migrate3x.AppliesWhen
import dev.caerbannog.core.migrate3x.ClassicIndexVersion
import dev.caerbannog.core.migrate3x.CompatibilityAnalyzer
import dev.caerbannog.core.migrate3x.CompatibilityReport
import dev.caerbannog.core.migrate3x.CompatibilityReportJson
import dev.caerbannog.core.migrate3x.CompatibilityReportText
import dev.caerbannog.core.migrate3x.DefaultDecision
import dev.caerbannog.core.migrate3x.DefinitionsDocument
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrate3x.Finding
import dev.caerbannog.core.migrate3x.FindingClass
import dev.caerbannog.core.migrate3x.ItemKind
import dev.caerbannog.core.migrate3x.Proposal
import dev.caerbannog.core.migrate3x.QueueKind
import dev.caerbannog.core.migrate3x.QueueRef
import dev.caerbannog.core.migrate3x.Severity
import dev.caerbannog.core.migrate3x.fakes.DefinitionsDocuments
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail

private typealias D = DefinitionsDocuments

/**
 * M8-03 step definitions for the `@m8-03` scenarios of 16_migrate_from_3x.feature and 18_migrate_access_from_3x.feature.
 * Givens build a hand-made document (or pick a recorded export) in [Migrate3xWorld]; Whens call `CompatibilityAnalyzer`;
 * Thens assert on the report. No analysis logic in the steps. Secrets in fixtures are SENTINEL values only.
 */
class Migrate3xAnalyzerSteps {
    private val world = Migrate3xWorld

    private fun report(): CompatibilityReport = world.report ?: fail("No report: the analyzer has not been run, or the file was not accepted")

    private fun Finding.text() = "$message\n$why"

    private fun findings(rule: String, name: String? = null): List<Finding> = report().findings.filter { it.rule == rule && (name == null || it.name == name) }

    private fun one(rule: String, name: String? = null): Finding {
        val found = findings(rule, name)
        assertEquals(1, found.size, "expected exactly one '$rule' finding${name?.let { " for '$it'" } ?: ""} but got ${found.size}; all: ${report().findings.map { "${it.rule}:${it.name}" }}")
        return found.single().also { world.lastFinding = it }
    }

    private fun manualTexts(f: Finding) = (listOf(f.proposal) + f.alternatives).filterIsInstance<Proposal.Manual>().map { "${it.title}\n${it.text}" }

    /** "x-max-priority 5", "x-queue-mode lazy", "x-dead-letter-exchange \"dlx\"", "\"x-max-priority\" 5", "auto-delete set" -> (name, value) */
    private fun setting(text: String): Pair<String, Any?> {
        if (text == "auto-delete set") return "auto_delete" to null
        val name = text.substringBefore(' ').trim('"')
        val raw = text.substringAfter(' ').trim()
        val value: Any = raw.toLongOrNull() ?: when (raw) { "true" -> true; "false" -> false; else -> raw.trim('"') }
        return name to value
    }

    private fun classicQueueWith(name: String, settingText: String, vhost: String = "/") {
        val (arg, value) = setting(settingText)
        world.edit {
            it.copy(queues = it.queues + if (arg == "auto_delete") D.classicQueue(name, vhost, autoDelete = true) else D.classicQueue(name, vhost, arguments = D.args(arg to value!!)))
        }
    }

    // ---- Given: connections and live facts ----

    @Given("a connection {string} to a broker running RabbitMQ 3.13")
    fun aConnectionToA313Broker(name: String) {
        world.live = D.liveFacts(connectionName = name, brokerVersion = "3.13.7")
    }

    @Given("the broker reports the deprecated feature {string} as in use")
    fun theBrokerReportsDeprecatedFeature(feature: String) {
        val live = world.live ?: fail("no connection")
        world.live = live.copy(deprecatedFeaturesInUse = live.deprecatedFeaturesInUse + feature)
    }

    @Given("the classic queue {string} reports storage version {int}")
    fun theClassicQueueReportsStorageVersion(name: String, version: Int) {
        world.edit { it.copy(queues = it.queues + D.classicQueue(name)) }
        val live = world.live ?: fail("no connection")
        val index = if (version == 1) ClassicIndexVersion.V1 else ClassicIndexVersion.V2
        world.live = live.copy(classicIndexVersions = live.classicIndexVersions + (QueueRef("/", name) to index))
    }

    // ---- Given: documents ----

    @Given("a definitions file exported from RabbitMQ 3.13 with {int} queues, {int} exchanges and {int} bindings in {string}")
    fun aFileWithCounts(queues: Int, exchanges: Int, bindings: Int, vhost: String) {
        assertEquals(listOf(3, 2, 4), listOf(queues, exchanges, bindings), "the scenario names 3 queues, 2 exchanges, 4 bindings")
        world.edit {
            it.copy(
                queues = listOf(D.classicQueue("a", vhost), D.classicQueue("b", vhost), D.quorumQueue("c", vhost)),
                exchanges = listOf(D.exchange("x1", "direct", vhost), D.exchange("x2", "fanout", vhost)),
                bindings = (1..bindings).map { n -> D.binding("x1", "c", "rk$n", vhost) },
            )
        }
    }

    @Given("a definitions file from RabbitMQ 3.13")
    fun aFileFrom313() {
        world.text = DefinitionsFixtures.read(DefinitionsFixtures.FULL)
    }

    @Given("a definitions file with a classic queue {string} that is mirrored by a policy")
    fun aMirroredClassicQueue(name: String) {
        world.edit { it.copy(queues = it.queues + D.classicQueue(name), policies = it.policies + D.policy("ha-all", "^$name", D.args("ha-mode" to "all"), "queues")) }
    }

    @Given("a definitions file with the classic queue {string} matched by a mirroring policy")
    fun aMatchedClassicQueue(name: String) = aMirroredClassicQueue(name)

    @Given("a definitions file with the policy {string} that sets {string} and {string} on {string}")
    fun aPolicyThatSetsTwo(name: String, key1: String, key2: String, pattern: String) {
        world.lastPolicy = name
        world.edit { it.copy(policies = it.policies + D.policy(name, pattern, D.args(key1 to "all", key2 to "automatic"))) }
    }

    @Given("a definitions file with a policy that sets {string}")
    fun aPolicyThatSets(key: String) {
        world.lastPolicy = "p-key"
        world.edit { it.copy(policies = it.policies + D.policy("p-key", "^none$", D.args(key to if (key.endsWith("params") || key.endsWith("batch-size")) 2 else "x"))) }
    }

    @Given("a definitions file whose queue {string} has no \"type\" and no \"x-queue-type\" argument")
    fun aQueueWithoutType(name: String) {
        // Text on purpose: the missing type is a parser matter, so the whole path file text -> document -> report is exercised.
        world.text = """{"rabbit_version":"3.13.7","queues":[{"name":"$name","vhost":"/","durable":true,"auto_delete":false,"arguments":{}}]}"""
    }

    @Given("^a definitions file with a classic queue \"([^\"]+)\" with (.+)$")
    fun aClassicQueueWith(name: String, settingText: String) = classicQueueWith(name, settingText)

    @Given("^a definitions file with the queue \"([^\"]+)\" with (.+)$")
    fun aQueueWith(name: String, settingText: String) {
        when (settingText) {
            "durable set to false" -> world.edit { it.copy(queues = it.queues + D.classicQueue(name, durable = false)) }
            "x-queue-master-locator" -> world.edit { it.copy(queues = it.queues + D.classicQueue(name, arguments = D.args("x-queue-master-locator" to "min-masters"))) }
            else -> fail("Unknown queue setting: $settingText")
        }
    }

    @Given("a definitions file with a classic queue {string} without {string}")
    fun aClassicQueueWithout(name: String, argument: String) {
        assertEquals("x-max-priority", argument)
        world.edit { it.copy(queues = it.queues + D.classicQueue(name)) }
    }

    @Given("a definitions file with a classic queue {string}")
    fun aClassicQueue(name: String) {
        world.edit { it.copy(queues = it.queues + D.classicQueue(name)) }
    }

    @Given("a definitions file with the classic queues {string} and {string} where {string} has {string} {int}")
    fun twoClassicQueues(first: String, second: String, holder: String, argument: String, value: Int) {
        assertEquals(second, holder, "the scenario puts the argument on the second queue")
        world.edit { it.copy(queues = it.queues + D.classicQueue(first) + D.classicQueue(second, arguments = D.args(argument to value))) }
    }

    @Given("a definitions file with the quorum queue {string}, the exchange {string} and a binding between them")
    fun aQuorumQueueExchangeBinding(queue: String, exchange: String) {
        world.edit { it.copy(queues = listOf(D.quorumQueue(queue)), exchanges = listOf(D.exchange(exchange)), bindings = listOf(D.binding(exchange, queue, queue))) }
    }

    @Given("a definitions file whose parameters contain an operator policy that sets {string}")
    fun aFileWithAnOperatorPolicy(key: String) {
        // Text on purpose: operator policies are read from the parameters by the parser.
        world.text = """{"rabbit_version":"3.13.7","parameters":[{"component":"operator_policy","name":"op-q","vhost":"/","value":{"pattern":".*","apply-to":"queues","priority":0,"definition":{"$key":"lazy"}}}]}"""
    }

    @Given("a definitions file with the virtual host {string}, the user {string} and its permissions")
    fun aFileWithVhostUserPermissions(vhost: String, user: String) = accessDocument(vhost, user, listOf("management"))

    @Given("a definitions file exported from RabbitMQ 3.13 with the virtual host {string}, the user {string} with tags {string} and permissions on {string}")
    fun aFileExportedWithAccess(vhost: String, user: String, tags: String, permissionVhost: String) {
        assertEquals(vhost, permissionVhost)
        accessDocument(vhost, user, tags.split(",").map { it.trim() })
    }

    private fun accessDocument(vhost: String, user: String, tags: List<String>) {
        world.edit {
            it.copy(
                virtualHosts = listOf(D.virtualHost(vhost)),
                users = listOf(D.user(user, tags = tags)),
                permissions = listOf(D.permission(vhost, user)),
            )
        }
    }

    @Given("a definitions file with a federation upstream and a static policy for federation")
    fun aFileWithFederation() {
        world.edit {
            it.copy(
                otherParameters = it.otherParameters + D.parameter("federation-upstream", "up1"),
                policies = it.policies + D.policy("fed", "^fed\\.", D.args("federation-upstream-set" to "all")),
            )
        }
    }

    @Given("the 4.x connection {string} is the held connection")
    fun the4xConnectionIsHeld(name: String) {
        // The analysis is a pure function of the document: the held 4.x connection plays no part. Nothing to prepare.
        assertTrue(name.isNotBlank())
    }

    // ---- Given: users (18) ----

    @Given("the export contains the user {string} with the default password hash")
    fun theExportContainsUser(name: String) = addUser(D.user(name, tags = listOf("administrator")))

    @Given("the export contains a user whose hashing algorithm is {string}")
    fun theExportContainsUserWithAlgorithm(algorithm: String) = addUser(D.user("alg-user", algorithm = algorithm))

    @Given("the export contains a user with a hash and no hashing algorithm")
    fun theExportContainsUserWithoutAlgorithm() = addUser(D.user("no-alg-user", algorithm = null))

    @Given("the export contains a user with no password hash")
    fun theExportContainsUserWithoutHash() = addUser(D.user("no-hash-user", hash = null, algorithm = null))

    @Given("the export contains a user with the tag {string}")
    fun theExportContainsUserWithTag(tag: String) = addUser(D.user("tagged-user", tags = listOf(tag)))

    private fun addUser(user: dev.caerbannog.core.migrate3x.UserDefinition) {
        world.lastUser = user.name
        world.edit { it.copy(users = it.users + user) }
    }

    // ---- Given: reports ----

    @Given("a live report with a mirroring policy finding and a message-loss blocker")
    fun aLiveReportWithBoth() {
        // The queue sits in a virtual host that sorts after the policy's: only the message-loss rule can put it first.
        world.edit {
            it.copy(
                policies = listOf(D.policy("ha-all", ".*", D.args("ha-mode" to "all"), vhost = "/")),
                queues = listOf(D.classicQueue("zeta", vhost = "z")),
            )
        }
        world.live = D.liveFacts(indexVersions = mapOf(QueueRef("z", "zeta") to ClassicIndexVersion.V1))
    }

    @Given("a message-loss blocker in the report")
    fun aMessageLossBlockerInTheReport() {
        world.edit { it.copy(queues = listOf(D.classicQueue("orders"))) }
        world.live = D.liveFacts(indexVersions = mapOf(QueueRef("/", "orders") to ClassicIndexVersion.V1))
        world.report = CompatibilityAnalyzer.analyze(world.document!!, world.live)
        val f = one("classic-index-v1", "orders")
        assertEquals(Severity.Blocker, f.severity)
    }

    @Given("the report for {string} is shown")
    fun theReportIsShown(connection: String) {
        if (Migrate3xScreenWorld.active) return Migrate3xScreenWorld.reportForFileIsShown(connection) // M8-06a: the same words on the screen
        val doc = (DefinitionsParser.parse(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)) as? DefinitionsParseResult.Parsed)?.document ?: fail("fixture not parsed")
        world.live = D.liveFacts(connectionName = connection, deprecatedFeatures = setOf("classic_queue_mirroring"))
        world.report = CompatibilityAnalyzer.analyze(doc, world.live)
    }

    // ---- When ----

    /** M8-02 rebinds this step to the live fetch. Until then: the hand-made document (or an empty export) plus the fake live facts. */
    @When("I analyse {string}")
    fun iAnalyseTheConnection(connection: String) {
        val live = world.live ?: fail("no connection named $connection was set up")
        assertEquals(connection, live.connectionName)
        val doc: DefinitionsDocument = world.document ?: D.document()
        world.report = CompatibilityAnalyzer.analyze(doc, live)
    }

    @When("I open the report")
    fun iOpenTheReport() {
        world.report = CompatibilityAnalyzer.analyze(world.document ?: D.document(), world.live)
    }

    @When("I open its details")
    fun iOpenItsDetails() {
        assertNotNull(world.lastFinding ?: report().findings.firstOrNull(), "there is no finding to open")
        world.lastFinding = world.lastFinding ?: report().findings.first()
    }

    @When("I export the report")
    fun iExportTheReport() {
        if (Migrate3xScreenWorld.active) return Migrate3xScreenWorld.exportReport() // M8-06a
        world.exportedJson = CompatibilityReportJson.encode(report())
    }

    // ---- Then: the report as a whole ----

    @Then("I see a compatibility report for {string}")
    fun iSeeAReport(vhost: String) {
        val r = report()
        assertEquals(9, r.summary.topologyItems, "3 queues + 2 exchanges + 4 bindings")
        assertTrue(r.findings.filter { it.kind == ItemKind.Queue }.all { it.vhost == vhost }, "queue findings should name the virtual host $vhost")
    }

    @Then("the report says how many items are compatible as they are and how many need a decision")
    fun theReportSaysHowMany() {
        val s = report().summary
        assertEquals(s.topologyItems, s.compatibleItems + s.needDecision, "compatible + need a decision = all items")
        assertEquals(2, s.needDecision, "the two classic queues need a decision, the quorum queue does not")
        assertEquals("${s.compatibleItems} of ${s.topologyItems} items are compatible as they are, ${s.needDecision} need a decision", CompatibilityReportText.render(report()).lines().first())
    }

    @Then("every finding shows the severity, the item, a reason and a proposed 4.x equivalent")
    fun everyFindingIsComplete() {
        val r = report()
        assertTrue(r.findings.isNotEmpty(), "the report has no finding")
        r.findings.forEach {
            assertTrue(it.message.isNotBlank() && it.why.isNotBlank(), "finding ${it.id} lacks a message or a reason")
            assertTrue(it.rule.isNotBlank() && it.id.isNotBlank(), "finding ${it.id} lacks a rule id")
            if (it.severity == Severity.Blocker) assertTrue(it.proposal != Proposal.None || it.alternatives.isNotEmpty(), "blocker ${it.id} proposes nothing")
        }
    }

    @Then("the severity is one of blocker, warning or info")
    fun theSeverityIsOneOf() {
        val root = Json.parseToJsonElement(CompatibilityReportJson.encode(report())).jsonObject
        val values = (root["findings"] as? JsonArray).orEmpty().map { (it.jsonObject["severity"] as? JsonPrimitive)?.content }.toSet()
        assertTrue(values.isNotEmpty() && values.all { it in setOf("blocker", "warning", "info") }, "severities in the JSON: $values")
    }

    // ---- Then: policies ----

    @Then("^there is a blocker for \"([^\"]+)\" that (.+)$")
    fun thereIsABlockerForThat(name: String, phrase: String) {
        val blockers = report().findings.filter { it.name == name && it.severity == Severity.Blocker }
        assertTrue(blockers.isNotEmpty(), "no blocker for \"$name\"; findings: ${report().findings.map { "${it.rule}:${it.name}" }}")
        val hit = blockers.firstOrNull { it.text().contains(phrase, ignoreCase = true) }
        assertNotNull(hit, "no blocker for \"$name\" says \"$phrase\"; blocker texts: ${blockers.map { it.text() }}")
        world.lastFinding = hit
    }

    @Then("the proposal is to drop the mirroring keys and to move the matching queues to quorum queues")
    fun theProposalIsToDropMirroringKeys() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertEquals(Proposal.DropPolicyKeys(listOf("ha-mode", "ha-sync-mode")), f.proposal)
        assertTrue(f.text().contains("quorum queues", ignoreCase = true), "the text should say to move the matching queues to quorum queues: ${f.text()}")
    }

    @Then("there is a blocker for that policy")
    fun thereIsABlockerForThatPolicy() {
        val name = world.lastPolicy ?: fail("no policy")
        assertTrue(report().findings.any { it.name == name && it.kind == ItemKind.Policy && it.severity == Severity.Blocker }, "no blocker for the policy $name; findings: ${report().findings.map { "${it.rule}:${it.name}" }}")
    }

    @Then("the report says mirroring is in use on this broker")
    fun theReportSaysMirroringInUse() {
        assertEquals(Severity.Warning, one("mirroring-in-use").severity)
    }

    // ---- Then: queues ----

    @Then("the report proposes to declare {string} as a quorum queue")
    fun theReportProposesQuorum(name: String) {
        assertEquals(Proposal.ConvertQueueType(QueueKind.Quorum, Acknowledgement.OrderingByPriorityMayChange), one("classic-not-replicated", name).proposal)
    }

    @Then("the report says classic queues are no longer replicated on 4.x")
    fun theReportSaysNoLongerReplicated() {
        assertTrue(report().findings.any { it.rule == "classic-not-replicated" && it.text().contains("no longer replicated on 4.x", ignoreCase = true) }, "no finding says classic queues are no longer replicated on 4.x")
    }

    @Then("{string} is treated as a classic queue")
    fun isTreatedAsClassic(name: String) {
        one("classic-not-replicated", name)
        assertTrue(findings("queue-type-unknown", name).isEmpty(), "a queue without a type must not be an unknown type")
    }

    @Then("there is a blocker for converting {string} to a quorum queue about {}")
    fun thereIsAConversionBlocker(name: String, settingText: String) {
        val (argument, _) = setting(settingText)
        val f = findings("quorum-rejected-setting", name).singleOrNull { it.about == listOf(argument) } ?: fail("no conversion blocker about $argument for $name; got ${findings("quorum-rejected-setting", name).map { it.about }}")
        assertEquals(listOf(Severity.Blocker, AppliesWhen.ConvertedToQuorum), listOf(f.severity, f.appliesWhen))
        world.lastFinding = f
    }

    @Then("the proposal for that blocker is to remove {} when the queue is converted")
    fun theProposalIsToRemove(settingText: String) {
        val (argument, _) = setting(settingText)
        val f = world.lastFinding ?: fail("no finding selected")
        assertEquals(if (argument == "auto_delete") Proposal.ClearAutoDelete else Proposal.RemoveArgument(argument), f.proposal)
    }

    @Then("the report says an import of the raw file would skip it without an error")
    fun theReportSaysImportSkips() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.text().contains("skip", ignoreCase = true) && f.text().contains("without an error", ignoreCase = true), "the finding should say a raw import skips it without an error: ${f.text()}")
    }

    @Then("converting {string} to a quorum queue keeps {}")
    fun convertingKeeps(name: String, settingText: String) {
        val (argument, _) = setting(settingText)
        val f = one("classic-not-replicated", name)
        assertEquals(Proposal.ConvertQueueType(QueueKind.Quorum, Acknowledgement.OrderingByPriorityMayChange), f.proposal)
        val rejected = report().findings.filter { it.name == name && it.appliesWhen == AppliesWhen.ConvertedToQuorum && it.severity == Severity.Blocker }
        assertTrue(rejected.isEmpty(), "converting $name must not be blocked, but ${rejected.map { it.rule + it.about }}; $argument must be kept")
        assertTrue(report().findings.none { it.name == name && it.proposal == Proposal.RemoveArgument(argument) }, "$argument must not be removed")
    }

    @Then("there is a warning for {string} that a quorum queue on 4.x orders messages by priority")
    fun theOrderingWarning(name: String) {
        val f = one("classic-to-quorum-ordering", name)
        assertEquals(Severity.Warning, f.severity)
        assertTrue(f.text().contains("quorum queue", ignoreCase = true) && f.text().contains("orders messages by priority", ignoreCase = true), f.text())
    }

    @Then("the warning says messages that carry a priority may be delivered in a different order than in a classic queue")
    fun theWarningSaysDifferentOrder() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.text().contains("may be delivered in a different order than in a classic queue", ignoreCase = true), f.text())
    }

    @Then("the default proposal for {string} is to keep it a classic queue and to say it is not replicated on 4.x")
    fun theDefaultIsKeepClassic(name: String) {
        val f = one("classic-not-replicated", name)
        assertEquals(Proposal.KeepClassic, f.proposal)
        assertTrue(f.text().contains("not replicated", ignoreCase = true), f.text())
    }

    @Then("converting to a quorum queue is offered as an alternative with the ordering warning")
    fun theQuorumAlternative() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.alternatives.any { it is Proposal.ConvertQueueType && it.target == QueueKind.Quorum }, "alternatives: ${f.alternatives}")
        assertEquals(1, findings("classic-to-quorum-ordering", f.name).size, "the ordering warning is shown for the queue")
    }

    @Then("accepting the quorum alternative needs the tick {string}")
    fun theTick(tick: String) {
        val f = world.lastFinding ?: fail("no finding selected")
        val alt = f.alternatives.filterIsInstance<Proposal.ConvertQueueType>().single()
        assertEquals(Acknowledgement.OrderingByPriorityMayChange, alt.acknowledgement)
        val json = Json.parseToJsonElement(CompatibilityReportJson.encode(report())).jsonObject
        val encoded = json["findings"]!!.jsonArray.map { it.jsonObject }.single { (it["id"] as JsonPrimitive).content == f.id }["alternatives"]!!.jsonArray
            .map { it.jsonObject }.single { (it["type"] as JsonPrimitive).content == "convertQueueType" }
        assertEquals(tick, (encoded["acknowledgement"] as JsonPrimitive).content)
    }

    @Then("the report names redelivery, the delivery limit and exclusive consumers as {string} differences")
    fun theUnmeasuredDifferences(label: String) {
        val f = one("classic-to-quorum-unmeasured")
        assertFalse(f.verified, "the finding must be flagged unverified")
        listOf("redelivery", "delivery limit", "exclusive consumers").forEach { assertTrue(f.text().contains(it, ignoreCase = true), "the finding should name $it: ${f.text()}") }
        assertTrue(CompatibilityReportText.render(report()).contains(label, ignoreCase = true), "the text rendering should show \"$label\"")
    }

    // ---- Then: message loss ----

    @Then("there is a blocker with severity {string} for {string}")
    fun theMessageLossBlocker(severityLabel: String, name: String) {
        assertEquals("message loss", severityLabel)
        val f = one("classic-index-v1", name)
        assertEquals(listOf(Severity.Blocker, FindingClass.MessageLoss), listOf(f.severity, f.findingClass))
    }

    @Then("it says RabbitMQ 4.3 and later cannot read this queue index and an in-place upgrade would silently empty the queue")
    fun itSaysCannotRead() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.text().contains("RabbitMQ 4.3 and later cannot read it", ignoreCase = true), f.text())
        assertTrue(f.text().contains("in-place upgrade would silently empty this queue", ignoreCase = true), f.text())
    }

    @Then("it offers three fixes: set the policy {string} to 2 and restart the node, upgrade through 4.0 to 4.2 first, or migrate to a new 4.x broker and move the messages with a Shovel")
    fun itOffersThreeFixes(policy: String) {
        val f = world.lastFinding ?: fail("no finding selected")
        val texts = manualTexts(f)
        assertEquals(3, texts.size, "three manual fixes: $texts")
        assertTrue(texts.any { it.contains(policy) && it.contains("restart", ignoreCase = true) }, "set the policy $policy to 2 and restart: $texts")
        assertTrue(texts.any { it.contains("4.0") && it.contains("4.2") }, "upgrade through 4.0 to 4.2: $texts")
        assertTrue(texts.any { it.contains("new 4.x broker", ignoreCase = true) && it.contains("Shovel") }, "a new 4.x broker and a Shovel: $texts")
    }

    @Then("it recommends the new broker with the Shovel as the safest way")
    fun itRecommendsNewBroker() {
        val f = world.lastFinding ?: fail("no finding selected")
        val recommended = (listOf(f.proposal) + f.alternatives).filterIsInstance<Proposal.Manual>().filter { it.recommended }
        assertEquals(1, recommended.size, "exactly one recommended proposal")
        assertTrue(recommended.single().text.contains("Shovel") && recommended.single().text.contains("new 4.x broker", ignoreCase = true), recommended.single().text)
    }

    @Then("there is no message-loss finding for {string}")
    fun noMessageLossFinding(name: String) {
        assertTrue(report().findings.any { it.rule == "clients-global-qos" }, "the analyzer produced no report")
        assertTrue(report().findings.none { it.findingClass == FindingClass.MessageLoss && it.name == name }, "unexpected message-loss finding for $name")
    }

    @Then("the message-loss blocker is the first item")
    fun theMessageLossIsFirst() {
        val first = report().findings.firstOrNull() ?: fail("the report is empty")
        assertEquals(listOf(FindingClass.MessageLoss, Severity.Blocker), listOf(first.findingClass, first.severity))
        assertTrue(report().findings.any { it.rule == "ha-policy-key" }, "the report also holds the mirroring policy finding")
        world.lastFinding = first
    }

    @Then("the report recommends a new 4.x broker with topology migration and a Shovel")
    fun theReportRecommendsNewBroker() {
        val first = world.lastFinding ?: fail("no finding selected")
        val rec = (first.proposal as? Proposal.Manual) ?: fail("the default proposal is not a Manual step: ${first.proposal}")
        assertTrue(rec.recommended && rec.text.contains("new 4.x broker", ignoreCase = true) && rec.text.contains("topology", ignoreCase = true) && rec.text.contains("Shovel"), rec.text)
    }

    @Then("the policy fix and the upgrade through 4.0 to 4.2 are shown as secondary options done by hand")
    fun theSecondaryOptions() {
        val first = world.lastFinding ?: fail("no finding selected")
        assertEquals(2, first.alternatives.size)
        first.alternatives.forEach { assertTrue(it is Proposal.Manual && !it.recommended, "secondary and by hand: $it") }
    }

    @Then("the report never offers an in-place upgrade as a safe option")
    fun neverInPlace() {
        report().findings.flatMap { manualTexts(it) }.forEach {
            if (it.contains("in place", ignoreCase = true) || it.contains("in-place", ignoreCase = true)) {
                assertTrue(it.contains("not", ignoreCase = true) || it.contains("never", ignoreCase = true), "an in-place upgrade may only appear in a warning: $it")
            }
        }
        assertTrue(report().findings.any { it.findingClass == FindingClass.MessageLoss }, "the report has no message-loss finding to check")
    }

    @Then("there is a message-loss warning for {string}")
    fun messageLossWarning(name: String) {
        val f = one("classic-index-v1", name)
        assertEquals(listOf(Severity.Warning, FindingClass.MessageLoss), listOf(f.severity, f.findingClass))
    }

    @Then("there is no message-loss warning for {string}")
    fun noMessageLossWarning(name: String) = noMessageLossFinding(name)

    @Then("the warning says the file does not carry the index version")
    fun theWarningSaysFileLacksVersion() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.text().contains("the file does not carry the index version", ignoreCase = true), f.text())
    }

    @Then("it says the management API does not show the loss after the upgrade")
    fun itSaysManagementApiDoesNotShow() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.text().contains("management API", ignoreCase = true), f.text())
    }

    @Then("it says only the broker log shows it")
    fun itSaysBrokerLog() {
        val f = world.lastFinding ?: fail("no finding selected")
        assertTrue(f.why.contains("broker log", ignoreCase = true), f.why)
    }

    // ---- Then: client checks, operator policy, compatible items ----

    @Then("the report lists \"check your clients\" items for global QoS, transient queues and AMQP 1.0 address v1")
    fun theReportListsClientChecks() {
        listOf("clients-global-qos", "clients-transient-queues", "clients-amqp-address-v1").forEach {
            val f = one(it)
            assertEquals(listOf(Severity.Info, FindingClass.ClientCheck), listOf(f.severity, f.findingClass))
            assertTrue(f.text().contains("check your clients", ignoreCase = true), f.text())
        }
    }

    @Then("the operator policy is reported and its keys are checked like a policy")
    fun theOperatorPolicyIsChecked() {
        val f = one("operator-policy-key-rejected", "op-q")
        assertEquals(listOf(ItemKind.OperatorPolicy, listOf("queue-mode")), listOf(f.kind, f.about))
    }

    @Then("there is no blocker and no warning")
    fun noBlockerNoWarning() {
        val r = report()
        assertTrue(r.findings.any { it.rule == "clients-global-qos" }, "the analyzer produced no report")
        assertTrue(r.findings.none { it.severity != Severity.Info }, "unexpected: ${r.findings.filter { it.severity != Severity.Info }.map { it.rule }}")
    }

    @Then("the report says all {int} items are compatible as they are")
    fun allItemsCompatible(count: Int) {
        val s = report().summary
        assertEquals(listOf(count, count, 0), listOf(s.topologyItems, s.compatibleItems, s.needDecision))
        assertEquals("$count of $count items are compatible as they are, 0 need a decision", CompatibilityReportText.render(report()).lines().first())
    }

    // ---- Then: access and parameters ----

    @Then("the report proposes to declare the virtual host, the user and its permissions on the 4.x broker")
    fun theReportProposesAccess() {
        assertTrue(report().findings.any { it.proposal is Proposal.DeclareVirtualHost }, "no DeclareVirtualHost proposal")
        assertTrue(report().findings.any { it.proposal is Proposal.DeclareUser }, "no DeclareUser proposal")
        assertTrue(report().findings.any { it.proposal is Proposal.SetPermissions }, "no SetPermissions proposal")
    }

    @Then("these proposals belong to the access migration and not to a topology migration")
    fun theyAreAccess() {
        val access = report().findings.filter { it.kind in setOf(ItemKind.VirtualHost, ItemKind.User, ItemKind.Permission, ItemKind.TopicPermission) }
        assertTrue(access.isNotEmpty() && access.all { it.findingClass == FindingClass.Access }, "access findings: ${access.map { it.rule + ":" + it.findingClass }}")
        assertEquals(0, report().summary.topologyItems, "access items are not topology items")
    }

    @Then("the report lists them as not covered by the migration")
    fun theReportListsNotCovered() {
        assertEquals(FindingClass.NotCovered, one("not-covered-parameter", "up1").findingClass)
        assertEquals(FindingClass.NotCovered, one("not-covered-policy-key", "fed").findingClass)
    }

    // ---- Then: export and secrets ----

    @Then("I get a JSON file with the schema {string}")
    fun iGetAJsonFile(schema: String) {
        val root = Json.parseToJsonElement(world.exportedJson ?: fail("nothing exported")).jsonObject
        assertEquals(schema, (root["schema"] as? JsonPrimitive)?.content, "schema of the exported JSON: $root")
    }

    @Then("every finding in it has a severity, a rule id, an item and a proposal")
    fun everyExportedFindingIsComplete() {
        val findings = (Json.parseToJsonElement(world.exportedJson ?: fail("nothing exported")).jsonObject["findings"] as JsonArray)
        assertTrue(findings.isNotEmpty(), "the export has no findings")
        findings.forEach {
            val f: JsonObject = it.jsonObject
            listOf("severity", "rule", "kind", "name", "proposal").forEach { key -> assertTrue(key in f, "finding without \"$key\": $f") }
        }
    }

    @Then("it contains no password, no password hash and no shovel URI")
    fun theExportHasNoSecret() {
        val json = world.exportedJson ?: fail("nothing exported")
        listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://").forEach { assertFalse(json.contains(it), "the exported report contains $it") }
    }

    @Then("neither the password hash nor the password appears in the report, the logs or the exported report")
    fun noSecretAnywhere() {
        val r = report()
        assertTrue(r.findings.isNotEmpty(), "the report is empty")
        // Core has no logging; the forms below are every text the report can turn into.
        val forms = listOf(r.toString(), CompatibilityReportJson.encode(r), CompatibilityReportText.render(r)) + r.findings.map { it.toString() } + r.findings.flatMap { f -> (listOf(f.proposal) + f.alternatives).map { it.toString() } }
        forms.forEach {
            assertFalse(it.contains("SENTINEL-HASH"), "the password hash is readable in: $it")
            assertFalse(it.contains("SENTINEL-PASSWORD"), "the password is readable in: $it")
        }
    }

    @Then("the user {string} is listed with its tags and hashing algorithm but without its hash")
    fun theUserIsListedWithoutHash(user: String) {
        val f = one("access-declare-user", user)
        assertEquals(Proposal.DeclareUser(user, listOf("management", "policymaker"), "rabbit_password_hashing_sha256", true, null), f.proposal)
    }

    // ---- Then: access (18) ----

    @Then("I see a warning that {string} has a publicly known password")
    fun aWarningForGuest(user: String) {
        val f = one("user-guest", user)
        assertEquals(Severity.Warning, f.severity)
        assertTrue(f.text().contains("publicly known password", ignoreCase = true), f.text())
    }

    @Then("the proposal for {string} is to skip it")
    fun theProposalIsToSkip(user: String) {
        assertEquals(DefaultDecision.Skip, one("user-guest", user).defaultDecision)
    }

    @Then("accepting it asks for an explicit confirmation")
    fun acceptingAsksConfirmation() {
        val f = one("user-guest", "guest")
        assertEquals(Acknowledgement.PubliclyKnownPassword, (f.proposal as Proposal.DeclareUser).acknowledgement)
    }

    @Then("there is a blocker for that user")
    fun aBlockerForThatUser() {
        val name = world.lastUser ?: fail("no user")
        val f = one("user-hash-algorithm-unsupported", name)
        assertEquals(Severity.Blocker, f.severity)
    }

    @Then("the proposal is to set a new password on the broker outside the app")
    fun theProposalIsManualNewPassword() {
        val f = one("user-hash-algorithm-unsupported", world.lastUser ?: fail("no user"))
        val text = manualTexts(f).joinToString(" ")
        assertTrue(f.proposal is Proposal.Manual && text.contains("new password", ignoreCase = true) && text.contains("outside the app", ignoreCase = true), "proposal: ${f.proposal}")
    }

    @Then("the app offers no field to type a password")
    fun noPasswordField() {
        val name = world.lastUser ?: fail("no user")
        assertTrue(report().findings.none { it.name == name && (listOf(it.proposal) + it.alternatives).any { p -> p is Proposal.DeclareUser } }, "no DeclareUser may be offered for $name")
        assertTrue(findings("user-hash-algorithm-unsupported", name).size == 1, "the blocker must exist")
    }

    @Then("the user is reported as a blocker because without the algorithm the login would fail")
    fun theUserWithoutAlgorithmIsABlocker() {
        val f = one("user-hash-without-algorithm", world.lastUser ?: fail("no user"))
        assertEquals(Severity.Blocker, f.severity)
        assertTrue(f.text().contains("login would fail", ignoreCase = true), f.text())
    }

    @Then("the user is reported as info and the app does not invent a password")
    fun theUserWithoutHashIsInfo() {
        val f = one("user-no-password-hash", world.lastUser ?: fail("no user"))
        assertEquals(Severity.Info, f.severity)
        assertTrue(f.text().contains("invent", ignoreCase = true), f.text())
    }

    @Then("there is a warning that the migration gives this user the administrator tag on 4.x")
    fun theAdministratorWarning() {
        val f = one("user-administrator-tag", world.lastUser ?: fail("no user"))
        assertEquals(Severity.Warning, f.severity)
        assertTrue(f.text().contains("administrator tag", ignoreCase = true), f.text())
    }
}
