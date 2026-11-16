package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.echoableName

/**
 * The fixed texts of the planner, in one place: plain text, no markup. A name from a plan goes into a message only through the echo
 * rule ([echoableName]); a name the rule refuses is left out and the sentence stays whole without it.
 */
object PlannerTexts {
    /** The kinds the wizard offers, in the order of the mockup and the spec. None deletes anything. */
    val WIZARD_KINDS: List<String> = listOf(
        "Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue", "MQTT route",
    )

    const val QUEUE_NAME_MISSING: String = "Give the queue a name"
    const val EXCHANGE_NAME_MISSING: String = "Give the exchange a name"
    const val BINDING_EXCHANGE_MISSING: String = "Choose the exchange to bind from"
    const val BINDING_DESTINATION_MISSING: String = "Choose what to bind to"

    fun summaryTitle(steps: Int): String = "This adds $steps ${plural(steps, "step")}"

    fun saveBlocked(problems: Int): String = "Fix the $problems ${plural(problems, "problem")} in the plan first"

    fun declaredWithOtherSettings(name: String): String = "The plan already declares ${subject(name, "this name")} with other settings"

    fun declaredTwice(name: String): String = "The plan declares ${subject(name, "this name")} twice with the same settings"

    fun queueAndStreamOfOneName(name: String): String =
        if (echoableName(name)) "The plan declares $name as a queue and as a stream" else "The plan declares a queue and a stream of one name"

    /** [kind] is "queue", "exchange" or "stream"; [place] is the 1-based place of the step that declares it. */
    fun declaredLater(kind: String, name: String, place: Int): String {
        val named = if (echoableName(name)) "The $kind $name" else "The $kind"
        return "$named is declared later, in step $place. Move this step below it."
    }

    private fun subject(name: String, fallback: String): String = if (echoableName(name)) name else fallback

    private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"
}
