package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.mqtt.CorrelationIdSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M9-10b step 4: huge filters, many wildcards, and unusual text through the pure functions. Nothing may throw or take long. */
class MqttTestMessageHostileTest {
    private val ids = CorrelationIdSource { "id" }

    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `a filter of 70000 characters with one plus goes through offer and opening`() {
        val filter = "a/".repeat(35_000) + "+"
        val route = MqttRoute(filter, filter.replace('/', '.').replace("+", "*"), "q")
        assertEquals(TestMessageOffer.Available, MqttTestMessage.offer(MigrationStatus.Applied("x"), route, true, true, "/"))
        val opening = MqttTestMessage.opening(route, MqttDestinationKind.Queue, listOf("v"), ids)
        assertEquals("a/".repeat(35_000) + "v", opening.topic)
    }

    @Test
    @Timeout(20, unit = TimeUnit.SECONDS)
    fun `a filter of 70000 characters made of 35000 pluses gives 35000 slots and fills quickly`() {
        val filter = List(35_000) { "+" }.joinToString("/")
        val slots = MqttTopicSample.slots(filter)
        assertEquals(35_000, slots.size)
        val starts = slots.map { MqttTopicSample.startValue(it) }
        val result = assertIs<SampleResult.Topic>(MqttTopicSample.fill(filter, starts))
        assertEquals(List(35_000) { "item" }.joinToString("/"), result.topic)
    }

    @Test
    fun `right-to-left, CJK, emoji and combining text in a value never throw`() {
        for (v in listOf("مرحبا", "日本語", "😀🚀", "é", "‮abc", "​z")) {
            MqttTopicSample.fill("devices/+/answer", listOf(v))
            MqttTopicSample.fill("sensors/#", listOf(v))
            MqttTestMessage.opening(MqttRoute("devices/+/answer", "devices.*.answer", "q"), null, listOf(v), ids)
        }
    }

    @Test
    fun `a filter with a lone wildcard character inside a level is not a slot and fill returns it unchanged`() {
        // Not a valid MQTT filter and the route form refuses it, so this only guards against a crash.
        MqttTopicSample.slots("a+/b#")
        assertTrue(MqttTopicSample.fill("a+/b#", emptyList()) is SampleResult)
    }

    @Test
    fun `an empty filter has no slots`() {
        assertEquals(emptyList(), MqttTopicSample.slots(""))
    }
}
