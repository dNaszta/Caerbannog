package dev.caerbannog.datarabbit

import dev.caerbannog.core.access.AccessAnswer
import dev.caerbannog.core.access.AccessAction
import dev.caerbannog.core.access.AccessMatcher
import dev.caerbannog.core.access.PatternCell
import dev.caerbannog.core.access.PatternReading
import dev.caerbannog.core.access.PermissionRow
import dev.caerbannog.core.access.TopicPermissionRow
import dev.caerbannog.core.access.UserPermissions
import dev.caerbannog.core.access.fixtures.AccessMatchCase
import dev.caerbannog.core.access.fixtures.AccessMatchCases
import dev.caerbannog.core.access.fixtures.BrokerAnswer
import dev.caerbannog.core.access.fixtures.CaseOperation
import dev.caerbannog.core.access.fixtures.MatcherExpectation
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.util.UUID

/**
 * M10-04 part A, the gate scenario "The preview agrees with a real broker for a table of cases" (`@gate-M10 @broker`; M10-09 wires it into the gate class).
 * The differential test of the match preview: for every row of `AccessMatchCases` (the 137 S-I rows of the spike plus the rows of M10-04) a throwaway user gets
 * the pattern under test as the one permission under test and `.*` for the others, a FRESH connection tries the real operation (permissions are checked per
 * connection), and two things are compared and every difference is listed with the row (permission, pattern, name, operation, broker, matcher):
 *  1. what the broker did against the broker answer recorded in the table (the table is a measured fact; a difference means the table or the broker image changed),
 *  2. what `AccessMatcher` says against the answer the table expects: allowed -> would match, refused -> no match, a pattern the broker cannot store (400
 *     `invalid_regexp`) or that the engine rejects or reads differently (`KnownDivergences`) -> pattern could not be read.
 * The operation runner is the one of the spike M10-00 (`AccessSpikeClients.kt`, shared by both classes; `RealBrokerAccessSpikeTest` is unchanged). Throwaway user and
 * objects are removed in teardown; the broker is the pinned image of every live test (`BrokerImages.CURRENT_4X`); its Erlang and PCRE versions are written to the output.
 * Test code against a throwaway container: GET, PUT and DELETE on its own user only, never a product gateway. Answers: `build/m10-04-live-answers.tsv`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealBrokerAccessMatchTest {
    private lateinit var env: AccessEnv
    private val user = "m10-04-" + UUID.randomUUID().toString().take(8)
    private val vhost = AccessMatchCases.VHOST
    private val created = mutableListOf<String>()

    @BeforeAll
    fun start() {
        env = AccessEnv("M10-04", accessBroker().also { it.start() })
        val overview = env.http.get("/api/overview", env.guest).obj
        val pcre = runCatching { env.container.execInContainer("rabbitmqctl", "eval", "re:version().") }.getOrNull()?.let { (it.stdout + it.stderr).trim().take(120) }
        println("[M10-04] image=${BrokerImages.CURRENT_4X} rabbitmq=${overview?.field("rabbitmq_version")} erlang=${overview?.field("erlang_version")} pcre(re:version)=$pcre")
        AccessFacts.note("M10-04", "image=${BrokerImages.CURRENT_4X} rabbitmq=${overview?.field("rabbitmq_version")} erlang=${overview?.field("erlang_version")} pcre(re:version)=$pcre")
        env.addUser(user, "", vhost)
        prepareObjects()
    }

    @AfterAll
    fun stop() {
        if (::env.isInitialized) {
            runCatching { env.admin("DELETE", "/api/topic-permissions/${env.encode(vhost)}/$user") }
            runCatching { env.deleteUser(user) }
            runCatching { env.container.stop() }
        }
    }

    /** Declares, as guest, what the operations act on (exchanges, queues, streams named by the table) and the one binding the consume row needs. */
    private fun prepareObjects() {
        val cases = AccessMatchCases.all.filter { it.matcherApplies }
        val exchanges = sortedSetOf<String>()
        val queues = sortedSetOf("queue")
        val streams = sortedSetOf<String>()
        for (c in cases) {
            val q = c.question
            when (c.operation) {
                CaseOperation.PublishToExchange -> if (!q.name.startsWith("amq.")) exchanges += q.name
                CaseOperation.BasicGet, CaseOperation.BasicConsume -> queues += q.name
                CaseOperation.QueueBind -> (q.action as AccessAction.BindQueue).exchange.takeIf { !it.startsWith("amq.") }?.let { exchanges += it }
                CaseOperation.StreamPublisher, CaseOperation.StreamSubscribe -> streams += q.name
                else -> Unit
            }
        }
        exchanges += "x.topic"
        val result = amqpOp(env, "guest", "guest", vhost) { ch ->
            exchanges.forEach { ch.exchangeDeclare(it, if (it == "x.topic") "topic" else "direct", true) }
            queues.forEach { ch.queueDeclare(it, true, false, false, null) }
            ch.queueBind("queue", "amq.topic", "a.b")
        }
        check(result == "allowed") { "could not prepare the objects: $result" }
        streams.forEach { check(streamOp(env, "guest", "guest") { e -> e.streamCreator().stream(it).create() } == "allowed") { "stream $it not created" } }
    }

    private class Outcome(val case: AccessMatchCase, val broker: String, val brokerRaw: String, val matcher: String)

    @Test
    fun `the preview agrees with the broker on every case of the table`() {
        val cases = AccessMatchCases.all
        assertTrue(cases.size >= 190, "the table has ${cases.size} rows, the brief wants at least 190")
        val matcher: Result<AccessMatcher> = runCatching { AccessMatcher() } // the engine of this platform; a stub that throws (step 2) makes every row fail on the matcher side only
        val outcomes = cases.filter { it.matcherApplies }.map { case ->
            val (brokerAnswer, raw) = try { tryOnBroker(case) } catch (e: Throwable) { "error" to "${e.javaClass.simpleName}: ${e.message}" }
            Outcome(case, brokerAnswer, raw, matcherAnswer(matcher, case))
        }
        writeAnswers(outcomes)
        val skipped = cases.size - outcomes.size
        println("[M10-04] rows=${cases.size} run=${outcomes.size} skipped(MQTT, the matcher takes no MQTT topic)=$skipped")
        val brokerFailures = outcomes.filter { it.broker != it.case.broker.name }.map {
            "BROKER   ${it.case.describe}: table says ${it.case.broker}, broker did ${it.broker} (${it.brokerRaw.take(120)})"
        }
        val matcherFailures = outcomes.filter { it.matcher != it.case.expect.name }.map {
            "MATCHER  ${it.case.describe}: expected ${it.case.expect}, matcher said ${it.matcher}; broker said ${it.broker}"
        }
        val failures = brokerFailures + matcherFailures
        assertTrue(failures.isEmpty(), "${failures.size} disagreement(s) of ${outcomes.size} rows:\n" + failures.take(60).joinToString("\n") + if (failures.size > 60) "\n... and ${failures.size - 60} more, all in build/m10-04-live-answers.tsv" else "")
    }

    private fun matcherAnswer(matcher: Result<AccessMatcher>, case: AccessMatchCase): String = try {
        when (matcher.getOrThrow().check(permissionsOf(case), case.question)) {
            is AccessAnswer.WouldMatch -> MatcherExpectation.WouldMatch.name
            is AccessAnswer.NoMatch -> MatcherExpectation.NoMatch.name
            is AccessAnswer.UnreadablePattern -> MatcherExpectation.Unreadable.name
            is AccessAnswer.PermissionsNotReadable -> "PermissionsNotReadable"
        }
    } catch (e: Throwable) {
        "threw ${e.javaClass.simpleName}"
    }

    private fun cell(text: String) = PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))

    private fun permissionsOf(case: AccessMatchCase) = UserPermissions(
        user, listOf(PermissionRow(vhost, cell(case.configure), cell(case.write), cell(case.read))),
        listOfNotNull(case.topic?.let { TopicPermissionRow(vhost, it.exchange, cell(it.write), cell(it.read)) }),
    )

    /** Sets the permissions of the case and runs its operation on a fresh connection. Returns the broker's answer name and the raw text. */
    private fun tryOnBroker(case: AccessMatchCase): Pair<String, String> {
        val set = env.setPermissions(user, vhost, case.configure, case.write, case.read)
        if (!set.ok) return if (set.status == 400 && "invalid_regexp" in set.body) BrokerAnswer.CannotBeSet.name to "PUT permissions ${set.status} invalid_regexp" else "error" to "PUT permissions: $set"
        env.admin("DELETE", "/api/topic-permissions/${env.encode(vhost)}/$user")
        case.topic?.let {
            val setTopic = env.setTopicPermissions(user, vhost, it.exchange, it.write, it.read)
            if (!setTopic.ok) return if (setTopic.status == 400 && "invalid_regexp" in setTopic.body) BrokerAnswer.CannotBeSet.name to "PUT topic-permissions ${setTopic.status} invalid_regexp" else "error" to "PUT topic-permissions: $setTopic"
        }
        val q = case.question
        val rk = q.routingKey ?: ""
        val raw = when (case.operation) {
            CaseOperation.PublishToExchange -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.publishConfirmed(q.name, rk) }
            CaseOperation.PublishToDefaultExchange -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.publishConfirmed("", "queue") }
            CaseOperation.BasicGet -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.basicGet(q.name, false) }
            CaseOperation.BasicConsume, CaseOperation.ConsumeBoundQueue ->
                amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.basicConsume(q.name, true, object : com.rabbitmq.client.DefaultConsumer(it) {}) }
            CaseOperation.QueueDeclare -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.queueDeclare(q.name, true, false, false, null) }.also { removeQueue(q.name) }
            CaseOperation.ExchangeDeclare -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.exchangeDeclare(q.name, "direct", false, true, null) }.also { removeExchange(q.name) }
            CaseOperation.QueueBind -> amqpOp(env, user, ACCESS_PASSWORD, vhost) { it.queueBind(q.name, (q.action as AccessAction.BindQueue).exchange, rk) }
            CaseOperation.StreamPublisher -> streamOp(env, user, ACCESS_PASSWORD) { it.publishOne(q.name) }
            CaseOperation.StreamSubscribe -> streamOp(env, user, ACCESS_PASSWORD) { it.consumeFirst(q.name) }
            CaseOperation.StreamCreate -> streamOp(env, user, ACCESS_PASSWORD) { it.streamCreator().stream(q.name).create() }.also { removeQueue(q.name) }
            CaseOperation.MqttPublish -> error("MQTT rows are not run")
        }
        // A name with a line feed cannot be declared (measured: the broker keeps `ab` for `a<LF>b`), so the publish ends in 404 NOT_FOUND. The write check comes first
        // (403 ACCESS_REFUSED otherwise), so a 404 means the permission passed.
        val permissionPassedObjectMissing = q.name.contains('\n') && "reply-code=404" in raw
        val answer = when {
            raw == "allowed" || permissionPassedObjectMissing -> BrokerAnswer.Allowed.name
            raw.startsWith("refused") -> BrokerAnswer.Refused.name
            else -> "error"
        }
        return answer to raw
    }

    private fun removeQueue(name: String) { env.admin("DELETE", "/api/queues/${env.encode(vhost)}/${env.encode(name)}") }

    private fun removeExchange(name: String) { env.admin("DELETE", "/api/exchanges/${env.encode(vhost)}/${env.encode(name)}") }

    private fun writeAnswers(outcomes: List<Outcome>) {
        val file = File("build/m10-04-live-answers.tsv")
        file.parentFile.mkdirs()
        file.writeText("id\tbroker\tmatcher\tbroker_raw\tcase\n" + outcomes.joinToString("\n") { o ->
            listOf(o.case.id, o.broker, o.matcher, AccessSecrets.sanitise(o.brokerRaw.take(100)), o.case.describe).joinToString("\t") { it.replace("\t", " ").replace("\n", "\\n") }
        } + "\n")
    }
}
