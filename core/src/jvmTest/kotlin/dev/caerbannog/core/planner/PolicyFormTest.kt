package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.DOT_NAME
import dev.caerbannog.core.migrations.MigrationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the policy form (docs/tasks/M9-08-policy-step.md). API fixed here, beyond the brief (the architect confirms it):
 *   data class PolicySetting(val key: String, val value: String) { fun typedValue(): ArgumentValue? }   // null: the typed text is not a value of the key's kind
 *   data class PolicyForm(val name: String, val pattern: String, val target: PolicyTarget = PolicyTarget.ALL, val priority: String = "0",
 *                         val settings: List<PolicySetting> = emptyList()) : StepForm {
 *       fun priorityValue(): Int?; fun withTarget(target: PolicyTarget): PolicyForm }
 *   problems: `FieldProblem(field, message)`, fields `name`, `pattern`, `settings`, `setting:<i>` (0-based row), `priority`, ONE per field, in this order.
 *   hints: `FieldHint("pattern", <the match line>)` and `FieldHint("target", "Only settings that every kind of queue honours are offered here")` for QUEUES and ALL.
 *   `FormContext` gets `connectionName: String? = null` (the match line says `on "uat-eu"`; the form has no other way to know the connection).
 */
class PolicyFormTest {

    private fun form(
        name: String = "orders-limits",
        pattern: String = "^orders",
        target: PolicyTarget = PolicyTarget.QUORUM_QUEUES,
        priority: String = "0",
        vararg settings: Pair<String, String>,
    ) = PolicyForm(name, pattern, target, priority, settings.map { PolicySetting(it.first, it.second) })

    private val valid = form(settings = arrayOf("max-length" to "1000"))

    private fun problems(f: PolicyForm, c: FormContext = ctx()) = f.problems(c).map { it.field to it.message }

    // ---- defaults and the step it builds ----

    @Test
    fun `a form starts for all, priority 0, no setting`() {
        val f = PolicyForm(name = "p", pattern = "x")
        assertEquals(PolicyTarget.ALL, f.target)
        assertEquals("0", f.priority)
        assertEquals(emptyList(), f.settings)
    }

    @Test
    fun `the form builds one declarePolicy with the target's wire text, the priority and the typed definition`() {
        val f = form(
            name = "orders-limits", pattern = "^orders", target = PolicyTarget.QUORUM_QUEUES, priority = "1",
            settings = arrayOf("max-length" to "1000", "overflow" to "reject-publish"),
        )
        assertEquals(
            listOf(
                MigrationAction.DeclarePolicy(
                    name = "orders-limits", pattern = "^orders",
                    definition = mapOf("max-length" to ArgumentValue.Whole(1000), "overflow" to ArgumentValue.Text("reject-publish")),
                    vhost = "uat", applyTo = "quorum_queues", priority = 1,
                ),
            ),
            f.actions(ctx(plan = TopologyPlan(vhost = "uat"))),
        )
    }

    @Test
    fun `the step lists its detail the way the scenario reads it`() {
        val action = form(priority = "1", settings = arrayOf("max-length" to "1000", "overflow" to "reject-publish")).actions(ctx()).single()
        assertEquals("Declare policy orders-limits", action.description)
        assertEquals("apply-to=quorum_queues, priority=1; max-length=1000, overflow=reject-publish", action.detail)
    }

    @Test
    fun `the operator policy is never built, whatever the target`() {
        PolicyTarget.entries.forEach { target ->
            val entry = PolicyKeyCatalog.offeredFor(target).first()
            val sample = when (val kind = entry.kind) {
                is PolicyValueKind.WholeNumber -> kind.min.toString()
                PolicyValueKind.Duration -> "7D"
                PolicyValueKind.Text -> "x"
                is PolicyValueKind.Choice -> kind.values.first().wire
            }
            val actions = PolicyForm("p", "^x", target, "0", listOf(PolicySetting(entry.key, sample))).actions(ctx())
            assertEquals(1, actions.size, "$target with ${entry.key}=$sample")
            assertTrue(actions.single() is MigrationAction.DeclarePolicy, "$target built ${actions.single()::class.simpleName}")
        }
    }

    @Test
    fun `no action while there is a problem`() {
        assertEquals(emptyList(), form(name = "").actions(ctx()))
        assertEquals(emptyList(), form(settings = arrayOf("max-length" to "many")).actions(ctx()))
    }

    // ---- typed values ----

    @Test
    fun `a whole number is typed as a number, never as text`() {
        assertEquals(ArgumentValue.Whole(1000), PolicySetting("max-length", "1000").typedValue())
        assertEquals(ArgumentValue.Whole(0), PolicySetting("max-length", "0").typedValue(), "0 is allowed for max-length: the form lets it through, the apply reports the broker's answer")
        assertEquals(ArgumentValue.Whole(10_000_000_000), PolicySetting("max-length-bytes", "10000000000").typedValue(), "more than an Int")
    }

    @Test
    fun `a text value is text`() {
        assertEquals(ArgumentValue.Text("orders-dlx"), PolicySetting("dead-letter-exchange", "orders-dlx").typedValue())
        assertEquals(ArgumentValue.Text("reject-publish"), PolicySetting("overflow", "reject-publish").typedValue())
        assertEquals(ArgumentValue.Text("balanced"), PolicySetting("queue-leader-locator", "balanced").typedValue())
    }

    @Test
    fun `max-age is text and never a number, whatever was typed (S-F1c, a JSON number is a 500 with an empty body)`() {
        assertEquals(ArgumentValue.Text("7D"), PolicySetting("max-age", "7D").typedValue())
        assertEquals(ArgumentValue.Text("1Y"), PolicySetting("max-age", "1Y").typedValue())
        assertEquals(ArgumentValue.Text("1000s"), PolicySetting("max-age", "1000s").typedValue())
        listOf("7", "604800", "0").forEach { digits ->
            assertNull(PolicySetting("max-age", digits).typedValue(), "\"$digits\" is not an age: it is refused, never sent as a number")
        }
        val actions = form(target = PolicyTarget.STREAMS, settings = arrayOf("max-age" to "7D")).actions(ctx())
        assertTrue((actions.single() as MigrationAction.DeclarePolicy).definition["max-age"] is ArgumentValue.Text)
    }

    @Test
    fun `the measured units are accepted and every other text is not`() {
        listOf("1Y", "1M", "7D", "1h", "90m", "1000s", "12D").forEach { assertEquals(ArgumentValue.Text(it), PolicySetting("max-age", it).typedValue(), it) }
        listOf("7d", "7H", "7S", "D", "Y", "", " 7D", "7D ", "7 D", "-1D", "1.5D", "7DD", "D7", "abc", "7x", "٣D").forEach {
            assertNull(PolicySetting("max-age", it).typedValue(), "\"$it\"")
        }
    }

    @Test
    fun `a whole number refuses everything that is not ASCII digits and honours the minimum of its key`() {
        listOf("", "-1", "1.5", "1e3", " 5", "5 ", "+5", "many", "٣", "99999999999999999999").forEach {
            assertNull(PolicySetting("max-length", it).typedValue(), "\"$it\"")
        }
        assertNull(PolicySetting("expires", "0").typedValue(), "expires 0 is a 400")
        assertEquals(ArgumentValue.Whole(1), PolicySetting("expires", "1").typedValue())
        assertNull(PolicySetting("target-group-size", "0").typedValue())
        assertNull(PolicySetting("stream-max-segment-size-bytes", "0").typedValue())
    }

    @Test
    fun `a choice is one of its measured values`() {
        assertEquals(ArgumentValue.Text("reject-publish-dlx"), PolicySetting("overflow", "reject-publish-dlx").typedValue())
        assertNull(PolicySetting("overflow", "bogus").typedValue(), "bogus is a 400 on the broker")
        assertNull(PolicySetting("overflow", "Drop-Head").typedValue())
        assertNull(PolicySetting("queue-leader-locator", "client-local").typedValue(), "not measured for a policy, not offered")
        assertNull(PolicySetting("dead-letter-strategy", "at-most-once").typedValue())
    }

    @Test
    fun `a key outside the catalog has no typed value`() {
        assertNull(PolicySetting("x-made-up", "1").typedValue())
    }

    // ---- priority ----

    @Test
    fun `the priority is a whole number within Int, negative allowed`() {
        assertEquals(0, PolicyForm("p", "x").priorityValue())
        assertEquals(10, form(priority = "10").priorityValue())
        assertEquals(-5, form(priority = "-5").priorityValue())
        assertEquals(Int.MAX_VALUE, form(priority = "2147483647").priorityValue())
        assertEquals(Int.MIN_VALUE, form(priority = "-2147483648").priorityValue())
        listOf("2147483648", "-2147483649", "high", "", " 1", "1 ", "1.5", "+1", "--1", "-", "٣").forEach { assertNull(form(priority = it).priorityValue(), "\"$it\"") }
    }

    // ---- problems: fixed messages, one per field, in order ----

    @Test
    fun `a valid form has no problem`() {
        assertEquals(emptyList(), problems(valid))
    }

    @Test
    fun `the messages of the scenario outline`() {
        assertEquals(listOf("name" to "Give the policy a name"), problems(valid.copy(name = "")))
        assertEquals(listOf("pattern" to "Enter a pattern"), problems(valid.copy(pattern = "")))
        assertEquals(listOf("settings" to "Add at least one setting"), problems(valid.copy(settings = emptyList())))
        assertEquals(listOf("setting:0" to "Enter a whole number"), problems(form(settings = arrayOf("max-length" to "many"))))
        assertEquals(listOf("priority" to "Enter a whole number"), problems(valid.copy(priority = "high")))
    }

    @Test
    fun `a duration that is not digits and a measured unit says what to type`() {
        assertEquals(
            listOf("setting:0" to "Enter a number and a unit, for example 7D"),
            problems(form(target = PolicyTarget.STREAMS, settings = arrayOf("max-age" to "7d"))),
        )
    }

    @Test
    fun `problems come one per field in the order name, pattern, settings, each row, priority`() {
        val all = PolicyForm(name = "", pattern = "", target = PolicyTarget.QUORUM_QUEUES, priority = "x", settings = emptyList())
        assertEquals(
            listOf("name" to "Give the policy a name", "pattern" to "Enter a pattern", "settings" to "Add at least one setting", "priority" to "Enter a whole number"),
            problems(all),
        )
        val rows = PolicyForm(
            name = "p", pattern = "x", target = PolicyTarget.QUORUM_QUEUES, priority = "x",
            settings = listOf(PolicySetting("max-length", "a"), PolicySetting("message-ttl", "5"), PolicySetting("delivery-limit", "b")),
        )
        assertEquals(listOf("setting:0" to "Enter a whole number", "setting:2" to "Enter a whole number", "priority" to "Enter a whole number"), problems(rows))
    }

    @Test
    fun `a value below the minimum of its key is refused like a text that is no number`() {
        assertEquals(listOf("setting:0" to "Enter a whole number"), problems(form(settings = arrayOf("expires" to "0"))))
        assertEquals(emptyList(), problems(form(settings = arrayOf("expires" to "1"))))
    }

    @Test
    fun `a boundary the spike did not try is let through, the apply reports the broker's answer`() {
        assertEquals(emptyList(), problems(form(settings = arrayOf("max-length" to "0"))))
        assertEquals(emptyList(), problems(form(settings = arrayOf("message-ttl" to "0"))))
        assertEquals(emptyList(), problems(form(settings = arrayOf("delivery-limit" to "0"))))
        assertEquals(emptyList(), problems(form(settings = arrayOf("max-length-bytes" to "0"))))
    }

    @Test
    fun `a key used in two rows is refused on the later row`() {
        val f = form(settings = arrayOf("max-length" to "1", "message-ttl" to "5", "max-length" to "2"))
        assertEquals(listOf("setting:2" to "Each setting can be added once"), problems(f))
    }

    @Test
    fun `a name goes through the name check, but the amq prefix is not blocked for a policy (S-H, the broker answers 201)`() {
        assertEquals(emptyList(), problems(valid.copy(name = "amq.x")), "a policy named amq.x is accepted by the broker and must not be blocked")
        assertEquals(emptyList(), problems(valid.copy(name = "amq.rabbitmq.policy")))
        assertEquals(listOf("name" to DOT_NAME), problems(valid.copy(name = ".")))
        assertEquals(listOf("name" to DOT_NAME), problems(valid.copy(name = "..")))
    }

    @Test
    fun `a pattern is never refused for its content, only for being empty`() {
        listOf("(?=orders)", "(", "[", "*", "\\1", "a{2,", ".*", "^$", "(.*a){40}", "x".repeat(5000)).forEach {
            assertEquals(emptyList(), problems(valid.copy(pattern = it)), "pattern <${it.take(20)}>")
        }
    }

    @Test
    fun `a name of 300 characters or with a quote does not break the form`() {
        // the parser round trip decides; the form never throws
        valid.copy(name = "n".repeat(300)).problems(ctx())
        valid.copy(name = "a\"b\\c\nd").problems(ctx())
    }

    // ---- the target and its rows ----

    @Test
    fun `choosing another target removes the rows whose key is no longer offered, and keeps the others`() {
        val f = form(
            target = PolicyTarget.QUORUM_QUEUES,
            settings = arrayOf("max-length" to "1000", "delivery-limit" to "5", "max-length-bytes" to "99", "queue-leader-locator" to "balanced"),
        )
        val streams = f.withTarget(PolicyTarget.STREAMS)
        assertEquals(PolicyTarget.STREAMS, streams.target)
        assertEquals(listOf(PolicySetting("max-length-bytes", "99"), PolicySetting("queue-leader-locator", "balanced")), streams.settings, "rows are removed, not kept hidden")
        assertEquals(PolicyTarget.CLASSIC_QUEUES, f.withTarget(PolicyTarget.CLASSIC_QUEUES).target)
        assertEquals(listOf("max-length", "max-length-bytes", "queue-leader-locator"), f.withTarget(PolicyTarget.CLASSIC_QUEUES).settings.map { it.key })
    }

    @Test
    fun `all and queues keep only the three keys every kind of queue honours`() {
        val f = form(settings = arrayOf("max-length" to "1", "max-length-bytes" to "2", "alternate-exchange" to "ae"))
        listOf(PolicyTarget.ALL, PolicyTarget.QUEUES).forEach { assertEquals(listOf("max-length-bytes", "alternate-exchange"), f.withTarget(it).settings.map { s -> s.key }) }
    }

    @Test
    fun `choosing the same target drops nothing, and a value typed in a kept row stays as typed`() {
        val f = form(settings = arrayOf("max-length" to "12x"))
        assertEquals(f, f.withTarget(PolicyTarget.QUORUM_QUEUES))
    }

    @Test
    fun `the exchanges target keeps only the alternate exchange`() {
        val f = form(target = PolicyTarget.ALL, settings = arrayOf("alternate-exchange" to "spare", "max-length-bytes" to "5"))
        assertEquals(listOf("alternate-exchange"), f.withTarget(PolicyTarget.EXCHANGES).settings.map { it.key })
        assertEquals("exchanges", f.withTarget(PolicyTarget.EXCHANGES).actions(ctx()).filterIsInstance<MigrationAction.DeclarePolicy>().single().applyTo)
    }

    @Test
    fun `the sentence for queues and all is a hint, never a problem`() {
        listOf(PolicyTarget.QUEUES, PolicyTarget.ALL).forEach { target ->
            val f = form(target = target, settings = arrayOf("max-length-bytes" to "5"))
            assertEquals(
                listOf(FieldHint("target", "Only settings that every kind of queue honours are offered here")),
                f.hints(ctx()).filter { it.field == "target" },
            )
            assertEquals(emptyList(), problems(f))
        }
        listOf(PolicyTarget.QUORUM_QUEUES, PolicyTarget.CLASSIC_QUEUES, PolicyTarget.STREAMS, PolicyTarget.EXCHANGES).forEach {
            assertEquals(emptyList(), form(target = it).hints(ctx()).filter { h -> h.field == "target" }, "$it")
        }
    }

    // ---- the match line ----

    private fun context(plan: TopologyPlan = TopologyPlan(), known: KnownTopology = KnownTopology.None, connection: String? = "uat-eu") =
        FormContext(plan = plan, namingStyle = NamingStyle.KEBAB, known = known, connectionName = connection)

    private fun matchLine(f: PolicyForm, c: FormContext) = f.hints(c).filter { it.field == "pattern" }.map { it.message }

    @Test
    fun `the line names the matches in this plan and on the broker, as the scenario words it`() {
        val plan = planOf(MigrationAction.DeclareQueue("orders"), MigrationAction.DeclareQueue("invoices"))
        val c = context(plan, KnownTopology(queues = setOf("orders-archive")))
        assertEquals(listOf("""matches "orders" in this plan and "orders-archive" on "uat-eu""""), matchLine(valid, c))
    }

    @Test
    fun `a stream of the plan and a stream of the broker match like a queue`() {
        val plan = planOf(MigrationAction.DeclareStream("orders-events"))
        val c = context(plan, KnownTopology(streams = setOf("orders-log")))
        assertEquals(listOf("""matches "orders-events" in this plan and "orders-log" on "uat-eu""""), matchLine(valid, c))
    }

    @Test
    fun `only this plan matches, or only the broker`() {
        val onlyPlan = context(planOf(MigrationAction.DeclareQueue("orders")), KnownTopology(queues = setOf("invoices")))
        assertEquals(listOf("""matches "orders" in this plan"""), matchLine(valid, onlyPlan))
        val onlyBroker = context(planOf(MigrationAction.DeclareQueue("invoices")), KnownTopology(queues = setOf("orders-archive")))
        assertEquals(listOf("""matches "orders-archive" on "uat-eu""""), matchLine(valid, onlyBroker))
    }

    @Test
    fun `nothing matched says so`() {
        val c = context(planOf(MigrationAction.DeclareQueue("invoices")), KnownTopology(queues = setOf("billing")))
        assertEquals(listOf("Matches no name yet"), matchLine(valid, c))
        assertEquals(listOf("Matches no name yet"), matchLine(valid, context()), "an empty plan on a broker that was read (it knows nothing here)")
    }

    @Test
    fun `when the broker was not read the broker part is dropped and no claim is made about it`() {
        val c = FormContext(plan = planOf(MigrationAction.DeclareQueue("orders")), namingStyle = NamingStyle.KEBAB, known = KnownTopology.None, connectionName = null)
        assertEquals(listOf("""matches "orders" in this plan"""), matchLine(valid, c))
    }

    @Test
    fun `an exchange of the plan is not a queue name`() {
        val c = context(planOf(MigrationAction.DeclareExchange("orders")), KnownTopology(exchanges = mapOf("orders-x" to "direct")))
        assertEquals(listOf("Matches no name yet"), matchLine(valid, c))
    }

    @Test
    fun `a pattern the matcher cannot evaluate gets the unevaluable line and the form can still be added`() {
        val f = valid.copy(pattern = "(?=orders)")
        assertEquals(listOf("The app cannot tell which names this pattern matches"), matchLine(f, context(planOf(MigrationAction.DeclareQueue("orders")))))
        assertEquals(emptyList(), problems(f), "the form is not blocked")
        assertEquals(1, f.actions(ctx()).size)
    }

    @Test
    fun `no line while the pattern is empty`() {
        assertEquals(emptyList(), matchLine(valid.copy(pattern = ""), context(planOf(MigrationAction.DeclareQueue("orders")))))
    }

    @Test
    fun `at most ten names per source, then and N more`() {
        val names = (1..12).map { "orders-%02d".format(it) }
        val plan = planOf(*names.map { MigrationAction.DeclareQueue(it) as MigrationAction }.toTypedArray())
        val line = matchLine(valid, context(plan)).single()
        names.take(10).forEach { assertTrue("\"$it\"" in line, "$it is missing in: $line") }
        names.drop(10).forEach { assertFalse("\"$it\"" in line, "$it must be folded into the count: $line") }
        assertTrue("and 2 more" in line, line)
        val brokerLine = matchLine(valid, context(known = KnownTopology(queues = names.toSet()))).single()
        assertTrue("and 2 more" in brokerLine, brokerLine)
        val exactlyTen = matchLine(valid, context(known = KnownTopology(queues = names.take(10).toSet()))).single()
        assertFalse("more" in exactlyTen, "ten names are all shown: $exactlyTen")
    }

    // Format decided by the architect on 2026-11-17 (brief M9-08, "Step 2: contract confirmed", gap 4): each source lists its names quoted, in the order found:
    // `"a"`, `"a" and "b"`, `"a", "b" and "c"`; ten names at most, then `, ... and N more`; the sentence is `matches <plan list> in this plan and <broker list> on "<connection>"`.

    @Test
    fun `three names are joined with commas and a last and, in the order found, in both sources`() {
        val plan = planOf(MigrationAction.DeclareQueue("orders-a"), MigrationAction.DeclareQueue("orders-b"), MigrationAction.DeclareQueue("orders-c"))
        assertEquals(listOf("""matches "orders-a", "orders-b" and "orders-c" in this plan"""), matchLine(valid, context(plan)))
        val c = context(plan, KnownTopology(queues = linkedSetOf("orders-x", "orders-y", "orders-z")))
        assertEquals(
            listOf("""matches "orders-a", "orders-b" and "orders-c" in this plan and "orders-x", "orders-y" and "orders-z" on "uat-eu""""),
            matchLine(valid, c),
        )
    }

    @Test
    fun `a name found in both sources is shown in both, not merged`() {
        val c = context(planOf(MigrationAction.DeclareQueue("orders")), KnownTopology(queues = setOf("orders")))
        assertEquals(listOf("""matches "orders" in this plan and "orders" on "uat-eu""""), matchLine(valid, c))
    }

    @Test
    fun `two names and the cap of ten, in full`() {
        val ten = ('a'..'j').map { "orders-$it" }
        val plan12 = planOf(*(ten + listOf("orders-k", "orders-l")).map { MigrationAction.DeclareQueue(it) as MigrationAction }.toTypedArray())
        val quotedTen = ten.joinToString(", ") { "\"$it\"" }.let { it.substringBeforeLast(", ") + " and " + it.substringAfterLast(", ") }
        // over the cap: all ten names comma-joined, then `and N more` (N = names left out)
        val firstTen = ten.joinToString(", ") { "\"$it\"" }
        assertEquals(listOf("""matches $firstTen and 2 more in this plan"""), matchLine(valid, context(plan12)))
        val twoNames = context(planOf(MigrationAction.DeclareQueue("orders-a"), MigrationAction.DeclareQueue("orders-b")), KnownTopology(queues = (ten + "orders-k" + "orders-l").toCollection(linkedSetOf())))
        assertEquals(
            listOf("""matches "orders-a" and "orders-b" in this plan and $firstTen and 2 more on "uat-eu""""),
            matchLine(valid, twoNames),
        )
        val exactlyTen = planOf(*ten.map { MigrationAction.DeclareQueue(it) as MigrationAction }.toTypedArray())
        assertEquals(listOf("""matches $quotedTen in this plan"""), matchLine(valid, context(exactlyTen)), "exactly ten names shows no count")
    }

    @Test
    fun `a hostile pattern cannot hang the hint`() {
        val f = valid.copy(pattern = "(.*a){40}")
        val names = (1..200).map { "a".repeat(50) + it }
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
            assertTrue(matchLine(f, context(known = KnownTopology(queues = names.toSet()))).isNotEmpty())
        }
    }

    @Test
    fun `a name echoed from the broker with a quote or a line break is not printed as typed`() {
        val c = context(known = KnownTopology(queues = setOf("orders\"; DROP\nx")))
        val line = matchLine(valid, c).single()
        assertFalse('\n' in line, "no line break in the sentence: $line")
        assertFalse("DROP" in line, "a name that cannot be echoed is not echoed: $line")
    }
}
