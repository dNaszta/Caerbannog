package dev.caerbannog.core.streams

import dev.caerbannog.core.json.ArgumentValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * M9-14c: `StreamSettings`, the semantic settings of a stream the stream manager is asked to create (Decision 1 of the brief): pure, typed,
 * no protocol name, no `x-` name. Contract fixed here: `StreamSettings(maxAge: String?, maxLengthBytes: Long?, segmentSizeBytes: Long?, leaderLocator: String?)`
 * (every parameter defaults to null), `StreamSettings.NONE` (all null), and `StreamSettings.from(arguments: Map<String, ArgumentValue>): StreamSettings?`:
 * built from a `DeclareStream`'s arguments by `StreamSettingRules`, null when any key or value is refused (nothing is silently dropped), `NONE` for no arguments.
 * `maxAge` is TEXT as written (route T would show `604800s`: the read-back would call a correct create different, S-14c-1).
 */
class StreamSettingsTest {

    @Test
    fun `NONE has no setting at all`() {
        assertEquals(StreamSettings(maxAge = null, maxLengthBytes = null, segmentSizeBytes = null, leaderLocator = null), StreamSettings.NONE)
        assertEquals(StreamSettings(), StreamSettings.NONE)
    }

    @Test
    fun `no arguments build NONE`() {
        assertEquals(StreamSettings.NONE, StreamSettings.from(emptyMap()))
    }

    @Test
    fun `the four arguments build the four settings, the age as the text written`() {
        val built = StreamSettings.from(
            mapOf(
                "x-max-age" to ArgumentValue.Text("7D"),
                "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000),
                "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(100_000_000),
                "x-queue-leader-locator" to ArgumentValue.Text("least-leaders"),
            ),
        )
        assertEquals(StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, segmentSizeBytes = 100_000_000, leaderLocator = "least-leaders"), built)
    }

    @Test
    fun `only the declared settings are set`() {
        assertEquals(StreamSettings(maxAge = "90m"), StreamSettings.from(mapOf("x-max-age" to ArgumentValue.Text("90m"))))
        assertEquals(StreamSettings(leaderLocator = "balanced"), StreamSettings.from(mapOf("x-queue-leader-locator" to ArgumentValue.Text("balanced"))))
        assertEquals(StreamSettings(segmentSizeBytes = 5), StreamSettings.from(mapOf("x-stream-max-segment-size-bytes" to ArgumentValue.Whole(5))))
    }

    @Test
    fun `the broker's own shown form of an age is kept as it is`() {
        assertEquals("604800s", StreamSettings.from(mapOf("x-max-age" to ArgumentValue.Text("604800s")))!!.maxAge)
    }

    @Test
    fun `a value the rules refuse builds nothing, it is never dropped silently`() {
        assertNull(StreamSettings.from(mapOf("x-max-age" to ArgumentValue.Text("7d"))))
        assertNull(StreamSettings.from(mapOf("x-max-length-bytes" to ArgumentValue.Whole(0))))
        assertNull(StreamSettings.from(mapOf("x-queue-leader-locator" to ArgumentValue.Text("nearest"))))
        assertNull(StreamSettings.from(mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-max-length-bytes" to ArgumentValue.Text("1"))))
    }

    @Test
    fun `a key outside the four builds nothing, the queue type and the protocol names included`() {
        for (key in listOf("x-queue-type", "x-initial-cluster-size", "x-dead-letter-exchange", "max-age", "x-foo")) {
            assertNull(StreamSettings.from(mapOf("x-max-age" to ArgumentValue.Text("7D"), key to ArgumentValue.Text("x"))), key)
        }
    }

    @Test
    fun `building is pure, the same arguments give equal settings and NONE is one value`() {
        val args = mapOf("x-max-age" to ArgumentValue.Text("7D"))
        assertEquals(StreamSettings.from(args), StreamSettings.from(args))
        assertSame(StreamSettings.NONE, StreamSettings.NONE)
    }

    @Test
    fun `the default of createStream is NONE, so a stream manager without settings still compiles`() = kotlinx.coroutines.test.runTest {
        val seen = mutableListOf<StreamSettings>()
        val manager = object : StreamManager {
            override suspend fun createStream(vhost: String, name: String, settings: StreamSettings): StreamManagementResult {
                seen += settings
                return StreamManagementResult.Success
            }
            override suspend fun deleteStream(vhost: String, name: String): StreamManagementResult = StreamManagementResult.Success
        }
        manager.createStream("/", "s")
        manager.createStream("/", "s", StreamSettings(maxAge = "7D"))
        assertEquals(listOf(StreamSettings.NONE, StreamSettings(maxAge = "7D")), seen)
    }
}
