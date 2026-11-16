package dev.caerbannog.core.migrations.fakes

/**
 * M9-00c: THE matrices of policy keys, MEASURED on `rabbitmq:4-management` (RabbitMQ 4.4.0, Erlang 29.1.2, 2026-11-16,
 * `RealBrokerM9SpikePolicyKeysTest` measures them again and fails with the measured value on any difference). DATA only, in the shape of
 * [ManagementStatusTable]: the fake broker and `M9-08` read it and must not re-measure it.
 *
 * What was measured (details: `docs/tasks/M9-00c-spike-results.md`):
 *  - S-F1a: the policy `PUT` accepts every listed key for every `apply-to` (201), including a key that cannot take effect on that target.
 *  - S-F1b: a key takes effect only on the targets of [effect]. A policy is bound to a queue only when EVERY key in its definition is a key the
 *    queue's type honours ([effect]); one key the type ignores and the policy takes no effect at all (the queue's `policy` stays empty).
 *  - S-F1c: [wrongTypeRows].
 */
object PolicyKeyMatrix {

    enum class Target(val queueType: String?) { QUORUM("quorum"), CLASSIC("classic"), STREAM("stream"), EXCHANGE(null) }

    /** A policy key with one sample value that is valid for it (JSON text). */
    data class Key(val name: String, val sampleJson: String)

    val keys: List<Key> = listOf(
        Key("max-length", "10"),
        Key("max-length-bytes", "1000"),
        Key("overflow", "\"reject-publish\""),
        Key("overflow", "\"drop-head\""),
        Key("overflow", "\"reject-publish-dlx\""),
        Key("message-ttl", "60000"),
        Key("expires", "3600000"),
        Key("dead-letter-exchange", "\"dlx\""),
        Key("dead-letter-routing-key", "\"dead\""),
        Key("dead-letter-strategy", "\"at-least-once\""),
        Key("delivery-limit", "5"),
        Key("queue-leader-locator", "\"balanced\""),
        Key("target-group-size", "3"),
        Key("max-age", "\"7D\""),
        Key("stream-max-segment-size-bytes", "500000000"),
        Key("alternate-exchange", "\"ae\""),
    )

    /** The six values of `apply-to`. */
    val applyTos: List<String> = listOf("queues", "quorum_queues", "classic_queues", "streams", "exchanges", "all")

    /** S-F1a: the status of the policy `PUT` for every key and every `apply-to` (all of them 201). */
    const val ACCEPT_STATUS: Int = 201

    /** A key the broker does not know: the one 400 of S-F1a (the positive control of the matrix). */
    const val UNKNOWN_KEY_STATUS: Int = 400

    /** The keys that appear in `effective_policy_definition` of a queue of this type, matched ALONE by a policy that holds only that key. */
    val effect: Map<Target, Set<String>> = mapOf(
        Target.QUORUM to setOf(
            "max-length", "max-length-bytes", "overflow", "message-ttl", "expires", "dead-letter-exchange", "dead-letter-routing-key",
            "dead-letter-strategy", "delivery-limit", "queue-leader-locator", "target-group-size", "alternate-exchange",
        ),
        Target.CLASSIC to setOf(
            "max-length", "max-length-bytes", "overflow", "message-ttl", "expires", "dead-letter-exchange", "dead-letter-routing-key",
            "queue-leader-locator", "alternate-exchange",
        ),
        Target.STREAM to setOf("max-length-bytes", "queue-leader-locator", "max-age", "stream-max-segment-size-bytes", "alternate-exchange"),
        // An exchange's GET carries `policy` (set for EVERY key above) but no `effective_policy_definition` at all: the effect on an exchange
        // cannot be read back from the management API. Only `alternate-exchange` is documented to act on an exchange.
        Target.EXCHANGE to emptySet(),
    )

    /** Does [key] take effect on [target]? (an exchange: not readable, see [effect].) */
    fun takesEffect(target: Target, key: String): Boolean = key in effect.getValue(target)

    /** The `overflow` values that take effect: all three, on quorum and on classic; none on a stream. */
    val overflowValues: List<String> = listOf("drop-head", "reject-publish", "reject-publish-dlx")

    /** S-F1c: a value of the wrong type or form: status of the policy `PUT`, and whether anything is stored (a 2xx stores it). */
    data class WrongTypeRow(val id: String, val key: String, val valueJson: String, val status: Int, val stored: Boolean)

    val wrongTypeRows: List<WrongTypeRow> = listOf(
        WrongTypeRow("max-length.text", "max-length", "\"abc\"", 400, false),
        WrongTypeRow("max-length.number-as-text", "max-length", "\"10\"", 400, false),
        WrongTypeRow("max-length.negative", "max-length", "-1", 400, false),
        WrongTypeRow("max-length.decimal", "max-length", "1.5", 400, false),
        WrongTypeRow("message-ttl.text", "message-ttl", "\"abc\"", 400, false),
        WrongTypeRow("message-ttl.boolean", "message-ttl", "true", 400, false),
        WrongTypeRow("expires.zero", "expires", "0", 400, false),
        WrongTypeRow("overflow.number", "overflow", "5", 400, false),
        WrongTypeRow("overflow.unknown-value", "overflow", "\"bogus\"", 400, false),
        WrongTypeRow("max-age.text", "max-age", "\"abc\"", 400, false),
        WrongTypeRow("max-age.no-unit", "max-age", "\"7\"", 400, false),
        WrongTypeRow("max-age.lowercase-unit", "max-age", "\"7d\"", 400, false),
        WrongTypeRow("max-age.number", "max-age", "7", 500, false),
        WrongTypeRow("max-age.years", "max-age", "\"1Y\"", 201, true),
        WrongTypeRow("max-age.seconds", "max-age", "\"1000s\"", 201, true),
        WrongTypeRow("delivery-limit.text", "delivery-limit", "\"x\"", 400, false),
        WrongTypeRow("dead-letter-exchange.number", "dead-letter-exchange", "5", 400, false),
        WrongTypeRow("dead-letter-strategy.unknown-value", "dead-letter-strategy", "\"bogus\"", 400, false),
        WrongTypeRow("queue-leader-locator.unknown-value", "queue-leader-locator", "\"bogus\"", 400, false),
        WrongTypeRow("target-group-size.zero", "target-group-size", "0", 400, false),
        WrongTypeRow("stream-max-segment-size-bytes.text", "stream-max-segment-size-bytes", "\"big\"", 400, false),
        WrongTypeRow("alternate-exchange.number", "alternate-exchange", "5", 400, false),
    )
}
