package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.fakes.DeclareEquivalenceTable
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.streams.StreamManagementResult
import dev.caerbannog.core.streams.StreamManager
import dev.caerbannog.core.streams.StreamSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-14c step 2b: the stream rows of the measured `DeclareEquivalenceTable.streamAnswers` that the stream manager answers, replayed on the honest
 * `FakeManagementBroker.streamManager()`. The live twin is `RealBrokerM9SpikeDeclareEquivalenceTest` (S-G3 and the M9-14c rows, through the REAL
 * `RabbitMqStreamManager`): the fake is never ahead of the broker. Every row id the table holds for the manager (`stream.create.*`,
 * `stream.arguments.*`) must be replayed here, so a row nobody replays fails. A repeat that is refused never alters the stream (asserted on all of them).
 */
class DeclareEquivalenceStreamRowsTest {

    private val broker = FakeManagementBroker()
    private val manager: StreamManager = broker.streamManager()
    private val replayed = mutableSetOf<String>()

    private fun answer(r: StreamManagementResult) = when (r) {
        StreamManagementResult.Success -> "Success"
        StreamManagementResult.NotFound -> "NotFound"
        is StreamManagementResult.Refused -> "Refused " + r.reason
        is StreamManagementResult.Failed -> "Failed"
    }

    private fun create(name: String, settings: StreamSettings = StreamSettings.NONE) = runBlocking { manager.createStream("/", name, settings) }
    private fun arguments(name: String): JsonObject = broker.queueArguments("/", name)!!

    private fun check(id: String, outcome: StreamManagementResult) {
        replayed += id
        val row = DeclareEquivalenceTable.streamAnswers.single { it.id == id }
        val text = answer(outcome)
        assertEquals(row.result, text.substringBefore(' '), "$id: table says ${row.result}, the fake answered $text")
        row.detail?.let { assertTrue(it in text, "$id: table expects '$it', the fake said: $text") }
    }

    private val sevenDays = StreamSettings(maxAge = "7D", leaderLocator = "balanced")

    private fun repeat(id: String, repeated: StreamSettings) {
        create("a", sevenDays)
        val before = arguments("a")
        check(id, create("a", repeated))
        assertEquals(before, arguments("a"), "$id: a repeat must never alter the stream")
    }

    @Test
    fun `the plain rows still hold, new stream, the same again, and a name that is taken`() {
        check("stream.create.new", create("s"))
        check("stream.create.again", create("s"))
        assertEquals(Json.parseToJsonElement("""{"x-queue-leader-locator":"balanced","x-queue-type":"stream"}"""), arguments("s"))
        replayed += "stream.arguments.after-create"
        broker.addQueue("/", "quorum-one", type = "quorum")
        check("stream.create.on-quorum-queue", create("quorum-one"))
        broker.addQueue("/", "classic-one", type = "classic")
        check("stream.create.on-classic-queue", create("classic-one"))
        broker.addExchange("/", "xchg")
        check("stream.create.on-exchange-name", create("xchg"))
        assertEquals("quorum", (broker.queueArguments("/", "quorum-one")!!["x-queue-type"] as JsonPrimitive).content, "the quorum queue stays one")
    }

    @Test
    fun `a stream made with settings shows them as arguments, text as written, and the default locator`() {
        assertEquals(StreamManagementResult.Success, create("a", StreamSettings(maxAge = "7D")))
        replayed += "stream.arguments.after-create-with-settings"
        val row = DeclareEquivalenceTable.streamAnswers.single { it.id == "stream.arguments.after-create-with-settings" }
        assertEquals("Arguments", row.result)
        assertEquals(Json.parseToJsonElement(row.detail!!), arguments("a"))
    }

    @Test
    fun `the four settings are stored with their types, numbers as numbers`() {
        create("a", StreamSettings(maxAge = "90m", maxLengthBytes = 1_000_000_000, segmentSizeBytes = 100_000_000, leaderLocator = "client-local"))
        assertEquals(
            Json.parseToJsonElement("""{"x-max-age":"90m","x-max-length-bytes":1000000000,"x-stream-max-segment-size-bytes":100000000,"x-queue-leader-locator":"client-local","x-queue-type":"stream"}"""),
            arguments("a"),
        )
    }

    @Test
    fun `again-same - the same settings, balanced included, are a no-op`() = repeat("stream.create.again-same", sevenDays)

    @Test
    fun `again-subset-locator - only the retention, the held balanced is the default, is a no-op`() = repeat("stream.create.again-subset-locator", StreamSettings(maxAge = "7D"))

    @Test
    fun `again-other-retention is refused and changes nothing`() = repeat("stream.create.again-other-retention", StreamSettings(maxAge = "1D", leaderLocator = "balanced"))

    @Test
    fun `again-other-locator is refused and changes nothing`() = repeat("stream.create.again-other-locator", StreamSettings(maxAge = "7D", leaderLocator = "client-local"))

    @Test
    fun `again-no-settings on a stream that holds a retention is refused and changes nothing`() = repeat("stream.create.again-no-settings", StreamSettings.NONE)

    @Test
    fun `again-superset is refused and changes nothing`() = repeat("stream.create.again-superset", StreamSettings(maxAge = "7D", maxLengthBytes = 1_000_000_000, leaderLocator = "balanced"))

    @Test
    fun `a plain stream created again with no settings is a success, a plain stream created with settings is refused`() {
        create("p")
        assertEquals(StreamManagementResult.Success, create("p"))
        val result = create("p", StreamSettings(maxAge = "7D"))
        assertTrue(result is StreamManagementResult.Refused, "$result")
        assertEquals(Json.parseToJsonElement("""{"x-queue-leader-locator":"balanced","x-queue-type":"stream"}"""), arguments("p"))
    }

    @Test
    fun `a stream manager call without settings behaves exactly as with NONE`() {
        assertEquals(StreamManagementResult.Success, runBlocking { manager.createStream("/", "s") })
        assertEquals(arguments("s"), Json.parseToJsonElement("""{"x-queue-leader-locator":"balanced","x-queue-type":"stream"}"""))
    }

    @Test
    fun `every manager row of the table is replayed by this class`() {
        // the rows that only the queue PUT answers are replayed by DeclareEquivalenceRowsTest and the live spike
        val managerRows = DeclareEquivalenceTable.streamAnswers.map { it.id }.filter { it.startsWith("stream.") }.toSet()
        // run every test body once more, here, so that `replayed` is full
        `the plain rows still hold, new stream, the same again, and a name that is taken`()
        `a stream made with settings shows them as arguments, text as written, and the default locator`()
        for ((id, settings) in listOf(
            "stream.create.again-same" to sevenDays,
            "stream.create.again-subset-locator" to StreamSettings(maxAge = "7D"),
            "stream.create.again-other-retention" to StreamSettings(maxAge = "1D", leaderLocator = "balanced"),
            "stream.create.again-other-locator" to StreamSettings(maxAge = "7D", leaderLocator = "client-local"),
            "stream.create.again-no-settings" to StreamSettings.NONE,
            "stream.create.again-superset" to StreamSettings(maxAge = "7D", maxLengthBytes = 1, leaderLocator = "balanced"),
        )) {
            broker.addQueue("/", "n-$id", type = "stream", arguments = mapOf("x-max-age" to JsonPrimitive("7D"), "x-queue-leader-locator" to JsonPrimitive("balanced")))
            check(id, runBlocking { manager.createStream("/", "n-$id", settings) })
        }
        assertEquals(managerRows, replayed, "a row of the table that nobody replays (or a replay without a row)")
    }
}
