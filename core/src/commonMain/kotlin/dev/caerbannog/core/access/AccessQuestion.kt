package dev.caerbannog.core.access

/**
 * M10-04 contract: what the match preview asks. [name] is the resource asked about: the exchange for [AccessAction.PublishToExchange] (empty or `amq.default`
 * is the default exchange, which the broker checks as `amq.default`, never as the target queue; measured S-I), the queue for [AccessAction.ReadFromQueue]
 * and [AccessAction.BindQueue], the stream for the stream forms, the exchange or queue for [AccessAction.Configure]. [routingKey] null means the empty key
 * (measured by M10-04 part A). Lengths: [AccessLimits].
 */
data class AccessQuestion(val action: AccessAction, val vhost: String, val name: String, val routingKey: String? = null)

/** The action-to-kind table lives in [AccessMatcher], one line per rule with the S-I row as comment. The UI offers the first three only (PD-1). */
sealed interface AccessAction {
    data object PublishToExchange : AccessAction

    data object ReadFromQueue : AccessAction

    /** Configure (declare) a queue, exchange or stream: the configure pattern on [AccessQuestion.name]. */
    data object Configure : AccessAction

    /** `queue.bind`: write on the queue ([AccessQuestion.name]) AND read on [exchange] (measured S-I). Matcher level only (PD-1). */
    data class BindQueue(val exchange: String) : AccessAction

    /** Stream publisher: the write pattern on the stream name (same patterns, S-I). Matcher level only. */
    data object PublishToStream : AccessAction

    /** Stream subscribe: the read pattern on the stream name. Matcher level only. */
    data object ReadFromStream : AccessAction
}

object AccessLimits {
    /** The longest pattern the matcher reads; a longer one is [AccessAnswer.UnreadablePattern] (work bound). */
    const val MAX_PATTERN: Int = 256

    /** The broker's own limit for a resource name and a routing key (255 bytes); a longer one is [AccessAnswer.UnreadablePattern]. */
    const val MAX_NAME: Int = 255
}
