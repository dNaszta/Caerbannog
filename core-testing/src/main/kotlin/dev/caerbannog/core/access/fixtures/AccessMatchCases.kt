package dev.caerbannog.core.access.fixtures

import dev.caerbannog.core.access.AccessAction
import dev.caerbannog.core.access.AccessQuestion
import dev.caerbannog.core.access.PatternKind

/** What the real broker did with the operation of a case (measured by `RealBrokerAccessMatchTest` on the pinned image). */
enum class BrokerAnswer {
    Allowed,
    Refused,

    /** `PUT /api/permissions` (or topic-permissions) answers 400 `invalid_regexp`: the pattern cannot be stored. */
    CannotBeSet,
}

/** What `AccessMatcher` must answer. [Unreadable] covers three reasons: the broker cannot store the pattern, the engine rejects it, or it is on `KnownDivergences`. */
enum class MatcherExpectation { WouldMatch, NoMatch, Unreadable }

/** The real operation the live test runs for a case. */
enum class CaseOperation {
    PublishToExchange, PublishToDefaultExchange, BasicGet, BasicConsume, QueueDeclare, ExchangeDeclare, QueueBind, StreamPublisher, StreamSubscribe, StreamCreate,

    /** MQTT publish (S-I rows): the matcher does not take MQTT topics (out of scope, brief), so these rows are listed but neither asked of the matcher nor re-run. */
    MqttPublish,

    /** `basic.consume` on a queue that is bound to a topic exchange, under a topic READ permission (new row, "read topic permission on consume"). */
    ConsumeBoundQueue,
}

data class CaseTopicRow(val exchange: String, val write: String, val read: String = ".*")

/**
 * One case of the differential table. The user has [configure], [write] and [read] on [vhost "/"], plus [topic] when set; the pattern UNDER TEST is the one the
 * case is about, the others are `.*`. [broker] is the measured answer of the real broker, [expect] what the matcher must say; they differ only for the rows
 * where the matcher must say [MatcherExpectation.Unreadable] although the broker answered (engine rejects the pattern, or `KnownDivergences`), and [reason] says why.
 */
data class AccessMatchCase(
    val id: String,
    val source: String,
    val configure: String,
    val write: String,
    val read: String,
    val topic: CaseTopicRow?,
    val question: AccessQuestion,
    val operation: CaseOperation,
    val broker: BrokerAnswer,
    val expect: MatcherExpectation,
    val reason: String = "",
    val note: String = "",
) {
    /** MQTT rows are in the table as facts only. */
    val matcherApplies: Boolean get() = operation != CaseOperation.MqttPublish

    /** The pattern text of the cell under test, for failure messages. */
    val describe: String
        get() = "$id [$source] configure=[$configure] write=[$write] read=[$read]" + (topic?.let { " topic(${it.exchange}) write=[${it.write}] read=[${it.read}]" } ?: "") +
            " ask=${question.action} name=[${question.name.visible()}] rk=${question.routingKey?.let { "[${it.visible()}]" } ?: "-"} op=$operation"

    /** The kind of the cell the case is mainly about (the first needed cell that differs from `.*`), for readable reports. */
    val testedKind: PatternKind?
        get() = when {
            topic != null && topic.write != ".*" -> PatternKind.TopicWrite
            topic != null && topic.read != ".*" -> PatternKind.TopicRead
            configure != ".*" -> PatternKind.Configure
            write != ".*" -> PatternKind.Write
            read != ".*" -> PatternKind.Read
            else -> null
        }
}

internal fun String.visible(): String = replace("\n", "\\n").replace("\t", "\\t")

/**
 * M10-04: the differential case table of the match preview. ONE source: the 137 S-I rows of the spike M10-00 are read from
 * `fixtures/m10-04/s-i-pattern-rows.tsv` (a byte copy of `data-rabbit/src/test/resources/fixtures/m10-00/s-i-pattern-rows.tsv`; `AccessMatchCasesSourceTest` in
 * data-rabbit fails when the two differ), the new rows of M10-04 are written here, with the answers the live test measured (see each row's note when the
 * broker and the engine disagreed). Used by `AccessMatcherTest` (fast) and `RealBrokerAccessMatchTest` (live).
 */
object AccessMatchCases {
    const val SPIKE_RESOURCE: String = "fixtures/m10-04/s-i-pattern-rows.tsv"
    const val VHOST: String = "/"
    const val DEFAULT_EXCHANGE: String = "amq.default"

    /** The 137 rows of the spike, in file order. */
    val spikeRows: List<AccessMatchCase> by lazy { parseSpikeRows(readSpikeText()) }

    /** The rows added by M10-04 for the constructs the spike did not measure. */
    val newRows: List<AccessMatchCase> by lazy { AccessMatchNewRows.rows() }

    val all: List<AccessMatchCase> by lazy { spikeRows + newRows }

    fun readSpikeText(): String {
        val stream = AccessMatchCases::class.java.classLoader.getResourceAsStream(SPIKE_RESOURCE) ?: error("missing fixture $SPIKE_RESOURCE")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Turns the S-I table (columns `permission, pattern, name, operation, outcome`, header first) into cases. A row it does not know fails loudly. */
    fun parseSpikeRows(text: String): List<AccessMatchCase> =
        text.lines().drop(1).filter { it.isNotEmpty() }.mapIndexed { index, line ->
            val cells = line.split('\t')
            require(cells.size == 5) { "S-I row ${index + 1} has ${cells.size} cells: $line" }
            spikeCase("SI-%03d".format(index + 1), cells[0], cells[1], cells[2], cells[3], cells[4])
        }

    private fun spikeCase(id: String, permission: String, pattern: String, name: String, operation: String, outcome: String): AccessMatchCase {
        val broker = when (outcome) {
            "allowed" -> BrokerAnswer.Allowed
            "refused" -> BrokerAnswer.Refused
            else -> error("$id: unknown outcome $outcome")
        }
        val expect = if (broker == BrokerAnswer.Allowed) MatcherExpectation.WouldMatch else MatcherExpectation.NoMatch
        fun case(
            question: AccessQuestion, op: CaseOperation, configure: String = ".*", write: String = ".*", read: String = ".*", topic: CaseTopicRow? = null,
        ) = AccessMatchCase(id, "S-I", configure, write, read, topic, question, op, broker, expect, note = "$permission | $operation")
        val publish = AccessAction.PublishToExchange
        return when {
            permission == "write" && operation == "basic.publish to exchange" -> case(AccessQuestion(publish, VHOST, name), CaseOperation.PublishToExchange, write = pattern)
            permission == "write" && operation == "basic.publish to \"\"" ->
                case(AccessQuestion(publish, VHOST, ""), CaseOperation.PublishToDefaultExchange, write = pattern) // routing key "queue": the default exchange is checked as amq.default
            permission == "read" && operation == "basic.get on queue" -> case(AccessQuestion(AccessAction.ReadFromQueue, VHOST, name), CaseOperation.BasicGet, read = pattern)
            permission == "read" && operation == "basic.consume on queue" -> case(AccessQuestion(AccessAction.ReadFromQueue, VHOST, name), CaseOperation.BasicConsume, read = pattern)
            permission == "configure" && operation == "queue.declare" -> case(AccessQuestion(AccessAction.Configure, VHOST, name), CaseOperation.QueueDeclare, configure = pattern)
            permission == "configure" && operation == "exchange.declare" -> case(AccessQuestion(AccessAction.Configure, VHOST, name), CaseOperation.ExchangeDeclare, configure = pattern)
            permission == "configure" && operation == "stream create" -> case(AccessQuestion(AccessAction.Configure, VHOST, name), CaseOperation.StreamCreate, configure = pattern)
            permission == "write+read" && operation == "queue.bind" -> {
                val match = Regex("""write=\[(.*)] read=\[(.*)]""").matchEntire(pattern) ?: error("$id: bind pattern $pattern")
                case(AccessQuestion(AccessAction.BindQueue(exchange = "ex"), VHOST, "queue", "k"), CaseOperation.QueueBind, write = match.groupValues[1], read = match.groupValues[2])
            }
            permission == "topic write" -> {
                val match = Regex("""amq\.topic rk=(.*)""").matchEntire(name) ?: error("$id: topic name $name")
                val topic = if (pattern == "(no topic permission)") null else CaseTopicRow("amq.topic", write = pattern)
                case(AccessQuestion(publish, VHOST, "amq.topic", match.groupValues[1]), CaseOperation.PublishToExchange, topic = topic)
            }
            permission == "topic write (MQTT)" -> case(AccessQuestion(publish, VHOST, "amq.topic", name.removePrefix("mqtt topic ")), CaseOperation.MqttPublish, topic = CaseTopicRow("amq.topic", write = pattern))
            permission == "write" && operation == "stream declare publisher" -> case(AccessQuestion(AccessAction.PublishToStream, VHOST, name), CaseOperation.StreamPublisher, write = pattern)
            permission == "read" && operation == "stream subscribe" -> case(AccessQuestion(AccessAction.ReadFromStream, VHOST, name), CaseOperation.StreamSubscribe, read = pattern)
            else -> error("$id: S-I row not understood: $permission | $pattern | $name | $operation")
        }
    }
}
