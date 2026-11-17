package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-09 step 2, written first (docs/tasks/M9-09-dead-lettering-recipe.md). RED until `DeadLetterForm` exists. What the brief fixes: the data class, its six fields and
 * defaults, the enums `DeadLetterTarget { Queue, Stream }` and `DeadLetterStrategy { AtMostOnce, AtLeastOnce }`, the four actions in order, `applyTo` by queue type,
 * the two at-least-once keys together, the `PlannerTexts` constants. What the brief leaves open and THIS FILE FIXES (the architect confirms in the report):
 *   fun suggestedExchangeName(ctx: FormContext): String   // PlanNaming.derived(queue, DLX, style); "" while the queue is empty
 *   fun suggestedTargetName(ctx: FormContext): String     // PlanNaming.derived(queue, DLQ, style); "" while the queue is empty
 *   fun effectiveExchangeName(ctx) / effectiveTargetName(ctx)   // exchangeName ?: suggested, targetName ?: suggested (as StreamForm.effectiveResponseName)
 *   fun offersAtLeastOnce(ctx: FormContext): Boolean      // ctx.queueType(queue) == "quorum"
 *   fun withStrategy(strategy: DeadLetterStrategy): DeadLetterForm   // AtMostOnce also clears publishersRefusedAccepted
 *   problems: one per field, field names `queue`, `exchange`, `targetName`, `refusedTick`, in this order;
 *     queue empty -> DEAD_LETTER_QUEUE_MISSING; queue is a stream (plan or broker) -> DEAD_LETTER_SOURCE_IS_STREAM;
 *     exchange empty -> EXCHANGE_NAME_MISSING; target empty -> QUEUE_NAME_MISSING (Queue) / STREAM_NAME_MISSING (Stream);
 *     AtLeastOnce without the tick -> DEAD_LETTER_TICK_REQUIRED on `refusedTick`.
 *   hints: FieldHint("strategy", DEAD_LETTER_DEFAULT_NOTE) for AtMostOnce; FieldHint("strategy", DEAD_LETTER_AT_LEAST_ONCE_NOTE) and
 *     FieldHint("refusedTick", DEAD_LETTER_FULL_QUEUE_NOTE) for AtLeastOnce; FieldHint("queue", deadLetterOwnArgument(queue, own exchange)) when
 *     `known.queueDetails[queue]?.ownDeadLetterExchange` is not null.
 */
class DeadLetterFormTest {
    private fun known(type: String, own: String? = null, name: String = "orders") = KnownTopology(
        queues = if (type == "stream") emptySet() else setOf(name),
        streams = if (type == "stream") setOf(name) else emptySet(),
        queueDetails = mapOf(name to KnownQueue(type, null, ownDeadLetterExchange = own)),
    )

    private val quorum = ctx(known = known("quorum"))
    private fun fields(f: DeadLetterForm, c: FormContext = quorum) = f.problems(c).map { it.field }

    private fun policyOf(f: DeadLetterForm, c: FormContext = quorum) = f.actions(c).last() as MigrationAction.DeclarePolicy

    // ---- defaults ----

    @Test
    fun `a form starts for a dead-letter queue, at most once, names follow the queue, nothing ticked`() {
        val f = DeadLetterForm(queue = "orders")
        assertEquals(DeadLetterTarget.Queue, f.target)
        assertNull(f.exchangeName)
        assertNull(f.targetName)
        assertEquals(DeadLetterStrategy.AtMostOnce, f.strategy)
        assertFalse(f.publishersRefusedAccepted)
    }

    // ---- the four actions ----

    @Test
    fun `a dead-letter queue gives the exchange, the queue, the binding and the policy, in this order`() {
        val actions = DeadLetterForm("orders").actions(ctx(plan = TopologyPlan(vhost = "uat"), known = known("quorum")))
        assertEquals(
            listOf(
                MigrationAction.DeclareExchange(name = "orders-dlx", type = "fanout", vhost = "uat"),
                MigrationAction.DeclareQueue(name = "orders-dlq", type = "quorum", vhost = "uat"),
                MigrationAction.DeclareBinding(exchange = "orders-dlx", queue = "orders-dlq", routingKey = "", vhost = "uat", destinationType = BindingDestinationType.QUEUE),
                MigrationAction.DeclarePolicy(
                    name = "orders-dlx", pattern = "^orders$", definition = mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx")),
                    vhost = "uat", applyTo = "quorum_queues", priority = 0,
                ),
            ),
            actions,
        )
    }

    @Test
    fun `a stream target declares a stream and binds it as a queue destination`() {
        val actions = DeadLetterForm("orders", target = DeadLetterTarget.Stream).actions(quorum)
        assertEquals(
            listOf("Declare fanout exchange orders-dlx", "Declare stream orders-dlq", "Bind orders-dlx to orders-dlq", "Declare policy orders-dlx"),
            actions.map { it.description },
        )
        assertEquals(MigrationAction.DeclareStream(name = "orders-dlq"), actions[1])
        assertEquals(BindingDestinationType.QUEUE, (actions[2] as MigrationAction.DeclareBinding).destinationType)
    }

    @Test
    fun `at least once adds both keys and only both, the exchange stays`() {
        val f = DeadLetterForm("orders", strategy = DeadLetterStrategy.AtLeastOnce, publishersRefusedAccepted = true)
        assertEquals(
            mapOf(
                "dead-letter-exchange" to ArgumentValue.Text("orders-dlx"),
                "dead-letter-strategy" to ArgumentValue.Text("at-least-once"),
                "overflow" to ArgumentValue.Text("reject-publish"),
            ),
            policyOf(f).definition,
        )
        assertEquals("quorum_queues", policyOf(f).applyTo)
    }

    @Test
    fun `at most once adds no strategy and no overflow`() {
        assertEquals(mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx")), policyOf(DeadLetterForm("orders")).definition)
    }

    @Test
    fun `the policy is named after the exchange, matches exactly the queue and has priority 0`() {
        val p = policyOf(DeadLetterForm("orders.eu", exchangeName = "eu-dlx", targetName = "eu-dlq"), ctx(known = known("quorum", name = "orders.eu")))
        assertEquals("eu-dlx", p.name)
        assertEquals(ExactPattern.of(listOf("orders.eu")), p.pattern)
        assertEquals(0, p.priority)
        assertEquals(ArgumentValue.Text("eu-dlx"), p.definition["dead-letter-exchange"])
    }

    @Test
    fun `the source queue is never declared or changed`() {
        val actions = DeadLetterForm("orders").actions(quorum)
        assertTrue(actions.none { it is MigrationAction.DeclareQueue && it.name == "orders" })
        assertTrue(actions.none { it is MigrationAction.DeclareStream && it.name == "orders" })
        assertEquals(4, actions.size)
    }

    // ---- applyTo by the type of the source queue ----

    @Test
    fun `applyTo follows the source queue's type, queues when it is not known`() {
        assertEquals(PolicyTarget.QUORUM_QUEUES.wire, policyOf(DeadLetterForm("orders"), ctx(known = known("quorum"))).applyTo)
        assertEquals(PolicyTarget.CLASSIC_QUEUES.wire, policyOf(DeadLetterForm("orders"), ctx(known = known("classic"))).applyTo)
        assertEquals(PolicyTarget.QUEUES.wire, policyOf(DeadLetterForm("orders"), ctx()).applyTo)
        assertEquals(PolicyTarget.QUEUES.wire, policyOf(DeadLetterForm("unlisted"), ctx(known = known("quorum"))).applyTo, "a name nobody knows")
    }

    @Test
    fun `a queue of this plan decides the type before the broker does`() {
        val plan = planOf(MigrationAction.DeclareQueue(name = "orders", type = "classic"))
        assertEquals("classic_queues", policyOf(DeadLetterForm("orders"), ctx(plan = plan, known = known("quorum"))).applyTo)
    }

    @Test
    fun `at least once is offered only for a known quorum queue`() {
        assertTrue(DeadLetterForm("orders").offersAtLeastOnce(ctx(known = known("quorum"))))
        assertFalse(DeadLetterForm("orders").offersAtLeastOnce(ctx(known = known("classic"))))
        assertFalse(DeadLetterForm("orders").offersAtLeastOnce(ctx()), "type unknown")
        assertFalse(DeadLetterForm("orders").offersAtLeastOnce(ctx(known = known("stream"))))
        assertTrue(DeadLetterForm("orders").offersAtLeastOnce(ctx(plan = planOf(MigrationAction.DeclareQueue(name = "orders", type = "quorum")))))
        assertFalse(DeadLetterForm("orders").offersAtLeastOnce(ctx(plan = planOf(MigrationAction.DeclareQueue(name = "orders", type = "classic")))))
    }

    // ---- names ----

    @Test
    fun `names are suggested with the suffixes in each naming style`() {
        val expected = mapOf(
            NamingStyle.KEBAB to ("orders-dlx" to "orders-dlq"),
            NamingStyle.SNAKE to ("orders_dlx" to "orders_dlq"),
            NamingStyle.DOT to ("orders.dlx" to "orders.dlq"),
            NamingStyle.CAMEL to ("ordersDlx" to "ordersDlq"),
            NamingStyle.NONE to ("orders-dlx" to "orders-dlq"),
        )
        expected.forEach { (style, names) ->
            val c = ctx(style = style)
            assertEquals(names.first, DeadLetterForm("orders").suggestedExchangeName(c), "exchange, $style")
            assertEquals(names.second, DeadLetterForm("orders").suggestedTargetName(c), "target, $style")
            assertEquals(names.first, PlanNaming.derived("orders", DerivedWord.DLX, style))
        }
    }

    @Test
    fun `no suggestion while the queue is empty`() {
        assertEquals("", DeadLetterForm("").suggestedExchangeName(ctx()))
        assertEquals("", DeadLetterForm("").suggestedTargetName(ctx()))
    }

    @Test
    fun `names follow the queue until the user edits them, an empty typed name stays typed`() {
        val f = DeadLetterForm("orders")
        assertEquals("invoices-dlx", f.copy(queue = "invoices").effectiveExchangeName(quorum))
        assertEquals("invoices-dlq", f.copy(queue = "invoices").effectiveTargetName(quorum))
        val typed = f.copy(exchangeName = "my-dlx", targetName = "my-dlq")
        assertEquals("my-dlx", typed.copy(queue = "invoices").effectiveExchangeName(quorum))
        assertEquals("my-dlq", typed.copy(queue = "invoices").effectiveTargetName(quorum))
        assertEquals("", f.copy(exchangeName = "").effectiveExchangeName(quorum), "typed empty is typed")
        assertEquals(listOf("exchange"), fields(f.copy(exchangeName = "")))
        assertEquals(listOf("targetName"), fields(f.copy(targetName = "")))
    }

    @Test
    fun `an empty name says what is missing, by target`() {
        assertEquals(PlannerTexts.EXCHANGE_NAME_MISSING, DeadLetterForm("orders", exchangeName = "").problems(quorum).single().message)
        assertEquals(PlannerTexts.QUEUE_NAME_MISSING, DeadLetterForm("orders", targetName = "").problems(quorum).single().message)
        assertEquals(PlannerTexts.STREAM_NAME_MISSING, DeadLetterForm("orders", target = DeadLetterTarget.Stream, targetName = "").problems(quorum).single().message)
    }

    @Test
    fun `a reserved or refused name is a problem of its own field`() {
        assertEquals(listOf("exchange"), fields(DeadLetterForm("orders", exchangeName = "amq.dead")))
        assertEquals(listOf("targetName"), fields(DeadLetterForm("orders", targetName = "amq.dead")))
        assertEquals(listOf("exchange"), fields(DeadLetterForm("orders", exchangeName = "..")))
    }

    // ---- the queue field ----

    @Test
    fun `an empty queue is the one problem and no step is built`() {
        val f = DeadLetterForm("")
        assertEquals(listOf(FieldProblem("queue", PlannerTexts.DEAD_LETTER_QUEUE_MISSING)), f.problems(quorum))
        assertEquals(emptyList(), f.actions(quorum))
    }

    @Test
    fun `a stream as the source is refused, from the broker and from the plan`() {
        val message = PlannerTexts.DEAD_LETTER_SOURCE_IS_STREAM
        assertEquals(listOf(FieldProblem("queue", message)), DeadLetterForm("orders").problems(ctx(known = known("stream"))))
        assertEquals(listOf(FieldProblem("queue", message)), DeadLetterForm("orders").problems(ctx(plan = planOf(MigrationAction.DeclareStream(name = "orders")))))
        assertEquals(emptyList(), DeadLetterForm("orders").actions(ctx(known = known("stream"))))
    }

    @Test
    fun `a name nobody knows is allowed`() {
        val f = DeadLetterForm("unlisted")
        assertEquals(emptyList(), f.problems(ctx()))
        assertEquals(4, f.actions(ctx()).size)
    }

    @Test
    fun `no action while any field has a problem`() {
        assertEquals(emptyList(), DeadLetterForm("orders", exchangeName = "").actions(quorum))
        assertEquals(emptyList(), DeadLetterForm("orders", targetName = "").actions(quorum))
    }

    // ---- the tick gate ----

    @Test
    fun `at least once without the tick is a problem of the tick, with the reason as written`() {
        val f = DeadLetterForm("orders", strategy = DeadLetterStrategy.AtLeastOnce)
        assertEquals(listOf(FieldProblem("refusedTick", """Tick "Publishers are refused when the queue is full" to use At least once""")), f.problems(quorum))
        assertEquals(PlannerTexts.DEAD_LETTER_TICK_REQUIRED, f.problems(quorum).single().message)
        assertEquals(emptyList(), f.actions(quorum))
        assertEquals(emptyList(), f.copy(publishersRefusedAccepted = true).problems(quorum))
    }

    @Test
    fun `switching back to at most once clears the tick, so a later switch asks again`() {
        val on = DeadLetterForm("orders").withStrategy(DeadLetterStrategy.AtLeastOnce).copy(publishersRefusedAccepted = true)
        val back = on.withStrategy(DeadLetterStrategy.AtMostOnce)
        assertEquals(DeadLetterStrategy.AtMostOnce, back.strategy)
        assertFalse(back.publishersRefusedAccepted)
        val again = back.withStrategy(DeadLetterStrategy.AtLeastOnce)
        assertEquals(listOf("refusedTick"), fields(again))
        assertEquals(mapOf("dead-letter-exchange" to ArgumentValue.Text("orders-dlx")), policyOf(back).definition)
    }

    @Test
    fun `withStrategy keeps every other field`() {
        val f = DeadLetterForm("orders", DeadLetterTarget.Stream, "x", "y")
        assertEquals(f.copy(strategy = DeadLetterStrategy.AtLeastOnce), f.withStrategy(DeadLetterStrategy.AtLeastOnce))
    }

    @Test
    fun `both at-least-once keys or neither, over every form state`() {
        val types = listOf<String?>("quorum", "classic", null)
        for (type in types) for (strategy in DeadLetterStrategy.entries) for (tick in listOf(false, true)) for (target in DeadLetterTarget.entries) {
            val c = if (type == null) ctx() else ctx(known = known(type))
            val f = DeadLetterForm("orders", target, strategy = strategy, publishersRefusedAccepted = tick)
            val state = "type=$type, $strategy, tick=$tick, $target"
            val actions = f.actions(c)
            if (actions.isEmpty()) continue
            val keys = policyOf(f, c).definition.keys
            val hasStrategy = "dead-letter-strategy" in keys
            assertEquals(hasStrategy, "overflow" in keys, "both or neither: $state")
            if (hasStrategy) {
                assertEquals("quorum", type, "at least once only for a quorum queue: $state")
                assertEquals("quorum_queues", policyOf(f, c).applyTo, state)
                assertTrue(tick, "only with the tick: $state")
            }
            assertEquals(strategy == DeadLetterStrategy.AtLeastOnce, hasStrategy, "the strategy decides: $state")
        }
    }

    // ---- hints (the notes of the form) ----

    @Test
    fun `the default says that a dead letter can be lost, word for word`() {
        assertEquals("If the dead-letter target cannot take a message, RabbitMQ drops it.", PlannerTexts.DEAD_LETTER_DEFAULT_NOTE)
        assertTrue(FieldHint("strategy", PlannerTexts.DEAD_LETTER_DEFAULT_NOTE) in DeadLetterForm("orders").hints(quorum))
        assertTrue(DeadLetterForm("orders").hints(quorum).none { it.message == PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE })
    }

    @Test
    fun `at least once says what it keeps and what the tick changes, word for word`() {
        assertEquals(
            "RabbitMQ keeps a message it cannot pass on and retries. Retries can take minutes, so do not rely on a quick delivery.",
            PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE,
        )
        assertEquals(
            "While the queue is full, publishers are refused. A quorum queue holds one message more than its limit.",
            PlannerTexts.DEAD_LETTER_FULL_QUEUE_NOTE,
        )
        val hints = DeadLetterForm("orders", strategy = DeadLetterStrategy.AtLeastOnce).hints(quorum)
        assertTrue(FieldHint("strategy", PlannerTexts.DEAD_LETTER_AT_LEAST_ONCE_NOTE) in hints, hints.toString())
        assertTrue(FieldHint("refusedTick", PlannerTexts.DEAD_LETTER_FULL_QUEUE_NOTE) in hints, hints.toString())
        assertTrue(hints.none { it.message == PlannerTexts.DEAD_LETTER_DEFAULT_NOTE })
    }

    @Test
    fun `a queue that declares its own dead-letter exchange is pointed out, word for word`() {
        val hints = DeadLetterForm("orders").hints(ctx(known = known("quorum", own = "legacy-dlx")))
        assertTrue(
            FieldHint("queue", "The queue orders names its own dead-letter exchange legacy-dlx. RabbitMQ uses that one and ignores this policy's exchange.") in hints,
            hints.toString(),
        )
    }

    @Test
    fun `no own-argument note without the argument, without a read, or for another queue`() {
        assertTrue(DeadLetterForm("orders").hints(quorum).none { it.field == "queue" })
        assertTrue(DeadLetterForm("orders").hints(ctx()).none { it.field == "queue" })
        assertTrue(DeadLetterForm("other").hints(ctx(known = known("quorum", own = "legacy-dlx"))).none { it.field == "queue" })
    }

    @Test
    fun `a hint never blocks, a note about the queue is not a problem`() {
        val c = ctx(known = known("quorum", own = "legacy-dlx"))
        assertEquals(emptyList(), DeadLetterForm("orders").problems(c))
        assertEquals(4, DeadLetterForm("orders").actions(c).size)
    }

    @Test
    fun `a new name the broker already has gets no naming hint, a new odd name does`() {
        val c = ctx(style = NamingStyle.KEBAB, known = known("quorum").copy(exchanges = mapOf("Odd_Name" to "fanout")))
        assertTrue(DeadLetterForm("orders", exchangeName = "Odd_Name").hints(c).none { it.field == "exchange" })
        assertTrue(DeadLetterForm("orders", exchangeName = "Odd_Name2").hints(c).any { it.field == "exchange" })
    }

    // ---- the plan the four steps make ----

    @Test
    fun `the four steps give none of the policy value notes`() {
        for (strategy in listOf(DeadLetterStrategy.AtMostOnce, DeadLetterStrategy.AtLeastOnce)) {
            val form = DeadLetterForm("orders", strategy = strategy, publishersRefusedAccepted = true)
            // a broker that was read and lacks the exchange: the exchange is declared by the step before the policy
            val c = ctx(known = known("quorum").copy(exchanges = mapOf("" to "direct", "amq.direct" to "direct")))
            val plan = TopologyPlan().append(form.actions(c))
            val problems = PlanValidation.problems(plan, c.known)
            assertEquals(emptyList(), problems.map { it.message }, "$strategy: the recipe's own steps need no note")
        }
    }

    @Test
    fun `a plan that declares the source queue gets the four steps after it, and has no problem`() {
        val base = planOf(MigrationAction.DeclareQueue(name = "orders", type = "quorum"))
        val c = ctx(plan = base)
        val plan = base.append(DeadLetterForm("orders").actions(c))
        assertEquals(
            listOf("Declare quorum queue orders", "Declare fanout exchange orders-dlx", "Declare quorum queue orders-dlq", "Bind orders-dlx to orders-dlq", "Declare policy orders-dlx"),
            plan.steps.map { it.action.description },
        )
        assertEquals(emptyList(), PlanValidation.problems(plan))
    }

    @Test
    fun `the form is a StepForm and the wizard kind is built`() {
        val form: StepForm = DeadLetterForm("orders")
        assertTrue(form.actions(quorum).isNotEmpty())
        assertTrue("Dead-lettering for a queue" in PlannerTexts.BUILT_KINDS)
        assertTrue("Dead-lettering for a queue" in PlannerTexts.WIZARD_KINDS)
        assertEquals("Exchange, dead-letter queue, binding and policy", PlannerTexts.DEAD_LETTER_KIND_DESCRIPTION)
    }
}
