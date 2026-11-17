package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationApplyResult
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-07b step 4 (test developer): hostile input and safety around the two ticks. Hostile filters never throw, never give both a problem and a step, and
 * every step the form gives is a file the parser accepts; 1,000 pairs are noted in bounded time; a pair whose request stream is an existing queue name is
 * refused under the name; two pairs sharing a response name are not mixed up; the steps of the ticks are ordinary steps the runner applies only with the
 * configure permission and above Read-only (nothing reaches the executor otherwise).
 */
class StreamFormMqttTicksHostileTest {
    private val hostile = listOf(
        "a".repeat(70_000), "\u0000", "a/\u0000/b", "\uD800", "a/\uD800/b", "a.b", "a/*/b", "a//b", "+/", "/+", "#/#", "\$share/g/x", " ", "\t", "‮evil/+",
        "ü/+/ö", "a/b\nc", "+".repeat(5000), "/".repeat(5000), "a/".repeat(3000) + "#",
    )

    @Test
    fun `hostile filters in either tick never throw and never give a problem together with a step`() {
        for (filter in hostile) for (side in listOf("answers", "requests")) {
            val form = if (side == "answers") StreamForm("p", true, null, filter, null) else StreamForm("p", true, null, null, filter)
            val problems = form.problems(ctx())
            val actions = form.actions(ctx())
            val label = "${side} filter of ${filter.length} chars starting ${filter.take(8).map { it.code }}"
            assertTrue(problems.isEmpty() == actions.isNotEmpty(), "problems and steps disagree for $label: $problems")
            if (actions.isNotEmpty()) {
                val json = MigrationParser.exportToJson("0001", "hostile", actions)
                assertTrue(MigrationParser.parse(json) is MigrationValidationResult.Valid, "the file of $label is refused by the parser")
            }
            assertTrue(problems.all { it.field == "answersTopic" || it.field == "requestsTopic" }, "problems of $label are under another field: $problems")
        }
    }

    @Test
    fun `a thousand pairs with routes are noted in bounded time and each once`() {
        val actions = buildList<MigrationAction> {
            repeat(1000) {
                add(MigrationAction.DeclareStream("p$it"))
                add(MigrationAction.DeclareStream("p$it-dln"))
                add(MigrationAction.DeclareBinding("amq.topic", "p$it-dln", "d$it.*.answer"))
            }
        }
        var notes = emptyList<String>()
        val millis = measureTimeMillis { notes = PlanPairs.requestsAnsweredOverMqtt(actions) }
        assertEquals((0 until 1000).map { "p$it" }, notes)
        val plan = TopologyPlan().append(actions)
        var graphNotes = 0
        val previewMillis = measureTimeMillis { graphNotes = planPreview(plan, KnownTopology.None).notes.size }
        assertEquals(1000, graphNotes)
        assertTrue(millis < 5_000 && previewMillis < 10_000, "the rule took $millis ms and the preview $previewMillis ms")
    }

    @Test
    fun `a pair whose request stream is a queue of the plan is refused under the name and adds nothing`() {
        val plan = planOf(MigrationAction.DeclareQueue("orders"))
        val form = StreamForm("orders", withResponse = true, answersTopic = "devices/+/answer")
        assertEquals(listOf(FieldProblem("name", PlannerTexts.queueAndStreamOfOneName("orders"))), form.problems(ctx(plan = plan)))
        assertEquals(emptyList(), form.actions(ctx(plan = plan)))
        val response = planOf(MigrationAction.DeclareQueue("orders-dln"))
        assertEquals(listOf(FieldProblem("responseName", PlannerTexts.queueAndStreamOfOneName("orders-dln"))), form.problems(ctx(plan = response)))
        assertEquals(emptyList(), form.actions(ctx(plan = response)))
    }

    @Test
    fun `two pairs sharing a response name are not mixed up`() {
        val a = StreamForm("a", withResponse = true, answersTopic = "x/+/answer").actions(ctx())
        val b = StreamForm("b", withResponse = true, responseName = "a-dln", answersTopic = "y/+/answer").actions(ctx())
        val actions = a + b
        assertEquals(listOf("a"), PlanPairs.requestsAnsweredOverMqtt(actions))
        assertEquals(setOf("q:a"), planPreview(planOf(*actions.toTypedArray()), KnownTopology.None).notes.map { it.sourceId }.toSet())
    }

    @Test
    fun `the steps of the ticks are applied only with the configure permission and above Read-only`() = runBlocking {
        val actions = StreamForm("device-commands", true, null, "devices/+/answer", "commands/#").actions(ctx())
        val file = (MigrationParser.parse(MigrationParser.exportToJson("0001", "ticks", actions)) as MigrationValidationResult.Valid).migration
        for ((permission, level) in listOf(false to TopologyControlLevel.FULL_CONTROL, true to TopologyControlLevel.READ_ONLY, false to TopologyControlLevel.READ_ONLY)) {
            val executor = FakeTopologyMigrationExecutor()
            val result = MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(file), permission, level, vhost = "/")
            assertTrue(result !is MigrationApplyResult.Success, "applied with permission=$permission at $level: $result")
            assertEquals(emptyList(), executor.executedActions, "something reached the executor with permission=$permission at $level")
        }
        val executor = FakeTopologyMigrationExecutor()
        val ok = MigrationRunner(InMemoryMigrationHistoryRepository(), executor).applyPending("p", listOf(file), true, TopologyControlLevel.FULL_CONTROL, vhost = "/")
        assertIs<MigrationApplyResult.Success>(ok)
        assertEquals(actions, executor.executedActions, "the runner applies exactly the steps of the ticks, in order, and nothing else")
    }
}
