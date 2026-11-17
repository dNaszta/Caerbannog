package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-11 step 2: the headers part of the binding form. RED until `StepForms.kt` has the API of the brief ("Design notes", pinned here):
 * `enum class MatchRule(val wire: String) { ALL("all"), ANY("any") }`, `enum class HeaderValueType { TEXT, WHOLE, FLAG }`,
 * `data class HeaderMatch(name, type = TEXT, value = "")` with `argumentValue(): ArgumentValue?`, and on `BindingForm`: `headers`, `matchRule = ALL`,
 * `heldRule: String? = null`.
 *
 * Names the brief leaves open, pinned here for the architect to confirm:
 *  - `BindingForm.withRule(rule: MatchRule): BindingForm` = `copy(matchRule = rule, heldRule = null)` ("choosing all or any clears heldRule");
 *  - `PlannerTexts.HEADERS_NONE_ALL`, `HEADERS_NONE_ANY` (decided texts), `HEADER_NAME_MISSING`, `HEADER_NAME_IS_MATCH_RULE`, `HEADER_LISTED_TWICE`,
 *    `HEADER_WHOLE_DIGITS_ONLY` (the four working refusals); every problem is `FieldProblem("headers", text)`, every hint `FieldHint("headers", text)`;
 *  - a problem is one per mistake: a name used twice is ONE problem (on the later row), an empty or `x-match` name is one per such row, rows in order;
 *  - an empty text value is a legal header value; an empty WHOLE value is "not a whole number" (refused).
 * Texts are asserted by their literal words as well, so a reword of the constant fails here and is a decision, not a refactoring.
 */
class BindingFormHeadersTest {
    private val headersPlan = planOf(
        MigrationAction.DeclareExchange(name = "documents", type = "headers"),
        MigrationAction.DeclareQueue(name = "reports"),
    )
    private val headersCtx = ctx(plan = headersPlan)

    private fun form(
        vararg headers: HeaderMatch,
        rule: MatchRule = MatchRule.ALL,
        exchange: String = "documents",
        key: String = "",
        held: String? = null,
    ) = BindingForm(exchange = exchange, destination = "reports", routingKey = key, headers = headers.toList(), matchRule = rule, heldRule = held)

    private fun text(name: String, value: String) = HeaderMatch(name, HeaderValueType.TEXT, value)
    private fun whole(name: String, value: String) = HeaderMatch(name, HeaderValueType.WHOLE, value)
    private fun flag(name: String, value: String) = HeaderMatch(name, HeaderValueType.FLAG, value)

    private fun arguments(f: BindingForm, c: FormContext = headersCtx) = (f.actions(c).single() as MigrationAction.DeclareBinding).arguments

    // ---- the model ----

    @Test
    fun `the start values are all, no header and no held rule`() {
        val f = BindingForm(exchange = "e", destination = "q")
        assertEquals(emptyList(), f.headers)
        assertEquals(MatchRule.ALL, f.matchRule)
        assertNull(f.heldRule)
        assertEquals("all", MatchRule.ALL.wire)
        assertEquals("any", MatchRule.ANY.wire)
        assertEquals(listOf(MatchRule.ALL, MatchRule.ANY), MatchRule.entries.toList(), "only the two measured rules are offered, all first")
        assertEquals(listOf(HeaderValueType.TEXT, HeaderValueType.WHOLE, HeaderValueType.FLAG), HeaderValueType.entries.toList())
        assertEquals(HeaderValueType.TEXT, HeaderMatch("a").type)
        assertEquals("", HeaderMatch("a").value)
    }

    @Test
    fun `a header value is typed text, a whole number or a boolean`() {
        assertEquals(ArgumentValue.Text("pdf"), text("format", "pdf").argumentValue())
        assertEquals(ArgumentValue.Text("10"), text("pages", "10").argumentValue(), "text that looks like a number stays text")
        assertEquals(ArgumentValue.Text(""), text("format", "").argumentValue())
        assertEquals(ArgumentValue.Text(" pdf "), text("format", " pdf ").argumentValue(), "nothing is trimmed")
        assertEquals(ArgumentValue.Whole(10), whole("pages", "10").argumentValue())
        assertEquals(ArgumentValue.Whole(-5), whole("delta", "-5").argumentValue())
        assertEquals(ArgumentValue.Whole(Long.MAX_VALUE), whole("big", Long.MAX_VALUE.toString()).argumentValue())
        assertEquals(ArgumentValue.Whole(Long.MIN_VALUE), whole("small", Long.MIN_VALUE.toString()).argumentValue())
        assertEquals(ArgumentValue.Flag(true), flag("signed", "true").argumentValue())
        assertEquals(ArgumentValue.Flag(false), flag("signed", "false").argumentValue())
    }

    @Test
    fun `a whole number value that is not one gives null`() {
        for (bad in listOf("", "ten", "1.5", "1e3", "+5", " 5", "5 ", "1 2", "--1", "-", "9223372036854775808", "-9223372036854775809", "０")) {
            assertNull(whole("pages", bad).argumentValue(), "\"$bad\" is not a whole number that fits a Long")
        }
    }

    // ---- what is written ----

    @Test
    fun `an exchange of unknown type and no header writes no argument, byte for byte as before`() {
        val plain = BindingForm(exchange = "events", destination = "orders", routingKey = "order.created")
        val action = plain.actions(ctx()).single() as MigrationAction.DeclareBinding
        assertEquals(MigrationAction.DeclareBinding(exchange = "events", queue = "orders", routingKey = "order.created", vhost = "/"), action)
        assertEquals(emptyMap(), action.arguments)
        // also when the form's rule is not the start value: with no header and no known headers exchange there is nothing to write
        assertEquals(emptyMap(), (plain.copy(matchRule = MatchRule.ANY).actions(ctx()).single() as MigrationAction.DeclareBinding).arguments)
    }

    @Test
    fun `headers are written with x-match and typed values`() {
        val a = arguments(form(text("format", "pdf"), whole("pages", "10"), flag("signed", "true"), text("copies", "10")))
        assertEquals(
            mapOf(
                "x-match" to ArgumentValue.Text("all"),
                "format" to ArgumentValue.Text("pdf"),
                "pages" to ArgumentValue.Whole(10),
                "signed" to ArgumentValue.Flag(true),
                "copies" to ArgumentValue.Text("10"),
            ),
            a,
        )
    }

    @Test
    fun `the match rule is written as its wire word`() {
        assertEquals(ArgumentValue.Text("all"), arguments(form(text("a", "b"), rule = MatchRule.ALL))["x-match"])
        assertEquals(ArgumentValue.Text("any"), arguments(form(text("a", "b"), rule = MatchRule.ANY))["x-match"])
    }

    @Test
    fun `a binding on a known headers exchange always carries x-match, also with no header`() {
        assertEquals(mapOf("x-match" to ArgumentValue.Text("all")), arguments(form()))
        assertEquals(mapOf("x-match" to ArgumentValue.Text("any")), arguments(form(rule = MatchRule.ANY)))
        val fromBroker = ctx(known = KnownTopology(exchanges = mapOf("documents" to "headers")))
        assertEquals(mapOf("x-match" to ArgumentValue.Text("all")), arguments(form(), fromBroker), "a headers exchange the broker has counts as known")
    }

    @Test
    fun `a known exchange that is not headers writes no x-match when there is no header`() {
        val direct = ctx(plan = planOf(MigrationAction.DeclareExchange(name = "documents", type = "direct")))
        assertEquals(emptyMap(), arguments(form(key = "k"), direct))
        val fanout = ctx(known = KnownTopology(exchanges = mapOf("documents" to "fanout")))
        assertEquals(emptyMap(), arguments(form(), fanout))
    }

    @Test
    fun `headers already in the form are written whatever the exchange type`() {
        // an opened file's binding on an exchange nobody knows: the form keeps and writes what it holds
        assertEquals(
            mapOf("x-match" to ArgumentValue.Text("any"), "a" to ArgumentValue.Text("b")),
            arguments(form(text("a", "b"), rule = MatchRule.ANY), ctx()),
        )
    }

    @Test
    fun `the routing key is written as held and never added`() {
        val a = form(text("format", "pdf"), key = "").actions(headersCtx).single() as MigrationAction.DeclareBinding
        assertEquals("", a.routingKey)
        val kept = form(text("format", "pdf"), key = "legacy-key").actions(headersCtx).single() as MigrationAction.DeclareBinding
        assertEquals("legacy-key", kept.routingKey, "a key of an opened file is kept, not dropped")
    }

    @Test
    fun `names and text values keep their spaces and case`() {
        val a = arguments(form(text(" format", " pdf "), text("Format", "PDF")))
        assertEquals(ArgumentValue.Text(" pdf "), a[" format"])
        assertEquals(ArgumentValue.Text("PDF"), a["Format"])
        assertEquals(3, a.size, "x-match and two headers")
    }

    @Test
    fun `the destination kind and the virtual host are kept`() {
        val f = form(text("a", "b")).copy(destinationKind = BindingDestinationType.EXCHANGE)
        val a = f.actions(ctx(plan = TopologyPlan(vhost = "uat").append(headersPlan.steps.map { it.action }))).single() as MigrationAction.DeclareBinding
        assertEquals(BindingDestinationType.EXCHANGE, a.destinationType)
        assertEquals("uat", a.vhost)
    }

    @Test
    fun `the step detail is the arguments sorted by key`() {
        val a = form(text("type", "report"), text("format", "pdf")).actions(headersCtx).single()
        assertEquals("format=pdf, type=report, x-match=all", a.detail)
        assertEquals("Bind documents to reports", a.description)
        val n = form(whole("pages", "10"), flag("signed", "true"), rule = MatchRule.ANY).actions(headersCtx).single()
        assertEquals("pages=10, signed=true, x-match=any", n.detail)
    }

    @Test
    fun `the written step survives the file format with its types`() {
        val actions = form(text("a", "10"), whole("b", "10"), flag("c", "true"), text("d", "true"), rule = MatchRule.ANY).actions(headersCtx)
        val json = MigrationParser.exportToJson("0001", "", actions)
        val back = (MigrationParser.parse(json) as MigrationValidationResult.Valid).migration.up
        assertEquals(actions, back)
        val b = (back.single() as MigrationAction.DeclareBinding).arguments
        assertEquals(ArgumentValue.Text("10"), b["a"])
        assertEquals(ArgumentValue.Whole(10), b["b"])
        assertEquals(ArgumentValue.Flag(true), b["c"])
        assertEquals(ArgumentValue.Text("true"), b["d"])
    }

    @Test
    fun `a held rule is written back verbatim and an explicit choice clears it`() {
        val kept = form(text("format", "pdf"), held = "all-with-x")
        assertEquals(ArgumentValue.Text("all-with-x"), arguments(kept)["x-match"])
        assertEquals(ArgumentValue.Text("weird"), arguments(form(text("format", "pdf"), held = "weird"))["x-match"], "anything else the file holds")
        for (rule in MatchRule.entries) {
            val chosen = kept.withRule(rule)
            assertNull(chosen.heldRule)
            assertEquals(rule, chosen.matchRule)
            assertEquals(ArgumentValue.Text(rule.wire), arguments(chosen)["x-match"])
        }
    }

    // ---- problems ----

    private fun problems(vararg headers: HeaderMatch) = form(*headers).problems(headersCtx)

    @Test
    fun `the four refusal texts`() {
        assertEquals("Give the header a name", PlannerTexts.HEADER_NAME_MISSING)
        assertEquals("x-match is the match rule, not a header", PlannerTexts.HEADER_NAME_IS_MATCH_RULE)
        assertEquals("A header can be listed once", PlannerTexts.HEADER_LISTED_TWICE)
        assertEquals("A whole number has digits only", PlannerTexts.HEADER_WHOLE_DIGITS_ONLY)
    }

    @Test
    fun `good rows have no problem`() {
        assertEquals(emptyList(), problems(text("format", "pdf"), whole("pages", "10"), flag("signed", "false"), text("empty", "")))
        assertEquals(emptyList(), problems(), "no header is a warning, not a problem")
        assertEquals(emptyList(), problems(text("X-Match", "all"), text("x-matcher", "all")), "only the exact name x-match is the rule")
    }

    @Test
    fun `an empty name is refused`() {
        assertEquals(listOf(FieldProblem("headers", "Give the header a name")), problems(text("", "pdf")))
    }

    @Test
    fun `the name x-match is refused for any value type`() {
        val message = "x-match is the match rule, not a header"
        assertEquals(listOf(FieldProblem("headers", message)), problems(text("x-match", "any")))
        assertEquals(listOf(FieldProblem("headers", message)), problems(whole("x-match", "1")))
        assertEquals(listOf(FieldProblem("headers", message)), problems(flag("x-match", "true")))
    }

    @Test
    fun `a name used twice is one problem, also with the same value and other types`() {
        val message = "A header can be listed once"
        assertEquals(listOf(FieldProblem("headers", message)), problems(text("format", "pdf"), text("format", "csv")))
        assertEquals(listOf(FieldProblem("headers", message)), problems(text("format", "pdf"), text("format", "pdf")))
        assertEquals(listOf(FieldProblem("headers", message)), problems(text("pages", "10"), whole("pages", "10")))
        assertEquals(emptyList(), problems(text("format", "pdf"), text("Format", "pdf")), "names are case sensitive on the broker")
        assertEquals(emptyList(), problems(text("format", "pdf"), text("format ", "pdf")), "a trailing space is another name")
    }

    @Test
    fun `a whole number with other characters is refused`() {
        assertEquals(listOf(FieldProblem("headers", "A whole number has digits only")), problems(whole("pages", "ten")))
        for (bad in listOf("", "1.5", "+5", " 5", "9223372036854775808")) {
            assertEquals(listOf(FieldProblem("headers", "A whole number has digits only")), problems(whole("pages", bad)), "\"$bad\"")
        }
        assertEquals(emptyList(), problems(whole("pages", "-5")))
    }

    @Test
    fun `one problem per mistake, in row order, and no step while there is one`() {
        val rows = arrayOf(text("", "a"), text("x-match", "all"), whole("pages", "ten"), text("ok", "1"))
        assertEquals(
            listOf("Give the header a name", "x-match is the match rule, not a header", "A whole number has digits only"),
            problems(*rows).map { it.message },
        )
        assertEquals(emptyList(), form(*rows).actions(headersCtx))
    }

    @Test
    fun `the missing exchange and destination are still the first problems`() {
        val f = BindingForm(exchange = "", destination = "", headers = listOf(text("", "x")))
        assertEquals(
            listOf("exchange", "destination"),
            f.problems(headersCtx).map { it.field }.take(2),
        )
    }

    // ---- hints ----

    @Test
    fun `the two fixed warnings`() {
        assertEquals("With no header, this binding matches every message", PlannerTexts.HEADERS_NONE_ALL)
        assertEquals("With no header, this binding matches no message at all", PlannerTexts.HEADERS_NONE_ANY)
    }

    @Test
    fun `a known headers exchange with no header warns per rule and never blocks`() {
        assertEquals(listOf(FieldHint("headers", PlannerTexts.HEADERS_NONE_ALL)), form(rule = MatchRule.ALL).hints(headersCtx))
        assertEquals(listOf(FieldHint("headers", PlannerTexts.HEADERS_NONE_ANY)), form(rule = MatchRule.ANY).hints(headersCtx))
        assertEquals(emptyList(), form(rule = MatchRule.ANY).problems(headersCtx))
        assertTrue(form().actions(headersCtx).isNotEmpty(), "the step can still be added")
        val fromBroker = ctx(known = KnownTopology(exchanges = mapOf("documents" to "headers")))
        assertEquals(listOf(FieldHint("headers", PlannerTexts.HEADERS_NONE_ALL)), form().hints(fromBroker))
    }

    @Test
    fun `no warning with a header, on another type or when the type is not known`() {
        assertEquals(emptyList(), form(text("format", "pdf")).hints(headersCtx))
        assertEquals(emptyList(), form(text("format", "pdf"), rule = MatchRule.ANY).hints(headersCtx))
        assertEquals(emptyList(), form().hints(ctx()), "type not known")
        for (type in listOf("direct", "topic", "fanout")) {
            val c = ctx(known = KnownTopology(exchanges = mapOf("documents" to type)))
            assertEquals(emptyList(), form().hints(c), type)
        }
    }

    @Test
    fun `the binding form has no naming or other hint besides the two warnings`() {
        assertEquals(emptyList(), BindingForm(exchange = "events", destination = "orders", routingKey = "k").hints(ctx()))
    }

    // ---- formOf ----

    private fun binding(arguments: Map<String, ArgumentValue>, key: String = "", exchange: String = "documents", kind: BindingDestinationType = BindingDestinationType.QUEUE) =
        MigrationAction.DeclareBinding(exchange = exchange, queue = "reports", routingKey = key, destinationType = kind, arguments = arguments)

    @Test
    fun `a binding with no arguments is the plain form, as today`() {
        assertEquals(BindingForm("documents", "reports", BindingDestinationType.QUEUE, "k"), StepForms.formOf(binding(emptyMap(), key = "k")))
    }

    @Test
    fun `a binding of headers and x-match all or any opens in the form with its values typed`() {
        val action = binding(
            linkedMapOf(
                "pages" to ArgumentValue.Whole(10),
                "x-match" to ArgumentValue.Text("any"),
                "signed" to ArgumentValue.Flag(true),
                "format" to ArgumentValue.Text("pdf"),
                "copies" to ArgumentValue.Text("10"),
            ),
            key = "legacy",
        )
        val f = StepForms.formOf(action) as BindingForm
        assertEquals(MatchRule.ANY, f.matchRule)
        assertNull(f.heldRule)
        assertEquals(
            listOf(whole("pages", "10"), flag("signed", "true"), text("format", "pdf"), text("copies", "10")),
            f.headers,
            "the headers in the file's order, x-match is not a header",
        )
        assertEquals("legacy", f.routingKey)
        assertEquals("documents", f.exchange)
        assertEquals("reports", f.destination)
        assertEquals(MatchRule.ALL, (StepForms.formOf(binding(mapOf("x-match" to ArgumentValue.Text("all"), "a" to ArgumentValue.Text("b")))) as BindingForm).matchRule)
    }

    @Test
    fun `an exchange destination is kept`() {
        val f = StepForms.formOf(binding(mapOf("x-match" to ArgumentValue.Text("all"), "a" to ArgumentValue.Text("b")), kind = BindingDestinationType.EXCHANGE)) as BindingForm
        assertEquals(BindingDestinationType.EXCHANGE, f.destinationKind)
    }

    @Test
    fun `a rule the form does not offer opens in the form and is written back unchanged`() {
        for (rule in listOf("all-with-x", "any-with-x", "ALL", "")) {
            val action = binding(mapOf("x-match" to ArgumentValue.Text(rule), "format" to ArgumentValue.Text("pdf")))
            val f = StepForms.formOf(action) as BindingForm
            assertEquals(rule, f.heldRule, "rule \"$rule\"")
            assertEquals(listOf(text("format", "pdf")), f.headers)
            assertEquals(listOf(action), f.actions(ctx()), "written back unchanged on an unknown exchange")
            assertEquals(listOf(action), f.actions(headersCtx), "written back unchanged on a known headers exchange")
        }
    }

    @Test
    fun `an opened binding written back is the same action, for every value type`() {
        val action = binding(
            mapOf(
                "x-match" to ArgumentValue.Text("any"), "a" to ArgumentValue.Text("1"), "b" to ArgumentValue.Whole(1),
                "c" to ArgumentValue.Flag(false), "d" to ArgumentValue.Whole(-7), "e e" to ArgumentValue.Text(" padded "),
            ),
            key = "kept",
        )
        assertEquals(listOf(action), (StepForms.formOf(action) as BindingForm).actions(headersCtx))
        assertEquals(listOf(action), (StepForms.formOf(action) as BindingForm).actions(ctx()))
    }

    /**
     * PINNED FOR THE ARCHITECT (brief gap): a file binding with only `x-match` (no header) opens in the form with no header. On an exchange of unknown type
     * the brief's rule "arguments are written when headers is not empty or the exchange is a known headers exchange" would DROP the rule on a plain
     * Edit and Add to plan. A step must never lose what the file had, so this test expects it kept (an implementation may remember the rule in the form,
     * or read-only the step; this test accepts a kept action, a form of any shape).
     */
    @Test
    fun `an opened binding with x-match only is not changed by writing it back on an unknown exchange`() {
        for (rule in listOf("all", "any", "all-with-x")) {
            val action = binding(mapOf("x-match" to ArgumentValue.Text(rule)))
            val form = StepForms.formOf(action)
            if (form != null) assertEquals(listOf(action), form.actions(ctx()), "x-match=$rule on an exchange of unknown type")
        }
    }

    @Test
    fun `anything else is a read-only step`() {
        val x = "x-match" to ArgumentValue.Text("all")
        val cases = mapOf(
            "decimal" to mapOf(x, "a" to ArgumentValue.Decimal(1.5)),
            "list" to mapOf(x, "a" to ArgumentValue.Items(listOf(ArgumentValue.Text("b")))),
            "table" to mapOf(x, "a" to ArgumentValue.Table(mapOf("b" to ArgumentValue.Text("c")))),
            "null" to mapOf(x, "a" to ArgumentValue.Nothing),
            "x-match is a number" to mapOf("x-match" to ArgumentValue.Whole(1), "a" to ArgumentValue.Text("b")),
            "x-match is a boolean" to mapOf("x-match" to ArgumentValue.Flag(true), "a" to ArgumentValue.Text("b")),
            "x-match is a list" to mapOf("x-match" to ArgumentValue.Items(emptyList())),
            "arguments without x-match" to mapOf("format" to ArgumentValue.Text("pdf")),
            "an unrelated argument" to mapOf("note" to ArgumentValue.Text("note")),
        )
        for ((why, args) in cases) assertNull(StepForms.formOf(binding(args)), why)
    }

    @Test
    fun `an MQTT route is still a route, whatever else formOf learned`() {
        val route = MigrationAction.DeclareBinding(exchange = "amq.topic", queue = "telemetry", routingKey = "sensors.*.temp")
        assertTrue(StepForms.formOf(route) is MqttRouteForm)
    }
}
