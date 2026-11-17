package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * M9-10b step 2: the sample values of a test message for an MQTT route. Pure rules: one slot per wildcard (1-based level), the start values of the
 * route form, the refusals with their fixed texts, and the hostile values. Texts are literal on purpose: they are the decided wording.
 */
class MqttTopicSampleTest {
    private val dot = "A level cannot contain a dot: RabbitMQ would read it as another level"

    private fun topic(filter: String, vararg values: String): String =
        assertIs<SampleResult.Topic>(MqttTopicSample.fill(filter, values.toList()), "fill($filter, ${values.toList()})").topic

    private fun problems(filter: String, vararg values: String): List<SampleProblem> =
        assertIs<SampleResult.Refused>(MqttTopicSample.fill(filter, values.toList()), "fill($filter, ${values.toList()})").problems

    private fun onlyText(filter: String, value: String): String = problems(filter, value).single().text

    // ---- slots ----

    @Test
    fun `a filter without wildcards has no slots and fill returns the filter itself`() {
        assertEquals(emptyList(), MqttTopicSample.slots("devices/d1/status"))
        assertEquals("devices/d1/status", topic("devices/d1/status"))
    }

    @Test
    fun `one plus is one slot at its 1-based level`() {
        assertEquals(listOf<SampleSlot>(SampleSlot.Plus(2)), MqttTopicSample.slots("devices/+/answer"))
        assertEquals(listOf<SampleSlot>(SampleSlot.Plus(1)), MqttTopicSample.slots("+/x"))
    }

    @Test
    fun `two pluses are two slots in order`() {
        assertEquals(listOf<SampleSlot>(SampleSlot.Plus(2), SampleSlot.Plus(4)), MqttTopicSample.slots("a/+/b/+"))
        assertEquals("a/x/b/y", topic("a/+/b/+", "x", "y"))
    }

    @Test
    fun `a hash is one slot for the rest of the topic`() {
        assertEquals(listOf<SampleSlot>(SampleSlot.Hash(2)), MqttTopicSample.slots("sensors/#"))
        assertEquals(listOf<SampleSlot>(SampleSlot.Plus(1), SampleSlot.Hash(3)), MqttTopicSample.slots("+/x/#"))
        assertEquals(listOf<SampleSlot>(SampleSlot.Hash(1)), MqttTopicSample.slots("#"))
    }

    // ---- start values ----

    @Test
    fun `a plus starts as item and a hash starts as test`() {
        assertEquals("item", MqttTopicSample.startValue(SampleSlot.Plus(2)))
        assertEquals("test", MqttTopicSample.startValue(SampleSlot.Hash(2)))
    }

    @Test
    fun `the start values give the same topic as the route form`() {
        val filters = listOf("devices/+/answer", "sensors/#", "a/+/b/+", "+", "#", "x y/+", "+/+/#", "devices/d1/status")
        for (filter in filters) {
            val expected = assertNotNull(MqttRouteForm(filter, "d").matchingTopic(), "the route form refuses \"$filter\"")
            val starts = MqttTopicSample.slots(filter).map { MqttTopicSample.startValue(it) }
            assertEquals(expected, topic(filter, *starts.toTypedArray()), "start values of \"$filter\"")
        }
    }

    // ---- values that work ----

    @Test
    fun `a plus value is one level and a hash value may hold levels, empty levels and dots`() {
        assertEquals("devices/d42/answer", topic("devices/+/answer", "d42"))
        assertEquals("sensors/room1/temp", topic("sensors/#", "room1/temp"))
        assertEquals("sensors/a//b", topic("sensors/#", "a//b"))
        assertEquals("sensors//a", topic("sensors/#", "/a"))
        assertEquals("sensors/a/", topic("sensors/#", "a/"))
        assertEquals("sensors/v1.2/x", topic("sensors/#", "v1.2/x"))
        assertEquals("test", topic("#", "test"))
    }

    @Test
    fun `spaces are allowed in a value`() {
        assertEquals("devices/d 42/answer", topic("devices/+/answer", "d 42"))
    }

    // ---- refusals, each with its text ----

    @Test
    fun `a blank plus value is refused`() {
        assertEquals("Enter a value", onlyText("devices/+/answer", ""))
        assertEquals("Enter a value", onlyText("devices/+/answer", "   "))
    }

    @Test
    fun `a plus value with a slash is refused`() {
        assertEquals("One level only: no /", onlyText("devices/+/answer", "d/42"))
    }

    @Test
    fun `a plus value with a plus or a hash is refused`() {
        assertEquals("No + or # in a value", onlyText("devices/+/answer", "d+42"))
        assertEquals("No + or # in a value", onlyText("devices/+/answer", "d#42"))
        assertEquals("No + or # in a value", onlyText("devices/+/answer", "+"))
    }

    @Test
    fun `a plus value with a dot is refused with the dot text of the route form`() {
        assertEquals(dot, onlyText("devices/+/answer", "d.42"))
    }

    @Test
    fun `a value with a control character is refused`() {
        for (c in listOf('\u0000', '\n', '\r', '\t', '\u001b')) {
            assertEquals("No control characters in a value", onlyText("devices/+/answer", "d${c}42"), "code ${c.code}")
            assertEquals("No control characters in a value", onlyText("sensors/#", "a${c}b"), "hash, code ${c.code}")
        }
    }

    @Test
    fun `a blank hash value is refused and a hash value with a plus or a hash is refused`() {
        assertEquals("Enter a value", onlyText("sensors/#", ""))
        assertEquals("Enter a value", onlyText("sensors/#", "  "))
        assertEquals("No + or # in a value", onlyText("sensors/#", "a/+/b"))
        assertEquals("No + or # in a value", onlyText("sensors/#", "a#"))
    }

    @Test
    fun `one problem is reported for each refused slot and names the slot by its 0-based position`() {
        val both = problems("a/+/b/+", "x/y", "")
        assertEquals(listOf(0, 1), both.map { it.field })
        assertEquals(listOf("One level only: no /", "Enter a value"), both.map { it.text })
        val second = problems("a/+/b/+", "ok", "no.dot")
        assertEquals(listOf(1), second.map { it.field })
        assertEquals(listOf(dot), second.map { it.text })
    }

    // ---- hostile values ----

    @Test
    fun `a value of 10000 characters gives a topic and no exception, the broker may refuse it later`() {
        val long = "x".repeat(10_000)
        assertEquals("devices/$long/answer", topic("devices/+/answer", long))
        assertEquals("sensors/$long", topic("sensors/#", long))
    }
}
