package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
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

/**
 * A stream, with its response stream (DLN) when [withResponse] (O9, O10). [responseName] null means "follows the stream name"; any string, even
 * an empty one, means the user typed it, so a later change of [name] leaves it alone.
 *
 * [answersTopic] and [requestsTopic] are the two optional MQTT ticks of a pair (O13): null is off, a string is on, and `""` is on with no filter
 * typed yet (the form then asks for one; nothing is guessed). Both are ignored while [withResponse] is false, so a stale value never makes a step.
 */
data class StreamForm(
    val name: String,
    val withResponse: Boolean = false,
    val responseName: String? = null,
    val answersTopic: String? = null,
    val requestsTopic: String? = null,
) : StepForm {
    /** Switches the pair. Off clears both ticks (they exist only with the pair) and keeps a typed [responseName] for the next time. */
    fun withPair(on: Boolean): StreamForm = when {
        !on -> copy(withResponse = false, answersTopic = null, requestsTopic = null)
        else -> copy(withResponse = true)
    }

    /** The response name the stream name gives in the plan's naming style; empty while there is no stream name. */
    fun suggestedResponseName(ctx: FormContext): String =
        if (name.isEmpty()) "" else PlanNaming.derived(name, DerivedWord.DLN, ctx.namingStyle)

    fun effectiveResponseName(ctx: FormContext): String = responseName ?: suggestedResponseName(ctx)

    override fun problems(ctx: FormContext): List<FieldProblem> {
        val own = FormChecks.nameProblem(name, PlannerTexts.STREAM_NAME_MISSING, "name") { build(name, ctx) }
            .ifEmpty { queueConflict(name, "name", ctx) }
        if (!withResponse) return own
        val response = effectiveResponseName(ctx)
        // A response name that follows an empty stream name is not a second problem: the first one says what to do.
        if (responseName == null && name.isEmpty()) return own + topicProblems()
        val theirs = FormChecks.nameProblem(response, PlannerTexts.RESPONSE_NAME_MISSING, "responseName") { build(response, ctx) }
            .ifEmpty {
                if (response == name) {
                    listOf(FieldProblem("responseName", PlannerTexts.RESPONSE_NAME_SAME))
                } else {
                    queueConflict(response, "responseName", ctx)
                }
            }
        return own + theirs + topicProblems()
    }

    /** The binding key the answers filter is saved as; null while that tick is off or its filter is refused. */
    fun answersKey(): String? = answersTopic?.let(::keyOf)

    /** The binding key the requests filter is saved as; null while that tick is off or its filter is refused. */
    fun requestsKey(): String? = requestsTopic?.let(::keyOf)

    private fun keyOf(filter: String): String? = (MqttTopicTranslation.toBindingKey(filter) as? Translation.Key)?.text

    /** Only the filter check of the route form: its destination is a stream name this form already checks, so one mistake gets one message. */
    private fun topicProblems(): List<FieldProblem> = buildList {
        answersTopic?.let { addAll(topicProblem(it, "answersTopic")) }
        requestsTopic?.let { addAll(topicProblem(it, "requestsTopic")) }
    }

    private fun topicProblem(filter: String, field: String): List<FieldProblem> =
        (MqttTopicTranslation.toBindingKey(filter) as? Translation.Refused)
            ?.let { listOf(FieldProblem(field, PlannerTexts.mqttRefusal(it.reason))) }.orEmpty()

    override fun actions(ctx: FormContext): List<MigrationAction> = when {
        problems(ctx).isNotEmpty() -> emptyList()
        withResponse -> {
            val response = effectiveResponseName(ctx)
            // Each route is what the MQTT route form gives for it, so the pair and the route step cannot drift apart.
            listOf(build(name, ctx), build(response, ctx)) +
                routeInto(answersTopic, response, ctx) + routeInto(requestsTopic, name, ctx)
        }
        else -> listOf(build(name, ctx))
    }

    private fun routeInto(filter: String?, destination: String, ctx: FormContext): List<MigrationAction> =
        if (filter == null) emptyList() else MqttRouteForm(filter, destination, MqttDestinationKind.Stream).actions(ctx)

    override fun hints(ctx: FormContext): List<FieldHint> =
        FormChecks.nameHint(name, ctx, "name") { isNewStreamName(name, ctx) } +
            // The suggestion is built from the stream name, which already has its own hint: only a name typed by hand gets one.
            if (withResponse && responseName != null) {
                FormChecks.nameHint(responseName, ctx, "responseName") { isNewStreamName(responseName, ctx) }
            } else {
                emptyList()
            }

    private fun isNewStreamName(candidate: String, ctx: FormContext): Boolean =
        candidate !in ctx.known.queues && candidate !in ctx.known.streams

    private fun queueConflict(candidate: String, field: String, ctx: FormContext): List<FieldProblem> =
        if (ctx.plan.steps.any { (it.action as? MigrationAction.DeclareQueue)?.name == candidate }) {
            listOf(FieldProblem(field, PlannerTexts.queueAndStreamOfOneName(candidate)))
        } else {
            emptyList()
        }

    private fun build(streamName: String, ctx: FormContext) = MigrationAction.DeclareStream(name = streamName, vhost = ctx.plan.vhost)
}

/** The match rules the form offers; `-with-x` was not measured, so a file that holds one keeps it as [BindingForm.heldRule]. */
enum class MatchRule(val wire: String) { ALL("all"), ANY("any") }

/** The value types of a header the form offers; a decimal is not offered (not measured). */
enum class HeaderValueType { TEXT, WHOLE, FLAG }

/**
 * One header of a headers binding. [value] is raw text, because a whole number is typed key by key; for [HeaderValueType.FLAG] it is `"true"`
 * or `"false"`. Nothing is trimmed: a header `" format"` is another header on the broker.
 */
data class HeaderMatch(val name: String, val type: HeaderValueType = HeaderValueType.TEXT, val value: String = "") {
    /** The typed value, or null when a whole number is not one that fits a `Long` (ASCII digits, an optional leading `-`) or a flag is neither word. */
    fun argumentValue(): ArgumentValue? = when (type) {
        HeaderValueType.TEXT -> ArgumentValue.Text(value)
        HeaderValueType.WHOLE -> if (isWholeNumber(value)) value.toLongOrNull()?.let(ArgumentValue::Whole) else null
        HeaderValueType.FLAG -> when (value) {
            "true" -> ArgumentValue.Flag(true)
            "false" -> ArgumentValue.Flag(false)
            else -> null
        }
    }

    /**
     * The same header with another type. The value follows what the new type can hold: a boolean starts at `true`, and the words `true` and
     * `false` are not carried into a text or number field (they were a choice, not typing).
     */
    fun withType(newType: HeaderValueType): HeaderMatch = when {
        newType == type -> this
        newType == HeaderValueType.FLAG -> copy(type = newType, value = "true")
        type == HeaderValueType.FLAG -> copy(type = newType, value = "")
        else -> copy(type = newType)
    }

    private fun isWholeNumber(text: String): Boolean {
        val digits = text.removePrefix("-")
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}

/**
 * A binding. On a headers exchange it carries [headers] and a match rule instead of a routing key (the key is never added; one an opened file
 * holds is kept). [heldRule] is the `x-match` of an opened file when it is not `all` or `any`: it is written back unchanged until the user picks a
 * rule ([withRule]). A binding never hints about names (Q7); its only hints are the two warnings for a headers binding with no header.
 */
data class BindingForm(
    val exchange: String,
    val destination: String,
    val destinationKind: BindingDestinationType = BindingDestinationType.QUEUE,
    val routingKey: String = "",
    val headers: List<HeaderMatch> = emptyList(),
    val matchRule: MatchRule = MatchRule.ALL,
    val heldRule: String? = null,
) : StepForm {
    /** An explicit choice of `all` or `any`: an edit, and the only way a held rule changes. */
    fun withRule(rule: MatchRule): BindingForm = copy(matchRule = rule, heldRule = null)

    override fun problems(ctx: FormContext): List<FieldProblem> {
        val missing = buildList {
            if (exchange.isEmpty()) add(FieldProblem("exchange", PlannerTexts.BINDING_EXCHANGE_MISSING))
            if (destination.isEmpty()) add(FieldProblem("destination", PlannerTexts.BINDING_DESTINATION_MISSING))
        }
        if (missing.isNotEmpty()) return missing
        val headerProblems = headerProblems()
        if (headerProblems.isNotEmpty()) return headerProblems
        return FormChecks.parserReason(build(ctx))?.let { listOf(FieldProblem("exchange", it)) }.orEmpty()
    }

    /** One problem per mistake, rows in order; a name used twice is one problem on its later row. */
    private fun headerProblems(): List<FieldProblem> {
        val seen = mutableSetOf<String>()
        return buildList {
            for (header in headers) {
                when {
                    header.name.isEmpty() -> add(PlannerTexts.HEADER_NAME_MISSING)
                    header.name == MATCH_KEY -> add(PlannerTexts.HEADER_NAME_IS_MATCH_RULE)
                    !seen.add(header.name) -> add(PlannerTexts.HEADER_LISTED_TWICE)
                }
                if (header.type == HeaderValueType.WHOLE && header.argumentValue() == null) add(PlannerTexts.HEADER_WHOLE_DIGITS_ONLY)
            }
        }.map { FieldProblem("headers", it) }
    }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> =
        if (headers.isEmpty() && ctx.exchangeType(exchange) == "headers") {
            listOf(FieldHint("headers", if (matchRule == MatchRule.ANY) PlannerTexts.HEADERS_NONE_ANY else PlannerTexts.HEADERS_NONE_ALL))
        } else {
            emptyList()
        }

    private fun build(ctx: FormContext) = MigrationAction.DeclareBinding(
        exchange = exchange,
        queue = destination,
        routingKey = routingKey,
        vhost = ctx.plan.vhost,
        destinationType = destinationKind,
        arguments = arguments(ctx),
    )

    /** `x-match` and the headers when there are headers or the source is a known headers exchange; otherwise nothing, as before. */
    private fun arguments(ctx: FormContext): Map<String, ArgumentValue> {
        if (headers.isEmpty() && ctx.exchangeType(exchange) != "headers") return emptyMap()
        return buildMap {
            put(MATCH_KEY, ArgumentValue.Text(heldRule ?: matchRule.wire))
            headers.forEach { header -> header.argumentValue()?.let { put(header.name, it) } }
        }
    }

    companion object {
        const val MATCH_KEY: String = "x-match"
    }
}

object StepForms {
    /**
     * The headers form of a binding whose arguments are `x-match` (text) plus at least one header of a kind the form offers; null otherwise (A2).
     * `x-match` alone is read-only: the form has no exchange context, and dropping the rule on a plain edit would lose what the file had.
     * A rule other than `all` or `any` is kept as [BindingForm.heldRule].
     */
    private fun headersBindingForm(action: MigrationAction.DeclareBinding): BindingForm? {
        val rule = (action.arguments[BindingForm.MATCH_KEY] as? ArgumentValue.Text)?.value ?: return null
        val headers = action.arguments.filterKeys { it != BindingForm.MATCH_KEY }.map { (name, value) ->
            when (value) {
                is ArgumentValue.Text -> HeaderMatch(name, HeaderValueType.TEXT, value.value)
                is ArgumentValue.Whole -> HeaderMatch(name, HeaderValueType.WHOLE, value.value.toString())
                is ArgumentValue.Flag -> HeaderMatch(name, HeaderValueType.FLAG, value.value.toString())
                else -> return null
            }
        }
        if (headers.isEmpty()) return null
        val known = MatchRule.entries.firstOrNull { it.wire == rule }
        return BindingForm(
            exchange = action.exchange,
            destination = action.queue,
            destinationKind = action.destinationType,
            routingKey = action.routingKey,
            headers = headers,
            matchRule = known ?: MatchRule.ALL,
            heldRule = if (known == null) rule else null,
        )
    }

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
            if (MqttRoutes.routeOf(action) != null) {
                MqttRouteForm(filter = MqttRoutes.routeOf(action)?.filter.orEmpty(), destination = action.queue)
            } else if (action.arguments.isEmpty()) {
                BindingForm(action.exchange, action.queue, action.destinationType, action.routingKey)
            } else {
                headersBindingForm(action)
            }
        // The form writes a name only, so a stream with settings has no form (read-only step, kept as written): editing it would drop them.
        // A response stream of a pair is edited as an ordinary stream.
        is MigrationAction.DeclareStream -> if (action.arguments.isEmpty()) StreamForm(name = action.name) else null
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
    fun nameHint(name: String, ctx: FormContext, field: String = "name", isNew: () -> Boolean): List<FieldHint> {
        if (name.isEmpty() || !isNew()) return emptyList()
        return PlanNaming.hint(name, ctx.namingStyle)?.let { listOf(FieldHint(field, it)) }.orEmpty()
    }

    /** The reason the parser gives for a file holding [action], or null when it accepts it. */
    fun parserReason(action: MigrationAction): String? = roundTrip("", listOf(action))

    fun roundTrip(description: String, actions: List<MigrationAction>): String? =
        (MigrationParser.parse(MigrationParser.exportToJson("draft", description, actions)) as? MigrationValidationResult.Invalid)?.reason
}
