package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9-07 step 2: the new fixed texts and the guard of the note. M9-07c (owner decision O16, 2026-11-17) added ONE sentence about the broker,
 * measured by spike M9-00b (S-D1 to S-D3): the guard now allows exactly that sentence, once, and no second broker claim. Constants fixed by these tests (names for the architect to confirm):
 * `RESPONSE_STREAM_TICK`, `RESPONSE_STREAM_NAME_LABEL`, `RESPONSE_STREAM_NOTE`, `RESPONSE_LINK_LABEL`, `responseStreamOf(requestName)`.
 */
class PlannerTextsStreamTest {
    private val texts = listOf(
        PlannerTexts.RESPONSE_STREAM_TICK,
        PlannerTexts.RESPONSE_STREAM_NAME_LABEL,
        PlannerTexts.RESPONSE_STREAM_NOTE,
        PlannerTexts.RESPONSE_LINK_LABEL,
        PlannerTexts.kindDescription("Stream"),
        PlannerTexts.responseStreamOf("telemetry"),
        PlannerTexts.responseStreamOf("a b"),
    )

    @Test
    fun `Stream is a built kind, in the order of the wizard, with a description`() {
        assertEquals(listOf("Queue", "Exchange", "Binding", "Stream"), PlannerTexts.BUILT_KINDS)
        assertEquals(PlannerTexts.WIZARD_KINDS.filter { it in PlannerTexts.BUILT_KINDS }, PlannerTexts.BUILT_KINDS, "built kinds keep the wizard order")
        assertEquals("Optionally with its response stream (DLN)", PlannerTexts.kindDescription("Stream"))
        assertEquals("", PlannerTexts.kindDescription("Policy"), "the others still have none")
    }

    @Test
    fun `the words are the ones of the mockup and the owner's decision Q2`() {
        assertEquals("Also create its response stream (DLN)", PlannerTexts.RESPONSE_STREAM_TICK)
        assertEquals("Response stream (DLN) name", PlannerTexts.RESPONSE_STREAM_NAME_LABEL)
        assertEquals("response (DLN)", PlannerTexts.RESPONSE_LINK_LABEL)
        assertEquals("Response stream (DLN) of telemetry", PlannerTexts.responseStreamOf("telemetry"))
    }

    @Test
    fun `the note says what a response stream is, what ticking does and what RabbitMQ does not do - and nothing else`() {
        assertEquals(
            "A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them. RabbitMQ does not move messages into it by itself: your applications write to it.",
            PlannerTexts.RESPONSE_STREAM_NOTE,
        )
    }

    @Test
    fun `the note makes exactly one claim about RabbitMQ, the owner's sentence, and no second one`() {
        val note = PlannerTexts.RESPONSE_STREAM_NOTE
        val sentence = "RabbitMQ does not move messages into it by itself: your applications write to it."
        assertTrue(note.endsWith(sentence), "the note does not end with the owner's sentence: $note")
        assertEquals(1, note.split(sentence).size - 1, "the owner's sentence must appear exactly once: $note")
        // M9-00b measured only S-D1 to S-D3: no sentence about retention, dead letters, deleting, queues or super streams.
        for (word in listOf("retention", "dead-letter", "delete", "queue", "super")) {
            assertTrue(word !in note.lowercase(), "the note mentions \"$word\", a second broker claim nobody reviewed: $note")
        }
    }

    @Test
    fun `no text of the stream step holds markup or an unfilled placeholder`() {
        for (t in texts) {
            assertTrue(t.none { it in "<>`*" }, "markup character in \"$t\"")
            assertTrue("{" !in t && "}" !in t && "%s" !in t && "\$" !in t && "null" !in t, "unfilled placeholder in \"$t\"")
            assertTrue(t.isNotBlank())
        }
    }

    @Test
    fun `a name goes into the pair line only through the echo rule`() {
        assertEquals("Response stream (DLN) of telemetry", PlannerTexts.responseStreamOf("telemetry"))
        for (hostile in listOf("a\nb", "<b>x</b>", "x\"y", "a".repeat(65), "", ".", "..", "has space", "`x`")) {
            val line = PlannerTexts.responseStreamOf(hostile)
            assertEquals("Response stream (DLN)", line, "hostile name ${hostile.take(10)}")
        }
    }

    @Test
    fun `the stream form problems and hints are plain text`() {
        val problems = listOf(StreamForm(""), StreamForm(".."), StreamForm("a", true, responseName = ""), StreamForm("a", true, responseName = "a"))
            .flatMap { it.problems(ctx()) }.map { it.message }
        val hints = StreamForm("Orders_EU", true, responseName = "Bad_Name").hints(ctx()).map { it.message }
        assertTrue(problems.size >= 4 && hints.size == 2)
        for (t in problems + hints) {
            assertTrue(t.none { it in "<>`*" }, "markup in \"$t\"")
            assertTrue("{" !in t && "}" !in t && "null" !in t, "placeholder in \"$t\"")
        }
    }
}
