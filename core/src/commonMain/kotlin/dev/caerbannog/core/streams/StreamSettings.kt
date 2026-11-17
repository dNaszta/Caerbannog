package dev.caerbannog.core.streams

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.StreamSettingRules

/**
 * The settings a stream is created with (M9-14c). Semantic, no protocol name and no `x-` name: `data-rabbit` maps them to the stream protocol.
 * [maxAge] is text as written ("7D"); a typed duration would be shown by the broker as "604800s" and the read-back would call a correct create
 * different. A null field means "not declared": the broker's own default applies.
 */
data class StreamSettings(
    val maxAge: String? = null,
    val maxLengthBytes: Long? = null,
    val segmentSizeBytes: Long? = null,
    val leaderLocator: String? = null,
) {
    companion object {
        val NONE: StreamSettings = StreamSettings()

        /**
         * Builds the settings of a `declareStream` action's arguments, or null when any key or value is refused by [StreamSettingRules]
         * (nothing is silently dropped). No arguments give [NONE].
         */
        fun from(arguments: Map<String, ArgumentValue>): StreamSettings? {
            if (StreamSettingRules.refusal(arguments) != null) return null
            return StreamSettings(
                maxAge = (arguments[StreamSettingRules.MAX_AGE] as? ArgumentValue.Text)?.value,
                maxLengthBytes = (arguments[StreamSettingRules.MAX_LENGTH_BYTES] as? ArgumentValue.Whole)?.value,
                segmentSizeBytes = (arguments[StreamSettingRules.SEGMENT_SIZE_BYTES] as? ArgumentValue.Whole)?.value,
                leaderLocator = (arguments[StreamSettingRules.LEADER_LOCATOR] as? ArgumentValue.Text)?.value,
            )
        }
    }
}
