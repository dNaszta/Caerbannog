package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType

/** Where the dead letters of the queue are kept. A stream keeps them and they can be browsed. */
enum class DeadLetterTarget { Queue, Stream }

/** How sure the broker is to pass a dead letter on. At least once needs a quorum queue and the refused-publishers setting that comes with it. */
enum class DeadLetterStrategy { AtMostOnce, AtLeastOnce }

/**
 * The dead-lettering recipe (M9-09): for the existing queue [queue] it adds four ordinary steps in a safe order, the dead-letter exchange, the
 * dead-letter queue (or stream), the binding between them, and last a policy on exactly [queue]. The queue itself is never declared or changed.
 * Nothing remembers afterwards that the four steps belong together (A1).
 *
 * [exchangeName] and [targetName] null mean "follows the queue name"; any string, even an empty one, means the user typed it (as
 * [StreamForm.responseName]). [publishersRefusedAccepted] is the tick that At least once needs; it is cleared with [withStrategy].
 */
data class DeadLetterForm(
    val queue: String,
    val target: DeadLetterTarget = DeadLetterTarget.Queue,
    val exchangeName: String? = null,
    val targetName: String? = null,
    val strategy: DeadLetterStrategy = DeadLetterStrategy.AtMostOnce,
    val publishersRefusedAccepted: Boolean = false,
) : StepForm {
    fun suggestedExchangeName(ctx: FormContext): String = if (queue.isEmpty()) "" else PlanNaming.derived(queue, DerivedWord.DLX, ctx.namingStyle)

    fun suggestedTargetName(ctx: FormContext): String = if (queue.isEmpty()) "" else PlanNaming.derived(queue, DerivedWord.DLQ, ctx.namingStyle)

    fun effectiveExchangeName(ctx: FormContext): String = exchangeName ?: suggestedExchangeName(ctx)

    fun effectiveTargetName(ctx: FormContext): String = targetName ?: suggestedTargetName(ctx)

    /** At least once is offered only for a queue known to be a quorum queue (S-F1b: one key the type ignores and the policy binds to nothing). */
    fun offersAtLeastOnce(ctx: FormContext): Boolean = ctx.queueType(queue) == "quorum"

    /** Another strategy; At most once also takes the tick away, so a later switch back to At least once asks again. */
    fun withStrategy(strategy: DeadLetterStrategy): DeadLetterForm =
        copy(strategy = strategy, publishersRefusedAccepted = strategy == DeadLetterStrategy.AtLeastOnce && publishersRefusedAccepted)

    /** One message per field, in this order: queue, exchange, targetName, strategy, refusedTick. */
    override fun problems(ctx: FormContext): List<FieldProblem> = buildList {
        queueProblem(ctx)?.let { add(FieldProblem("queue", it)) }
        // A name that follows an empty queue is not a second problem: the queue field says what to do. A typed name, even an empty one, is judged.
        if (exchangeName != null || queue.isNotEmpty()) {
            addAll(
                FormChecks.nameProblem(effectiveExchangeName(ctx), PlannerTexts.EXCHANGE_NAME_MISSING, "exchange") { exchange(effectiveExchangeName(ctx), ctx) },
            )
        }
        if (targetName != null || queue.isNotEmpty()) {
            val missing = if (target == DeadLetterTarget.Stream) PlannerTexts.STREAM_NAME_MISSING else PlannerTexts.QUEUE_NAME_MISSING
            addAll(
                FormChecks.nameProblem(effectiveTargetName(ctx), missing, "targetName", isQueue = target == DeadLetterTarget.Queue) {
                    targetDeclaration(effectiveTargetName(ctx), ctx)
                },
            )
        }
        if (strategy == DeadLetterStrategy.AtLeastOnce) {
            if (!offersAtLeastOnce(ctx)) {
                add(FieldProblem("strategy", PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_UNAVAILABLE))
            } else if (!publishersRefusedAccepted) {
                add(FieldProblem("refusedTick", PlannerTexts.DEAD_LETTER_TICK_REQUIRED))
            }
        }
    }

    private fun queueProblem(ctx: FormContext): String? = when {
        queue.isEmpty() -> PlannerTexts.DEAD_LETTER_QUEUE_MISSING
        ctx.queueType(queue) == "stream" -> PlannerTexts.DEAD_LETTER_SOURCE_IS_STREAM
        else -> null
    }

    override fun actions(ctx: FormContext): List<MigrationAction> {
        if (problems(ctx).isNotEmpty()) return emptyList()
        val exchange = effectiveExchangeName(ctx)
        val destination = effectiveTargetName(ctx)
        return listOf(
            exchange(exchange, ctx),
            targetDeclaration(destination, ctx),
            // A stream is bound as a queue destination, like every binding of a stream in the planner; a fanout exchange ignores the key.
            MigrationAction.DeclareBinding(
                exchange = exchange, queue = destination, routingKey = "", vhost = ctx.plan.vhost, destinationType = BindingDestinationType.QUEUE,
            ),
            policy(exchange, ctx),
        )
    }

    /** The default says that a dead letter can be lost; At least once says what it keeps and what the tick changes. Notes about the queue come from what the broker has. */
    override fun hints(ctx: FormContext): List<FieldHint> = buildList {
        ctx.known.queueDetails[queue]?.ownDeadLetterExchange?.let { add(FieldHint("queue", PlannerTexts.deadLetterOwnArgument(queue, it))) }
        // The suggestions follow the queue, so only a name typed by hand gets the naming advice (as the stream form does).
        exchangeName?.let { name -> addAll(FormChecks.nameHint(name, ctx, "exchange") { name !in ctx.known.exchanges }) }
        targetName?.let { name -> addAll(FormChecks.nameHint(name, ctx, "targetName") { name !in ctx.known.queues && name !in ctx.known.streams }) }
        when (strategy) {
            DeadLetterStrategy.AtMostOnce -> add(FieldHint("strategy", PlannerTexts.DEAD_LETTER_DEFAULT_NOTE))
            DeadLetterStrategy.AtLeastOnce -> {
                add(FieldHint("strategy", PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE))
                add(FieldHint("refusedTick", PlannerTexts.DEAD_LETTER_FULL_QUEUE_NOTE))
            }
        }
    }

    /**
     * The policy step as the form would add it, with the priority the recipe always uses (0). The view model asks the broker's policy notes
     * about it, so the form and the note cannot judge two different policies. Call it only for a queue that is not empty.
     */
    internal fun policyProbe(ctx: FormContext): MigrationAction.DeclarePolicy = policy(effectiveExchangeName(ctx), ctx)

    private fun exchange(name: String, ctx: FormContext) = MigrationAction.DeclareExchange(name = name, type = "fanout", vhost = ctx.plan.vhost)

    private fun targetDeclaration(name: String, ctx: FormContext): MigrationAction = when (target) {
        DeadLetterTarget.Queue -> MigrationAction.DeclareQueue(name = name, type = "quorum", vhost = ctx.plan.vhost)
        DeadLetterTarget.Stream -> MigrationAction.DeclareStream(name = name, vhost = ctx.plan.vhost)
    }

    private fun policy(exchange: String, ctx: FormContext) = MigrationAction.DeclarePolicy(
        name = exchange,
        pattern = ExactPattern.of(listOf(queue)),
        definition = definition(exchange),
        vhost = ctx.plan.vhost,
        applyTo = applyTo(ctx).wire,
        priority = 0,
    )

    private fun applyTo(ctx: FormContext): PolicyTarget = when (ctx.queueType(queue)) {
        "quorum" -> PolicyTarget.QUORUM_QUEUES
        "classic" -> PolicyTarget.CLASSIC_QUEUES
        else -> PolicyTarget.QUEUES
    }

    /** At least once always holds both keys together (S-C4: the strategy without `reject-publish` is accepted and holds nothing); the one place that builds them. */
    private fun definition(exchange: String): Map<String, ArgumentValue> = buildMap {
        put("dead-letter-exchange", ArgumentValue.Text(exchange))
        if (strategy == DeadLetterStrategy.AtLeastOnce) {
            put("dead-letter-strategy", ArgumentValue.Text("at-least-once"))
            put("overflow", ArgumentValue.Text("reject-publish"))
        }
    }
}
