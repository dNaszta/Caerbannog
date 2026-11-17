package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/**
 * The wizard page of an MQTT route: a topic filter and a destination, saved as one binding on `amq.topic`. [destinationKind] changes no action
 * (a stream is a queue for a binding); it narrows the suggestions, and is null when it is not said (a form rebuilt from a saved action).
 */
data class MqttRouteForm(
    val filter: String,
    val destination: String,
    val destinationKind: MqttDestinationKind? = null,
) : StepForm {
    /** The binding key the filter is saved as; null while the filter is refused. */
    fun bindingKey(): String? = (MqttTopicTranslation.toBindingKey(filter) as? Translation.Key)?.text

    /**
     * A concrete topic the filter matches: `+` gives `item`, `#` gives `test`, every other level stays as typed (spaces too). Built from the
     * filter's own levels and not from the key, because the shared helper of the Publish MQTT dialog trims the whole key.
     */
    fun matchingTopic(): String? =
        if (bindingKey() == null) null else filter.split('/').joinToString("/") { level ->
            when (level) {
                "+" -> "item"
                "#" -> "test"
                else -> level
            }
        }

    override fun problems(ctx: FormContext): List<FieldProblem> {
        val filterProblem = (MqttTopicTranslation.toBindingKey(filter) as? Translation.Refused)
            ?.let { listOf(FieldProblem("filter", PlannerTexts.mqttRefusal(it.reason))) }.orEmpty()
        val destinationProblem = when {
            destination.isEmpty() -> listOf(FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING))
            filterProblem.isEmpty() -> FormChecks.parserReason(build(ctx))?.let { listOf(FieldProblem("destination", it)) }.orEmpty()
            else -> emptyList()
        }
        return filterProblem + destinationProblem
    }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> = emptyList()

    private fun build(ctx: FormContext) = MigrationAction.DeclareBinding(
        exchange = MqttRoutes.EXCHANGE,
        queue = destination,
        routingKey = checkNotNull(bindingKey()) { "build is called only for an accepted filter" },
        vhost = ctx.plan.vhost,
        destinationType = BindingDestinationType.QUEUE,
    )
}
