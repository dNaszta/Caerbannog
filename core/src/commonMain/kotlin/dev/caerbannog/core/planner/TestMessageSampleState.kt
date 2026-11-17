package dev.caerbannog.core.planner

/**
 * The state of the small sample-value dialog: one value per wildcard of [filter], in order. Immutable; the composable only draws it and calls
 * [withValue]. The refusals come from [MqttTopicSample], so the dialog and the opening can never disagree.
 */
data class TestMessageSampleState(val filter: String, val values: List<String>) {
    val slots: List<SampleSlot> = MqttTopicSample.slots(filter)

    /** The refusal under field [field] (0-based), or null while the value is good. Only fields that have a slot can be refused. */
    fun errorFor(field: Int): String? = slots.getOrNull(field)?.let { slot -> values.getOrNull(field)?.let { MqttTopicSample.refusal(slot, it) } }

    fun labelFor(field: Int): String = slots.getOrNull(field)?.let { MqttTestMessageTexts.label(it) }.orEmpty()

    /** Open is blocked while any field is refused. */
    val canOpen: Boolean get() = slots.indices.none { errorFor(it) != null }

    fun withValue(field: Int, value: String): TestMessageSampleState =
        if (field !in values.indices) this else copy(values = values.toMutableList().also { it[field] = value })

    companion object {
        /** The dialog starts with the start values of the route form, so Open works at once. */
        fun start(filter: String): TestMessageSampleState =
            TestMessageSampleState(filter, MqttTopicSample.slots(filter).map { MqttTopicSample.startValue(it) })
    }
}
