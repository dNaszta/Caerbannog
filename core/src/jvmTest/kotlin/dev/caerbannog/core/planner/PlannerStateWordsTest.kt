package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.planner.fakes.FakeKnownTopologyReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-07b step 2 addendum: `PlannerState.wordsOf(action, alongside: List<MigrationAction> = emptyList())`. [alongside] are the actions that are about to
 * join the plan (the wizard's summary page passes its own actions), so the kind word of a route can come from them: the summary of a stream pair says
 * "to stream device-commands-dln" although the stream is not in the plan yet. Without it the words are the ones of M9-10; a non-route action is unchanged.
 */
class PlannerStateWordsTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterTest
    fun close() = rigs.forEach { it.close() }

    private fun rig(reader: KnownTopologyReader? = null, prepare: PlannerRig.() -> Unit = {}) =
        PlannerRig(reader = reader).also { rigs += it; it.prepare(); it.restart() }

    private val route = MigrationAction.DeclareBinding("amq.topic", "device-commands-dln", "devices.*.answer")
    private val streams = listOf(MigrationAction.DeclareStream("device-commands"), MigrationAction.DeclareStream("device-commands-dln"))

    @Test
    fun `a route says stream when the stream comes alongside`() {
        val state = rig().state
        val words = state.wordsOf(route, streams + route)
        assertEquals("MQTT route devices/+/answer to stream device-commands-dln", words.title)
        assertEquals("Binding on amq.topic with the key devices.*.answer", words.detail)
    }

    @Test
    fun `without alongside the words are the old ones`() {
        val state = rig().state
        assertEquals("MQTT route devices/+/answer to device-commands-dln", state.wordsOf(route).title)
        assertEquals(state.wordsOf(route), state.wordsOf(route, emptyList()))
    }

    @Test
    fun `the kind still comes from the plan and from the broker when nothing comes alongside`() {
        val inPlan = rig { stream("device-commands", withResponse = true) }.state
        assertEquals("MQTT route devices/+/answer to stream device-commands-dln", inPlan.wordsOf(route).title)
        val onBroker = rig(FakeKnownTopologyReader(KnownTopology(streams = setOf("device-commands-dln")))).state
        assertEquals("MQTT route devices/+/answer to stream device-commands-dln", onBroker.wordsOf(route).title)
    }

    @Test
    fun `a queue alongside gives queue`() {
        val state = rig().state
        assertEquals("MQTT route a/# to queue alarms", state.wordsOf(MigrationAction.DeclareBinding("amq.topic", "alarms", "a.#"), listOf(MigrationAction.DeclareQueue("alarms"))).title)
    }

    @Test
    fun `a non-route action is worded as before, whatever comes alongside`() {
        val state = rig().state
        val queue = MigrationAction.DeclareQueue("orders")
        assertEquals(MqttRouteWords(queue.description, queue.detail.orEmpty()), state.wordsOf(queue))
        assertEquals(state.wordsOf(queue), state.wordsOf(queue, streams + route))
        val binding = MigrationAction.DeclareBinding("events", "orders", "k")
        assertEquals(state.wordsOf(binding), state.wordsOf(binding, streams))
    }

    @Test
    fun `a stream alongside does not make a plan route a stream route of another name`() {
        val state = rig().state
        val other = MigrationAction.DeclareBinding("amq.topic", "elsewhere", "a.#")
        assertEquals("MQTT route a/# to elsewhere", state.wordsOf(other, streams).title)
    }
}
