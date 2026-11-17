package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.StreamSettings

/**
 * The stream protocol's names for the declared settings, in a fixed order, with the numbers as plain digits (M9-14c, Decision 1).
 * These are the names the creator's raw `argument(String, String)` takes (S-14c-1); an `x-` name is accepted by the broker and then silently
 * not shown, so none is ever produced here. Only declared settings appear: the broker adds its own default locator.
 */
internal fun StreamSettings.protocolArguments(): Map<String, String> = buildMap {
    maxAge?.let { put("max-age", it) }
    maxLengthBytes?.let { put("max-length-bytes", it.toString()) }
    segmentSizeBytes?.let { put("stream-max-segment-size-bytes", it.toString()) }
    leaderLocator?.let { put("queue-leader-locator", it) }
}
