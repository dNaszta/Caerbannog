package dev.caerbannog.core.migrations.fakes

/**
 * M9-00c: THE table of what a repeated declare answers, MEASURED on `rabbitmq:4-management` (RabbitMQ 4.4.0, Erlang 29.1.2, 2026-11-16,
 * `RealBrokerM9SpikeDeclareEquivalenceTest` measures it again and fails with the measured value on any difference). DATA only, in the shape of
 * [ManagementStatusTable]. The bodies are those of the migration executor (`MigrationRequests`): the test sends what the product sends.
 *
 * The rule the rows show: a repeated declare is answered 204 (nothing changes) when it is equivalent, 400 `inequivalent arg` otherwise. The broker
 * compares `durable`, `auto_delete` and the ARGUMENTS THE TYPE HONOURS ([queueComparedArguments], [exchangeComparedArguments]); any other
 * argument (a made-up `x-foo`, or `x-max-priority` on a quorum queue) is ignored: another value, more or fewer of them answer 204 and the stored
 * arguments do not change. Numbers are compared with their wire type: `10` against `"10"` or `10.0` is a 400.
 */
object DeclareEquivalenceTable {

    data class Row(val id: String, val status: Int, val bodyHas: String? = null)

    private const val INEQUIVALENT = "inequivalent arg"

    private fun queueRows(type: String): List<Row> = listOf(
        Row("queue.$type.same", 204),
        Row("queue.$type.fewer-arguments", 400, INEQUIVALENT),
        Row("queue.$type.more-arguments", 400, INEQUIVALENT),
        Row("queue.$type.other-value", 400, INEQUIVALENT),
        Row("queue.$type.number-as-text", 400, INEQUIVALENT),
        Row("queue.$type.number-as-decimal", 400, INEQUIVALENT),
        Row("queue.$type.other-type", 400, INEQUIVALENT),
        Row("queue.$type.durable-false", 400, INEQUIVALENT),
        Row("queue.$type.auto-delete-true", 400, INEQUIVALENT),
        Row("queue.$type.unknown-argument-other-value", 204),
        Row("queue.$type.unknown-argument-added", 204),
        Row("queue.$type.unknown-argument-number-as-text", 204),
        Row("queue.$type.argument-of-another-type-added", 204),
    )

    val rows: List<Row> = queueRows("quorum") + queueRows("classic") + queueRows("stream") + listOf(
        // the one case where "classic" differs: no `x-queue-type` at all declares a classic queue
        Row("queue.classic.type-argument-omitted", 204),
        Row("queue.quorum.type-argument-omitted", 400, INEQUIVALENT),
        Row("queue.stream.type-argument-omitted", 400, INEQUIVALENT),
        Row("exchange.same", 204),
        Row("exchange.other-type", 400, INEQUIVALENT),
        Row("exchange.durable-false", 400, INEQUIVALENT),
        Row("exchange.auto-delete-true", 400, INEQUIVALENT),
        Row("exchange.internal-true", 400, INEQUIVALENT),
        Row("exchange.alternate-exchange-fewer", 400, INEQUIVALENT),
        Row("exchange.alternate-exchange-other-value", 400, INEQUIVALENT),
        Row("exchange.alternate-exchange-as-number", 400, INEQUIVALENT),
        Row("exchange.alternate-exchange-added", 400, INEQUIVALENT),
        Row("exchange.unknown-argument-added", 204),
        Row("exchange.unknown-argument-number-as-text", 204),
        Row("exchange.unknown-argument-number-as-decimal", 204),
    )

    private val byId = rows.associateBy { it.id }

    fun row(id: String): Row = byId[id] ?: error("no row $id in the measured table")

    fun status(id: String): Int = row(id).status

    /** Arguments (names as sent, `x-` prefix included) whose difference a repeated queue declare refuses, per `x-queue-type`. */
    val queueComparedArguments: Map<String, Set<String>> = mapOf(
        "quorum" to setOf(
            "x-message-ttl", "x-expires", "x-max-length", "x-max-length-bytes", "x-overflow", "x-dead-letter-exchange", "x-dead-letter-routing-key",
            "x-dead-letter-strategy", "x-delivery-limit", "x-queue-leader-locator", "x-quorum-initial-group-size", "x-single-active-consumer",
            "x-consumer-disconnected-timeout",
        ),
        "classic" to setOf(
            "x-message-ttl", "x-expires", "x-max-length", "x-max-length-bytes", "x-overflow", "x-dead-letter-exchange", "x-dead-letter-routing-key",
            "x-queue-leader-locator", "x-single-active-consumer", "x-max-priority", "x-queue-mode", "x-queue-version",
        ),
        "stream" to setOf(
            "x-max-length-bytes", "x-queue-leader-locator", "x-initial-cluster-size", "x-max-age", "x-stream-max-segment-size-bytes",
            "x-stream-filter-size-bytes",
        ),
    )

    /** The only argument a repeated exchange declare compares (besides type, `durable`, `auto_delete`, `internal`). */
    val exchangeComparedArguments: Set<String> = setOf("alternate-exchange")

    /**
     * S-G3: what the stream manager (`createStream`: its typed result) and the queue/exchange `PUT` (the status) answer about a name that is taken.
     * [detail] is a substring of the broker's own text (null: no claim). A stream created over the stream protocol carries the argument
     * `x-queue-leader-locator: balanced` (the stream client's default), so a `PUT` of a stream-type queue WITHOUT it is a 400.
     */
    data class StreamAnswer(val id: String, val result: String, val detail: String? = null)

    val streamAnswers: List<StreamAnswer> = listOf(
        StreamAnswer("stream.create.new", "Success"),
        StreamAnswer("stream.arguments.after-create", "Arguments", """{"x-queue-leader-locator":"balanced","x-queue-type":"stream"}"""),
        StreamAnswer("stream.create.again", "Success"),
        StreamAnswer("stream.create.on-quorum-queue", "Refused", "PRECONDITION_FAILED"),
        StreamAnswer("stream.create.on-classic-queue", "Refused", "PRECONDITION_FAILED"),
        StreamAnswer("stream.create.on-exchange-name", "Success"),
        StreamAnswer("queue.put.quorum-on-stream", "400", "inequivalent arg 'x-queue-type'"),
        StreamAnswer("queue.put.classic-on-stream", "400", "inequivalent arg 'x-queue-type'"),
        StreamAnswer("queue.put.stream-on-stream", "400", "inequivalent arg 'x-queue-leader-locator'"),
        StreamAnswer("exchange.put.on-stream-name", "201"),
        StreamAnswer("queue.put.stream-on-quorum-queue", "400", "inequivalent arg 'x-queue-type'"),
    )
}
