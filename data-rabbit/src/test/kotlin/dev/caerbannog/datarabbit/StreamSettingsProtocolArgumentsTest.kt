package dev.caerbannog.datarabbit

import dev.caerbannog.core.streams.StreamSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-14c step 2b, Decision 1: the ONE pure function that maps semantic settings to the names of the stream PROTOCOL (`core` knows no protocol name).
 * Contract fixed here: `internal fun StreamSettings.protocolArguments(): Map<String, String>` in package `dev.caerbannog.datarabbit`, insertion order
 * `max-age`, `max-length-bytes`, `stream-max-segment-size-bytes`, `queue-leader-locator`; only declared settings; numbers as plain digits; the age text
 * untouched. MEASURED (S-14c-1): an `x-` name is accepted by the broker and NOT SHOWN, so no `x-` name may ever be sent, and the typed client
 * route would turn `7D` into `604800s`.
 */
class StreamSettingsProtocolArgumentsTest {

    @Test
    fun `NONE sends nothing, the broker adds balanced itself`() {
        assertEquals(emptyMap<String, String>(), StreamSettings.NONE.protocolArguments())
    }

    @Test
    fun `each setting goes under its protocol name`() {
        assertEquals(mapOf("max-age" to "7D"), StreamSettings(maxAge = "7D").protocolArguments())
        assertEquals(mapOf("max-length-bytes" to "1000000000"), StreamSettings(maxLengthBytes = 1_000_000_000).protocolArguments())
        assertEquals(mapOf("stream-max-segment-size-bytes" to "100000000"), StreamSettings(segmentSizeBytes = 100_000_000).protocolArguments())
        assertEquals(mapOf("queue-leader-locator" to "client-local"), StreamSettings(leaderLocator = "client-local").protocolArguments())
    }

    @Test
    fun `all four, in a fixed order`() {
        val all = StreamSettings(maxAge = "7D", maxLengthBytes = 1, segmentSizeBytes = 2, leaderLocator = "random").protocolArguments()
        assertEquals(listOf("max-age", "max-length-bytes", "stream-max-segment-size-bytes", "queue-leader-locator"), all.keys.toList())
        assertEquals(listOf("7D", "1", "2", "random"), all.values.toList())
    }

    @Test
    fun `no key ever starts with x- and no key is the queue type`() {
        val all = StreamSettings(maxAge = "7D", maxLengthBytes = 1, segmentSizeBytes = 2, leaderLocator = "balanced").protocolArguments()
        assertTrue(all.keys.none { it.startsWith("x-") || it.contains("queue-type") }, "${all.keys}")
    }

    @Test
    fun `numbers are plain digits, the largest and the smallest allowed ones included`() {
        assertEquals("9223372036854775807", StreamSettings(maxLengthBytes = Long.MAX_VALUE).protocolArguments().getValue("max-length-bytes"))
        assertEquals("1", StreamSettings(segmentSizeBytes = 1).protocolArguments().getValue("stream-max-segment-size-bytes"))
        assertEquals("4000000000", StreamSettings(segmentSizeBytes = 4_000_000_000).protocolArguments().getValue("stream-max-segment-size-bytes"), "the broker's bound is its refusal, not ours")
    }

    @Test
    fun `the age text is sent as written, 7D stays 7D and 604800s stays 604800s`() {
        assertEquals("7D", StreamSettings(maxAge = "7D").protocolArguments().getValue("max-age"))
        assertEquals("604800s", StreamSettings(maxAge = "604800s").protocolArguments().getValue("max-age"))
        assertEquals("1Y", StreamSettings(maxAge = "1Y").protocolArguments().getValue("max-age"))
    }

    @Test
    fun `an undeclared setting is never sent as a default, not even the locator`() {
        val sent = StreamSettings(maxAge = "7D").protocolArguments()
        assertEquals(setOf("max-age"), sent.keys)
    }
}
