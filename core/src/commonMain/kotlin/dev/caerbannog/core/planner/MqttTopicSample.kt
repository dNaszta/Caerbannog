package dev.caerbannog.core.planner

/** One wildcard of a topic filter that the user fills with a sample value; [level] counts the filter's levels from 1. */
sealed interface SampleSlot {
    val level: Int

    data class Plus(override val level: Int) : SampleSlot
    data class Hash(override val level: Int) : SampleSlot
}

/** A refused sample value: [field] is the 0-based position of the slot, [text] the fixed refusal. */
data class SampleProblem(val field: Int, val text: String)

sealed interface SampleResult {
    data class Topic(val topic: String) : SampleResult
    data class Refused(val problems: List<SampleProblem>) : SampleResult
}

/**
 * Sample values for the wildcards of a route's topic filter, so a test message gets a concrete topic. Pure rules; the filter itself is already
 * accepted by the route form. A `+` value is one level; a `#` value may hold levels, empty levels and dots (measured to work, S-A3).
 */
object MqttTopicSample {
    fun slots(filter: String): List<SampleSlot> = filter.split('/').mapIndexedNotNull { index, level ->
        when (level) {
            "+" -> SampleSlot.Plus(index + 1)
            "#" -> SampleSlot.Hash(index + 1)
            else -> null
        }
    }

    /** The start value of a slot: the same words as `MqttRouteForm.matchingTopic()`. */
    fun startValue(slot: SampleSlot): String = when (slot) {
        is SampleSlot.Plus -> "item"
        is SampleSlot.Hash -> "test"
    }

    fun fill(filter: String, values: List<String>): SampleResult {
        val slots = slots(filter)
        if (slots.size != values.size) {
            val field = minOf(slots.size, values.size)
            return SampleResult.Refused(listOf(SampleProblem(field, MqttTestMessageTexts.wrongCount(slots.size, values.size))))
        }
        val problems = slots.indices.mapNotNull { i -> refusal(slots[i], values[i])?.let { SampleProblem(i, it) } }
        if (problems.isNotEmpty()) return SampleResult.Refused(problems)
        var next = 0
        val topic = filter.split('/').joinToString("/") { level -> if (level == "+" || level == "#") values[next++] else level }
        return SampleResult.Topic(topic)
    }

    /** The first problem of one value, in a fixed order: blank, control character, slash, plus or hash, dot. */
    fun refusal(slot: SampleSlot, value: String): String? = when {
        value.isBlank() -> MqttTestMessageTexts.ENTER_A_VALUE
        value.any { it.isISOControl() } -> MqttTestMessageTexts.NO_CONTROL_CHARACTERS
        slot is SampleSlot.Plus && '/' in value -> MqttTestMessageTexts.ONE_LEVEL_ONLY
        '+' in value || '#' in value -> MqttTestMessageTexts.NO_WILDCARD_IN_VALUE
        slot is SampleSlot.Plus && '.' in value -> PlannerTexts.mqttRefusal(MqttFilterRefusal.DotInLevel)
        else -> null
    }
}
