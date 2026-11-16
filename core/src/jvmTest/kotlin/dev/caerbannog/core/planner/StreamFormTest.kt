package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * M9-07 step 2: the stream form (owner decisions O9, O10, O11; spec "Streams and the response stream").
 * API fixed by these tests (beyond the brief, for the architect to confirm):
 *  - `StreamForm(name, withResponse = false, responseName = null)`; `responseName == null` means "follow the stream name" and a string, even an
 *    empty one, means "the user edited it" (the rule "follows the stream name until the user edits it");
 *  - `suggestedResponseName(ctx)`: `PlanNaming.derived(name, DerivedWord.DLN, ctx.namingStyle)`, or "" while the stream name is empty;
 *  - `effectiveResponseName(ctx)`: `responseName ?: suggestedResponseName(ctx)`;
 *  - problems carry the field `name` or `responseName`; hints the same fields; the response stream's field and hint exist only while ticked.
 */
class StreamFormTest {
    @Test
    fun `the response stream is off by default and a stream is one declareStream`() {
        val form = StreamForm(name = "telemetry")
        assertFalse(form.withResponse)
        assertEquals(null, form.responseName)
        assertEquals(listOf(MigrationAction.DeclareStream(name = "telemetry")), form.actions(ctx()))
    }

    @Test
    fun `the stream takes the virtual host of the plan`() {
        assertEquals(
            listOf(MigrationAction.DeclareStream(name = "telemetry", vhost = "uat")),
            StreamForm(name = "telemetry").actions(ctx(plan = TopologyPlan(vhost = "uat"))),
        )
    }

    @Test
    fun `with the tick there are two actions, the stream first and its response stream second`() {
        val actions = StreamForm(name = "telemetry", withResponse = true).actions(ctx(plan = TopologyPlan(vhost = "uat")))
        assertEquals(
            listOf(
                MigrationAction.DeclareStream(name = "telemetry", vhost = "uat"),
                MigrationAction.DeclareStream(name = "telemetry-dln", vhost = "uat"),
            ),
            actions,
        )
    }

    @Test
    fun `the suggested response name is the stream name with the dln suffix in each style`() {
        val table = mapOf(
            NamingStyle.KEBAB to "orders-dln",
            NamingStyle.SNAKE to "orders_dln",
            NamingStyle.DOT to "orders.dln",
            NamingStyle.CAMEL to "ordersDln",
            NamingStyle.NONE to "orders-dln",
        )
        for ((style, expected) in table) {
            val c = ctx(style = style)
            val form = StreamForm(name = "orders", withResponse = true)
            assertEquals(expected, form.suggestedResponseName(c), "suggestion in $style")
            assertEquals(expected, form.effectiveResponseName(c), "effective name in $style")
            assertEquals(expected, form.actions(c)[1].let { (it as MigrationAction.DeclareStream).name }, "second action in $style")
        }
    }

    @Test
    fun `an empty stream name suggests nothing`() {
        assertEquals("", StreamForm(name = "", withResponse = true).suggestedResponseName(ctx()))
    }

    @Test
    fun `the response name follows the stream name until the user edits it - as a table`() {
        data class Row(val form: StreamForm, val effective: String, val why: String)
        val c = ctx()
        val rows = listOf(
            Row(StreamForm("orders", true), "orders-dln", "never edited"),
            Row(StreamForm("invoices", true), "invoices-dln", "stream name changed, response name never edited"),
            Row(StreamForm("bills", true, responseName = "invoices-replies"), "invoices-replies", "edited by hand: stays when the stream name changes"),
            Row(StreamForm("bills", true, responseName = "bills-dln"), "bills-dln", "typed the same as the suggestion: still its own name"),
            Row(StreamForm("orders", false, responseName = "kept"), "kept", "untick keeps what was typed, so a second tick brings it back"),
        )
        for (row in rows) assertEquals(row.effective, row.form.effectiveResponseName(c), row.why)
        // the follow rule as the user sees it: a copy that only changes the stream name keeps a hand-made response name, a fresh one follows
        val followed = StreamForm("orders", true).copy(name = "invoices")
        assertEquals("invoices-dln", followed.effectiveResponseName(c))
        val own = StreamForm("orders", true, responseName = "invoices-replies").copy(name = "bills")
        assertEquals("invoices-replies", own.effectiveResponseName(c))
    }

    @Test
    fun `a response name of its own is the second action and is not renamed by the style`() {
        val form = StreamForm("orders", true, responseName = "Orders_Replies")
        assertEquals(
            listOf("orders", "Orders_Replies"),
            form.actions(ctx()).map { (it as MigrationAction.DeclareStream).name },
        )
    }

    @Test
    fun `untick gives one action even when a response name was typed`() {
        assertEquals(
            listOf(MigrationAction.DeclareStream(name = "orders")),
            StreamForm("orders", withResponse = false, responseName = "orders-replies").actions(ctx()),
        )
    }

    // ---- problems ----

    @Test
    fun `problems as a table`() {
        val c = ctx()
        val table = listOf(
            StreamForm("") to listOf(FieldProblem("name", "Give the stream a name")),
            StreamForm("..") to listOf(FieldProblem("name", "a name must not be \".\" or \"..\"")),
            StreamForm("orders", true, responseName = "") to listOf(FieldProblem("responseName", "Give the response stream a name")),
            StreamForm("orders", true, responseName = ".") to listOf(FieldProblem("responseName", "a name must not be \".\" or \"..\"")),
            StreamForm("orders", true, responseName = "orders") to listOf(FieldProblem("responseName", "The response stream needs a name of its own")),
            // following an empty stream name is not a second problem: the first one says what to do
            StreamForm("", true) to listOf(FieldProblem("name", "Give the stream a name")),
        )
        for ((form, expected) in table) assertEquals(expected, form.problems(c), "$form")
        for (ok in listOf(StreamForm("orders"), StreamForm("orders", true), StreamForm("Orders_EU", true, responseName = "x y"), StreamForm("a", false, responseName = ""))) {
            assertEquals(emptyList(), ok.problems(c), "$ok")
        }
    }

    @Test
    fun `the response name is not checked while the tick is off`() {
        assertEquals(emptyList(), StreamForm("orders", withResponse = false, responseName = "orders").problems(ctx()))
    }

    @Test
    fun `a form with a problem builds no action`() {
        assertEquals(emptyList(), StreamForm("").actions(ctx()))
        assertEquals(emptyList(), StreamForm("orders", true, responseName = "orders").actions(ctx()), "no half pair")
    }

    @Test
    fun `a name the plan already declares as a queue is a problem on the field of that name`() {
        val c = ctx(plan = planOf(MigrationAction.DeclareQueue(name = "telemetry"), MigrationAction.DeclareQueue(name = "telemetry-dln")))
        assertEquals(
            listOf(FieldProblem("name", PlannerTexts.queueAndStreamOfOneName("telemetry"))),
            StreamForm("telemetry").problems(c),
        )
        assertEquals(
            listOf(FieldProblem("responseName", PlannerTexts.queueAndStreamOfOneName("telemetry-dln"))),
            StreamForm("telemetry-x", true, responseName = "telemetry-dln").problems(c),
        )
        assertEquals(
            listOf(FieldProblem("name", PlannerTexts.queueAndStreamOfOneName("telemetry")), FieldProblem("responseName", PlannerTexts.queueAndStreamOfOneName("telemetry-dln"))),
            StreamForm("telemetry", true).problems(c),
        )
        assertEquals(emptyList(), StreamForm("other", true).problems(c))
    }

    @Test
    fun `a queue of the broker only is a hint matter, never a problem`() {
        val known = KnownTopology(queues = setOf("telemetry"))
        assertEquals(emptyList(), StreamForm("telemetry").problems(ctx(known = known)))
    }

    // ---- hints ----

    @Test
    fun `both names get the naming hint, the response stream's on its own field and only while ticked`() {
        val hint = "This name does not follow kebab-case, for example orders-eu"
        assertEquals(listOf(FieldHint("name", hint)), StreamForm("Orders_EU").hints(ctx()))
        assertEquals(
            listOf(FieldHint("name", hint)),
            StreamForm("Orders_EU", withResponse = false, responseName = "Bad_Name").hints(ctx()),
            "no hint for a response stream that is not added",
        )
        assertEquals(
            listOf(FieldHint("responseName", "This name does not follow kebab-case, for example orders-replies")),
            StreamForm("orders", true, responseName = "Orders_Replies").hints(ctx()),
        )
        assertEquals(
            listOf(FieldHint("name", hint), FieldHint("responseName", "This name does not follow kebab-case, for example orders-replies")),
            StreamForm("Orders_EU", true, responseName = "Orders_Replies").hints(ctx()),
        )
    }

    @Test
    fun `the suggestion carries no hint in any style`() {
        for (style in NamingStyle.entries) {
            assertEquals(emptyList(), StreamForm("orders", true).hints(ctx(style = style)), "style $style")
        }
    }

    @Test
    fun `no hint for an empty name and none for a name the broker already has`() {
        assertEquals(emptyList(), StreamForm("").hints(ctx()))
        val known = KnownTopology(streams = setOf("Legacy_Telemetry", "Legacy_Replies"))
        assertEquals(emptyList(), StreamForm("Legacy_Telemetry", true, responseName = "Legacy_Replies").hints(ctx(known = known)))
    }
}
