package dev.caerbannog.core.migrations

/** The registry of action codecs: the one place that knows which actions the format has. */
internal object ActionCodecs {

    private val byName: Map<String, ActionCodec> = listOf(
        declareQueueCodec, deleteQueueCodec,
        declareExchangeCodec, deleteExchangeCodec,
        declareBindingCodec, deleteBindingCodec,
        declareStreamCodec, deleteStreamCodec,
        declarePolicyCodec, declareOperatorPolicyCodec, deletePolicyCodec,
    ).associateBy(ActionCodec::name)

    /** The codec for [actionName], or null: an unknown action is refused, never given a default codec. */
    fun find(actionName: String): ActionCodec? = byName[actionName]

    /** The codec that writes [actionName]; every action of the sealed model has one. */
    fun forAction(actionName: String): ActionCodec = byName.getValue(actionName)
}
