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

    /** The kinds that have a form today, in wizard order. The others of [WIZARD_KINDS] are drawn when the task that builds their form lands. */
    val BUILT_KINDS: List<String> = listOf("Queue", "Exchange", "Binding")

    fun kindDescription(kind: String): String = when (kind) {
        "Queue" -> "A quorum or classic queue"
        "Exchange" -> "Fanout, direct, topic or headers"
        "Binding" -> "From an exchange to a queue or an exchange"
        else -> ""
    }

    /** One sentence per exchange type, under the type choice of the exchange form. */
    fun exchangeTypeNote(type: String): String = when (type) {
        "fanout" -> "Fanout: a message goes to every bound queue."
        "direct" -> "Direct: a message goes to the queues bound with exactly its routing key."
        "topic" -> "Topic: a message goes by a pattern with * and #."
        "headers" -> "Headers: a message goes by its headers, not by the routing key."
        else -> ""
    }

    fun queueTypeNote(type: String): String = when (type) {
        "quorum" -> "Replicated, durable."
        "classic" -> "One copy on one node."
        else -> ""
    }

    /** What a naming style does to a derived name, for the menu of the plan header. */
    fun namingExample(style: NamingStyle): String =
        if (style == NamingStyle.NONE) "No hints; derived names as in kebab-case" else PlanNaming.derived("orders", DerivedWord.DLN, style)

    const val QUEUE_FORM_NOTE: String =
        "Limits and dead-lettering are set with a policy step, so they can be changed later without deleting the queue."
    const val INFO_LINE: String =
        "A plan is a draft. Nothing on this screen changes the broker: saving writes a migration file, and the file is applied from the Migrations screen."
    const val NO_STEPS_HELP: String =
        "Add the first step with the wizard: a queue, an exchange or a binding. You do not write JSON; the plan is saved as a migration file when you are done."
    const val STEPS_TITLE: String = "Steps, applied from top to bottom"
    const val REVIEW_TITLE: String = "The migration this plan saves as"
    const val REVIEW_NOTE: String = "Read only. This is the text of the file that Save as migration writes."
    const val WIZARD_KIND_TITLE: String = "What do you want to add?"
    const val NOT_IN_PLAN_NOTE: String = "Not in this plan and not seen on the broker"
    const val CHECK_LABEL: String = "Check"
    const val DISMISS_LABEL: String = "Dismiss"
    const val PROBLEM_PILL: String = "Problem"

    const val QUEUE_NAME_MISSING: String = "Give the queue a name"
    const val EXCHANGE_NAME_MISSING: String = "Give the exchange a name"
    const val BINDING_EXCHANGE_MISSING: String = "Choose the exchange to bind from"
    const val BINDING_DESTINATION_MISSING: String = "Choose what to bind to"

    const val ADD_A_STEP_FIRST: String = "Add a step first"
    const val NO_FOLDER: String = "Choose a migrations folder first"
    const val DRAFT_LABEL: String = "Draft, not saved as a migration"
    const val FILE_NOT_WRITTEN: String = "The file could not be written"
    const val FILE_NOT_WRITTEN_DETAIL: String = "The plan is still here as a draft. Nothing was changed."
    const val NO_STEPS_YET: String = "No steps yet"
    const val NOTHING_TO_REVIEW: String = "Nothing to review yet"
    const val CHECK_LATER: String = "Checking comes in a later step"
    const val DRAFT_CARD_TITLE: String = "Draft plan"
    const val DRAFT_CARD_PILL: String = "Not saved"
    const val DRAFT_NOT_SAVED: String = "The draft could not be saved on this machine; the plan is only in memory"
    const val DRAFT_NOT_MOVED: String = "The draft file could not be moved aside; it was not changed"
    const val DRAFT_FILE_NAME: String = "planner-draft.json"

    const val NOT_OFFERED: String = "The planner is not offered on this connection"
    const val DRAFT_UNREADABLE: String = "The saved draft cannot be read"
    const val READ_ONLY_LEVEL: String =
        "This connection is set to Read-only. You can plan, check and save; applying needs the level Declare and edit."
    const val NOT_CONNECTED: String = "Not connected. You can plan and save; connect to a RabbitMQ 4 broker to check the plan."
    const val NO_FOLDER_BANNER: String = "Choose a migrations folder to save this plan. Until then it stays a draft on this machine."

    fun noConfigurePermission(connection: String?): String =
        "The permission check found that this user cannot change topology on ${connection ?: "this connection"}. " +
            "You can plan, check and save, then apply the migration on a connection that allows it."

    /**
     * [planVhost] comes from a file, so it goes in only through the echo rule. A virtual host is often written with one leading slash
     * ("/orders"), which the rule alone refuses, so that single slash is allowed in front of a name the rule accepts.
     */
    fun vhostMismatch(planVhost: String, connection: String, connectionVhost: String): String {
        val echo = echoableName(planVhost) || planVhost.startsWith("/") && echoableName(planVhost.drop(1))
        val plan = if (echo) "the virtual host $planVhost" else "another virtual host"
        val target = if (echo) "a connection for $planVhost" else "a connection for that virtual host"
        return "This plan is for $plan; $connection is connected to $connectionVhost. It can be saved here and applied on $target."
    }

    fun draftCardSteps(steps: Int): String = "$steps ${plural(steps, "step")} \u00b7 not a migration yet"

    fun planSaved(version: String): String = "Plan saved as $version. Nothing was sent to the broker. Apply it when you are ready."

    fun dismissQuestion(steps: Int): String =
        "Dismiss this plan? Its $steps ${if (steps == 1) "step is" else "steps are"} discarded. Nothing was sent to the broker."

    fun startNewPlanQuestion(asideFileName: String): String =
        "Move $DRAFT_FILE_NAME to $asideFileName and start a new plan? The old file is kept."

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
