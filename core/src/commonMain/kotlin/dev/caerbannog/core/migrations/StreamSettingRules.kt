package dev.caerbannog.core.migrations

import dev.caerbannog.core.json.ArgumentValue

/**
 * The one place that knows which settings a `declareStream` action may carry and what a value may look like (M9-14c, Decision 2 of the brief).
 * The parser, the `StreamSettings` builder and the topology export all ask it, so a file the export writes is always a file the parser accepts.
 *
 * The shapes are ours, stricter than the broker on purpose (the broker checks nothing of `x-max-age` at create): nothing measured says a stream
 * with a zero age or a negative size works later. The broker's own bound for a segment size is not checked here, it is an apply-time refusal.
 * A refusal is a fixed text; a value is never echoed, and a key is echoed only when [echoableName] allows it.
 */
internal object StreamSettingRules {
    const val MAX_AGE: String = "x-max-age"
    const val MAX_LENGTH_BYTES: String = "x-max-length-bytes"
    const val SEGMENT_SIZE_BYTES: String = "x-stream-max-segment-size-bytes"
    const val LEADER_LOCATOR: String = "x-queue-leader-locator"

    /** The four accepted keys, in the order the export and the dry run show them. */
    val keys: List<String> = listOf(MAX_AGE, MAX_LENGTH_BYTES, SEGMENT_SIZE_BYTES, LEADER_LOCATOR)

    /** The locator values the broker accepted (S-14c-2), case-sensitive. */
    val LEADER_LOCATORS: List<String> = listOf("balanced", "client-local", "random", "least-leaders")

    // At most nine digits: a hostile 10,000-digit age never reaches the broker; "604800s" and "31536000s" fit.
    private val maxAge = Regex("[1-9][0-9]{0,8}[YMDhms]")

    /** The reason [value] is refused for [key], or null when it is accepted. */
    fun refusal(key: String, value: ArgumentValue): String? = when (key) {
        MAX_AGE ->
            if (value is ArgumentValue.Text && maxAge.matches(value.value)) null
            else "stream setting \"$MAX_AGE\" must be text such as 7D"
        MAX_LENGTH_BYTES, SEGMENT_SIZE_BYTES ->
            if (value is ArgumentValue.Whole && value.value >= 1) null
            else "stream setting \"$key\" must be a whole number above 0"
        LEADER_LOCATOR ->
            if (value is ArgumentValue.Text && value.value in LEADER_LOCATORS) null
            else "stream setting \"$LEADER_LOCATOR\" must be balanced, client-local, random or least-leaders"
        else ->
            if (echoableName(key)) "stream setting \"$key\" is not supported" else "stream setting is not supported"
    }

    /** The first refusal in the map's order (so a parser that refuses by it is deterministic), or null when every entry is accepted. */
    fun refusal(arguments: Map<String, ArgumentValue>): String? =
        arguments.entries.firstNotNullOfOrNull { (key, value) -> refusal(key, value) }
}
