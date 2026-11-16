package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9-07 step 4 (test developer): hostile names, scale and the draft round trip for the stream pair. */
class PlanPairsExploratoryTest {
    private fun stream(name: String) = MigrationAction.DeclareStream(name = name)

    private val hostile = listOf(
        "<script>alert(1)</script>", "`x`", "a".repeat(200), "‮evil", "שלום", "a/b", "a\\b", "a b", "tab\tname", "line\nbreak",
        "café", "🐇rabbit", "dln", "-dln", "Orders_EU", "orders", "\u0000nul",
    )

    @Test
    fun `hostile names go through the form, the pair rule, the text and the graph without a crash and without markup in the line`() {
        for (name in hostile) {
            for (style in NamingStyle.entries) {
                val c = ctx(style = style)
                val form = StreamForm(name, withResponse = true)
                form.problems(c); form.hints(c); form.actions(c)
                val response = form.effectiveResponseName(c)
                val plan = planOf(stream(name), stream(response))
                val pairs = PlanPairs.responseOf(plan)
                val g = planPreview(plan, KnownTopology.None)
                if (name.isNotEmpty() && form.problems(c).isEmpty()) {
                    assertEquals(mapOf(2 to 1), pairs, "name ${name.take(12)} style $style")
                    assertEquals(1, g.links.size)
                }
                val line = PlannerTexts.responseStreamOf(name)
                assertTrue(line == "Response stream (DLN) of $name" || line == "Response stream (DLN)")
                if (line != "Response stream (DLN)") assertTrue(name.all { it.isLetterOrDigit() || it in "._-" }, "echoed an unsafe name: $name")
            }
        }
    }

    @Test
    fun `a name equal to its derived response name is an ordinary stream or a pair with the shorter one - never itself`() {
        val plan = planOf(stream("a-dln"))
        assertEquals(emptyMap(), PlanPairs.responseOf(plan))
        assertEquals(listOf(FieldProblem("responseName", PlannerTexts.RESPONSE_NAME_SAME)), StreamForm("x", true, responseName = "x").problems(ctx()))
    }

    @Test
    fun `the stream form refuses names the migration parser refuses, on the right field`() {
        assertEquals("name", StreamForm("..").problems(ctx()).single().field)
        assertEquals("responseName", StreamForm("ok", true, responseName = "..").problems(ctx()).single().field)
    }

    @Test
    fun `200 pairs of 400 steps are recognised in bounded time`() {
        val actions = (1..200).flatMap { listOf(stream("stream-$it"), stream("stream-$it-dln")) }
        val plan = planOf(*actions.toTypedArray())
        val t0 = System.nanoTime()
        val pairs = PlanPairs.responseOf(plan)
        val links = planPreview(plan, KnownTopology.None).links
        val state = PlannerState(plan = plan)
        var lines = 0
        plan.steps.forEach { if (state.responseStreamLine(it.id) != null) lines++ }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(200, pairs.size)
        assertEquals(200, lines)
        assertTrue(links.size <= 200)
        assertTrue(ms < 5_000, "400 steps took $ms ms for the pair rule, the links and every row's line")
    }

    @Test
    fun `the saved file holds two plain declareStream actions and reads back as the same pair`() {
        val plan = planOf(stream("telemetry"), stream("telemetry-dln"))
        val json = MigrationParser.exportToJson("0005", "", plan.steps.map { it.action })
        assertTrue(!json.contains("pair") && !json.contains("response"), json)
        val parsed = (MigrationParser.parse(json) as MigrationValidationResult.Valid).migration
        val reopened = TopologyPlan.of("", parsed.up, "/")
        assertEquals(mapOf(2 to 1), PlanPairs.responseOf(reopened))
    }

    @Test
    fun `a style change after adding renames nothing and the pair stays recognised in every style`() {
        val rig = PlannerRig()
        rig.stream("orders", withResponse = true)
        for (style in NamingStyle.entries) {
            rig.vm.setNamingStyle(style)
            assertEquals(listOf("Declare stream orders", "Declare stream orders-dln"), rig.titles())
            assertEquals("Response stream (DLN) of orders", rig.state.responseStreamLine(rig.ids()[1]))
        }
        rig.close()
    }

    @Test
    fun `a response name the plan already declares as a stream is the plan's note, as for any repeat`() {
        val rig = PlannerRig()
        rig.stream("orders-dln")
        rig.stream("orders", withResponse = true)
        assertEquals(3, rig.state.plan.steps.size)
        assertTrue(rig.state.problems.none { it.blocking }, rig.state.problems.toString())
        rig.close()
    }

    @Test
    fun `check on a pair asks the port about both streams, and reads only`() {
        val rig = PlannerRig(declareCheck = dev.caerbannog.core.migrations.fakes.FakeDeclareCheck()
            .answerNamed("telemetry", dev.caerbannog.core.migrations.BrokerFinding.Absent)
            .answerNamed("telemetry-dln", dev.caerbannog.core.migrations.BrokerFinding.Same))
        rig.stream("telemetry", withResponse = true)
        rig.vm.check()
        val done = rig.state.check as CheckState.Done
        assertEquals(listOf(StepFinding.WillBeCreated, StepFinding.AlreadyThere), done.results.map { it.finding })
        rig.close()
    }
}
