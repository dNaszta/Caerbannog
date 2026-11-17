package dev.caerbannog.core.planner

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-10 step 2: the one place that knows the MQTT topic filter <-> AMQP binding key rule (A16), pinned by the M9-00a measurements
 * (RabbitMQ 4.4.0): S-A1 the table, S-A3 the refusals and what is carried unchanged. Not pinned (not measured): a whole-level `*` on the broker
 * (refused here as the safe side), case, a topic above 255 bytes, non-ASCII, QoS 2.
 *
 * API fixed by these tests (names for the architect to confirm): `object MqttTopicTranslation { fun toBindingKey(filter: String): Translation;
 * fun toFilter(key: String): String? }`, `sealed interface Translation { data class Key(val text: String); data class Refused(val reason: MqttFilterRefusal) }`,
 * `enum class MqttFilterRefusal { Empty, HashNotLast, PlusNotWholeLevel, DotInLevel, StarInLevel, EmptyLevel }`, texts by
 * `PlannerTexts.mqttRefusal(reason)`.
 */
class MqttTopicTranslationTest {
    private fun key(filter: String): String =
        (MqttTopicTranslation.toBindingKey(filter) as? Translation.Key)?.text ?: error("\"$filter\" was refused: ${MqttTopicTranslation.toBindingKey(filter)}")

    private fun refusal(filter: String): MqttFilterRefusal =
        (MqttTopicTranslation.toBindingKey(filter) as? Translation.Refused)?.reason ?: error("\"$filter\" was accepted: ${MqttTopicTranslation.toBindingKey(filter)}")

    // ---- S-A1: the table, both ways ----

    private val table = listOf(
        "sensors/+/temp" to "sensors.*.temp",
        "sensors/#" to "sensors.#",
        "devices/d1/status" to "devices.d1.status",
        "devices/+/answer" to "devices.*.answer",
        "#" to "#",
        "+" to "*",
        "a/b/c" to "a.b.c",
        "+/+" to "*.*",
        "a" to "a",
        "+/#" to "*.#",
    )

    @Test
    fun `the measured table gives the key`() {
        for ((filter, expected) in table) assertEquals(expected, key(filter), "filter $filter")
    }

    @Test
    fun `the measured table gives the filter back`() {
        for ((filter, k) in table) assertEquals(filter, MqttTopicTranslation.toFilter(k), "key $k")
    }

    // ---- S-A3: carried unchanged ----

    @Test
    fun `a space, a leading dollar and upper case are carried as typed`() {
        assertEquals("sensors.a b.temp", key("sensors/a b/temp"))
        assertEquals("\$SYS.#", key("\$SYS/#"))
        assertEquals("Sensors.Temp", key("Sensors/Temp"))
        assertEquals("sensors/a b/temp", MqttTopicTranslation.toFilter("sensors.a b.temp"))
        assertEquals("\$SYS/#", MqttTopicTranslation.toFilter("\$SYS.#"))
    }

    // ---- refusals ----

    @Test
    fun `every refused filter has its own reason`() {
        val cases = listOf(
            "" to MqttFilterRefusal.Empty,
            "sensors/#/temp" to MqttFilterRefusal.HashNotLast,
            "sensors/te#" to MqttFilterRefusal.HashNotLast,
            "#/a" to MqttFilterRefusal.HashNotLast,
            "##" to MqttFilterRefusal.HashNotLast,
            "sens+/temp" to MqttFilterRefusal.PlusNotWholeLevel,
            "a/+b" to MqttFilterRefusal.PlusNotWholeLevel,
            "sensors/v1.2/temp" to MqttFilterRefusal.DotInLevel,
            "a.b" to MqttFilterRefusal.DotInLevel,
            "a.b/#" to MqttFilterRefusal.DotInLevel,
            "sensors/a*b/temp" to MqttFilterRefusal.StarInLevel,
            "sensors/*/temp" to MqttFilterRefusal.StarInLevel,
            "*" to MqttFilterRefusal.StarInLevel,
            "sensors//temp" to MqttFilterRefusal.EmptyLevel,
            "/sensors/temp" to MqttFilterRefusal.EmptyLevel,
            "sensors/temp/" to MqttFilterRefusal.EmptyLevel,
            "/" to MqttFilterRefusal.EmptyLevel,
        )
        for ((filter, reason) in cases) assertEquals(reason, refusal(filter), "filter \"$filter\"")
    }

    @Test
    fun `the refusal texts are the decided ones`() {
        assertEquals("Enter a topic filter", PlannerTexts.mqttRefusal(MqttFilterRefusal.Empty))
        assertEquals("# must be the last level", PlannerTexts.mqttRefusal(MqttFilterRefusal.HashNotLast))
        assertEquals("+ must stand for a whole level", PlannerTexts.mqttRefusal(MqttFilterRefusal.PlusNotWholeLevel))
        assertEquals("A level cannot contain a dot: RabbitMQ would read it as another level", PlannerTexts.mqttRefusal(MqttFilterRefusal.DotInLevel))
        assertEquals("A level cannot contain *: use + for a whole level", PlannerTexts.mqttRefusal(MqttFilterRefusal.StarInLevel))
        assertEquals("A level cannot be empty", PlannerTexts.mqttRefusal(MqttFilterRefusal.EmptyLevel))
        assertEquals(MqttFilterRefusal.entries.size, MqttFilterRefusal.entries.map { PlannerTexts.mqttRefusal(it) }.toSet().size, "one text per reason")
    }

    // ---- the key back to a filter ----

    @Test
    fun `a key that is not a plain route gives no filter`() {
        val none = listOf(
            "", ".", "a..b", ".a", "a.", // an empty word
            "a.b#", "a.#b", "#a", "a.*b", "a*", // # or * mixed with other characters
            "a.#.b", "#.a", "#.#", // # not last
        )
        for (k in none) assertNull(MqttTopicTranslation.toFilter(k), "key \"$k\"")
    }

    @Test
    fun `a key with a slash or a plus word may have a filter, but it does not come back as the same key`() {
        // toFilter is the plain character swap of the brief; that its answer is not always an inverse is why recognition (MqttRoutes.routeOf)
        // round-trips the key and keeps these as ordinary bindings. Pinned here so nobody "fixes" toFilter and loses the reason.
        for (k in listOf("a/b", "a.+", "+", "a.b/c", "x.+.y")) {
            val filter = MqttTopicTranslation.toFilter(k)
            if (filter != null) assertTrue(MqttTopicTranslation.toBindingKey(filter) != Translation.Key(k), "key \"$k\" came back through \"$filter\"")
        }
    }

    // ---- the inverse property ----

    private val acceptedFilters = table.map { it.first } + listOf(
        "sensors/a b/temp", "\$SYS/#", "Sensors/Temp", "a/b/c/d/e/f/g", "x1/y2", "ünï/ç", "tab\there", "a:b/c;d", "a-b/c_d", "#", "+/#",
        "a b", " ", "a/ /b", "\\", "a/\\/b", "a%/b",
    )

    @Test
    fun `toFilter of toBindingKey is the typed filter for every accepted filter`() {
        for (f in acceptedFilters) assertEquals(f, MqttTopicTranslation.toFilter(key(f)), "filter \"$f\"")
    }

    @Test
    fun `toBindingKey of toFilter is the key for every key that has a filter`() {
        val keys = acceptedFilters.map { key(it) } + listOf("sensors.*.temp", "a.b", "*.*.*", "#", "*", "x y.z")
        for (k in keys) {
            val filter = MqttTopicTranslation.toFilter(k) ?: error("no filter for \"$k\"")
            assertEquals(Translation.Key(k), MqttTopicTranslation.toBindingKey(filter), "key \"$k\" through \"$filter\"")
        }
    }

    // ---- hostile input ----

    @Test
    fun `hostile text never throws and an accepted filter never holds a refused character in a level`() {
        val hostile = listOf(
            "\u0000", "a\u0000b", "a\nb", "a/\n", "\r\n", "a\tb/#", "‮/#", "a/\u0000/b", "x".repeat(70_000), "/".repeat(1000),
            "a/".repeat(5000) + "#", "+/".repeat(3000) + "+", "\uD800", "😀/#", "%00/#", "../../etc", "a/b/../c", "<script>/#", "\"/#", "'; DROP/#",
        )
        for (f in hostile) {
            when (val t = MqttTopicTranslation.toBindingKey(f)) {
                is Translation.Key -> {
                    assertEquals(f, MqttTopicTranslation.toFilter(t.text), "accepted but not inverse for ${f.take(20).replace("\u0000", "NUL")}")
                    assertTrue('/' !in t.text, "a slash is left in the key")
                }
                is Translation.Refused -> assertTrue(PlannerTexts.mqttRefusal(t.reason).isNotEmpty())
            }
            MqttTopicTranslation.toFilter(f) // as a key: no throw
        }
    }
}
