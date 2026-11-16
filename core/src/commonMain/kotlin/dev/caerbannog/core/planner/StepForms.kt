package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.DOT_NAME
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType

/** A reason a form cannot be added; [field] is `name`, `exchange` or `destination`. */
data class FieldProblem(val field: String, val message: String)

/** Advice on a field. It never blocks (O11). */
data class FieldHint(val field: String, val message: String)

/** The wizard page of one kind of step. Pure: the same fields and context always give the same answers. */
sealed interface StepForm {
    fun problems(ctx: FormContext): List<FieldProblem>

    /** The steps this form adds; empty while there is a problem. */
    fun actions(ctx: FormContext): List<MigrationAction>

    fun hints(ctx: FormContext): List<FieldHint>
}

data class QueueForm(val name: String, val type: String = "quorum") : StepForm {
    override fun problems(ctx: FormContext): List<FieldProblem> =
        FormChecks.nameProblem(name, PlannerTexts.QUEUE_NAME_MISSING, "name") { build(ctx) }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> =
        FormChecks.nameHint(name, ctx) { name !in ctx.known.queues && name !in ctx.known.streams }

    private fun build(ctx: FormContext) = MigrationAction.DeclareQueue(name = name, type = type, vhost = ctx.plan.vhost)
}

data class ExchangeForm(val name: String, val type: String = "direct") : StepForm {
    override fun problems(ctx: FormContext): List<FieldProblem> =
        FormChecks.nameProblem(name, PlannerTexts.EXCHANGE_NAME_MISSING, "name") { build(ctx) }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> =
        FormChecks.nameHint(name, ctx) { name !in ctx.known.exchanges }

    private fun build(ctx: FormContext) = MigrationAction.DeclareExchange(name = name, type = type, vhost = ctx.plan.vhost)
}

/** A binding never hints: an exchange, a destination and a routing key are mostly names the broker has already (Q7). */
data class BindingForm(
    val exchange: String,
    val destination: String,
    val destinationKind: BindingDestinationType = BindingDestinationType.QUEUE,
    val routingKey: String = "",
) : StepForm {
    override fun problems(ctx: FormContext): List<FieldProblem> {
        val missing = buildList {
            if (exchange.isEmpty()) add(FieldProblem("exchange", PlannerTexts.BINDING_EXCHANGE_MISSING))
            if (destination.isEmpty()) add(FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING))
        }
        if (missing.isNotEmpty()) return missing
        return FormChecks.parserReason(build(ctx))?.let { listOf(FieldProblem("exchange", it)) }.orEmpty()
    }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> = emptyList()

    private fun build(ctx: FormContext) = MigrationAction.DeclareBinding(
        exchange = exchange,
        queue = destination,
        routingKey = routingKey,
        vhost = ctx.plan.vhost,
        destinationType = destinationKind,
    )
}

object StepForms {
    private val queueTypes = setOf("quorum", "classic")
    private val exchangeTypes = setOf("fanout", "direct", "topic", "headers")

    /**
     * The form that holds [action], or null when a form cannot hold it: arguments, flags that are not the default, every delete, every
     * access action, other types. The screen then shows the step read-only and the file keeps it as it is (A2).
     */
    fun formOf(action: MigrationAction): StepForm? = when (action) {
        is MigrationAction.DeclareQueue ->
            if (action.type in queueTypes && action.durable && !action.autoDelete && action.arguments.isEmpty()) {
                QueueForm(name = action.name, type = action.type)
            } else {
                null
            }
        is MigrationAction.DeclareExchange ->
            if (action.type in exchangeTypes && action.durable && !action.autoDelete && !action.internal && action.arguments.isEmpty()) {
                ExchangeForm(name = action.name, type = action.type)
            } else {
                null
            }
        is MigrationAction.DeclareBinding ->
            if (action.arguments.isEmpty()) {
                BindingForm(action.exchange, action.queue, action.destinationType, action.routingKey)
            } else {
                null
            }
        else -> null
    }
}

internal object FormChecks {
    /** An empty name and "." or ".." first, then whatever the parser round trip refuses, with the parser's own reason. */
    fun nameProblem(name: String, missing: String, field: String, build: () -> MigrationAction): List<FieldProblem> = when {
        name.isEmpty() -> listOf(FieldProblem(field, missing))
        name == "." || name == ".." -> listOf(FieldProblem(field, DOT_NAME))
        else -> parserReason(build())?.let { listOf(FieldProblem(field, it)) }.orEmpty()
    }

    /** A hint for the name of something new: not for an empty name and not for a name the broker already has. */
    fun nameHint(name: String, ctx: FormContext, isNew: () -> Boolean): List<FieldHint> {
        if (name.isEmpty() || !isNew()) return emptyList()
        return PlanNaming.hint(name, ctx.namingStyle)?.let { listOf(FieldHint("name", it)) }.orEmpty()
    }

    /** The reason the parser gives for a file holding [action], or null when it accepts it. */
    fun parserReason(action: MigrationAction): String? = roundTrip("", listOf(action))

    fun roundTrip(description: String, actions: List<MigrationAction>): String? =
        (MigrationParser.parse(MigrationParser.exportToJson("draft", description, actions)) as? MigrationValidationResult.Invalid)?.reason
}
