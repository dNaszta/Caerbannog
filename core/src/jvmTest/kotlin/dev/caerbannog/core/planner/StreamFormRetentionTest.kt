package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-08 slice 2, written first: the retention block of the stream form (Q4: retention is ONE policy). API fixed here (docs/tasks/M9-08-policy-step.md):
 *   StreamForm(..., keepFor: String = "", keepAtMost: String = "", sameRetentionForResponse: Boolean = true)
 * Both fields empty adds NO policy. Otherwise `actions` appends `DeclarePolicy(name = PlanNaming.derived(streamName, DerivedWord.RETENTION, style),
 * pattern = ExactPattern.of(names), applyTo = "streams", priority = 0, definition = {max-age?, max-length-bytes?})` AFTER every stream and route step;
 * `names` is the stream, plus the response stream when the pair and the tick are on. Problems: field `keepFor` (a duration: digits and a measured unit,
 * `Enter a number and a unit, for example 7D`) and field `keepAtMost` (a whole number, 0 or more, `Enter a whole number`).
 */
class StreamFormRetentionTest {
    private fun stream(name: String, vhost: String = "/") = MigrationAction.DeclareStream(name = name, vhost = vhost)

    private fun policy(
        pattern: String, definition: Map<String, ArgumentValue>, name: String = "telemetry-retention", vhost: String = "/",
    ) = MigrationAction.DeclarePolicy(name = name, pattern = pattern, definition = definition, vhost = vhost, applyTo = "streams", priority = 0)

    private val sevenDays = mapOf("max-age" to ArgumentValue.Text("7D"))

    @Test
    fun `the retention fields are empty and the response tick is on by default`() {
        val form = StreamForm(name = "telemetry")
        assertEquals("", form.keepFor)
        assertEquals("", form.keepAtMost)
        assertTrue(form.sameRetentionForResponse)
    }

    @Test
    fun `both fields empty adds no policy step, with or without the pair`() {
        assertEquals(listOf(stream("telemetry")), StreamForm(name = "telemetry").actions(ctx()))
        assertEquals(listOf(stream("telemetry"), stream("telemetry-dln")), StreamForm(name = "telemetry", withResponse = true).actions(ctx()))
    }

    @Test
    fun `Keep messages for adds one policy that applies to streams and matches exactly the stream`() {
        assertEquals(
            listOf(stream("telemetry"), policy("^telemetry$", sevenDays)),
            StreamForm(name = "telemetry", keepFor = "7D").actions(ctx()),
        )
    }

    @Test
    fun `Keep at most is saved as a number`() {
        assertEquals(
            listOf(stream("telemetry"), policy("^telemetry$", mapOf("max-length-bytes" to ArgumentValue.Whole(1_000_000)))),
            StreamForm(name = "telemetry", keepAtMost = "1000000").actions(ctx()),
        )
    }

    @Test
    fun `both fields make one policy that holds both, age first`() {
        val action = StreamForm(name = "telemetry", keepFor = "7D", keepAtMost = "1000000").actions(ctx()).last() as MigrationAction.DeclarePolicy
        assertEquals(mapOf("max-age" to ArgumentValue.Text("7D"), "max-length-bytes" to ArgumentValue.Whole(1_000_000)), action.definition)
        assertEquals("apply-to=streams, priority=0; max-age=7D, max-length-bytes=1000000", action.detail)
        assertEquals("Declare policy telemetry-retention", action.description)
    }

    @Test
    fun `the policy takes the plan's virtual host and priority 0`() {
        val action = StreamForm(name = "telemetry", keepFor = "7D").actions(ctx(plan = TopologyPlan(vhost = "uat"))).last() as MigrationAction.DeclarePolicy
        assertEquals("uat", action.vhost)
        assertEquals(0, action.priority)
        assertEquals("streams", action.applyTo)
    }

    @Test
    fun `with the pair and the tick the policy covers the stream and its response stream`() {
        val actions = StreamForm(name = "telemetry", withResponse = true, keepFor = "7D").actions(ctx())
        assertEquals(listOf(stream("telemetry"), stream("telemetry-dln"), policy("^(telemetry|telemetry-dln)$", sevenDays)), actions)
    }

    @Test
    fun `unticked, the policy covers the stream alone`() {
        val actions = StreamForm(name = "telemetry", withResponse = true, keepFor = "7D", sameRetentionForResponse = false).actions(ctx())
        assertEquals(listOf(stream("telemetry"), stream("telemetry-dln"), policy("^telemetry$", sevenDays)), actions)
    }

    @Test
    fun `the tick means nothing without the pair`() {
        val on = StreamForm(name = "telemetry", keepFor = "7D", sameRetentionForResponse = true).actions(ctx())
        val off = StreamForm(name = "telemetry", keepFor = "7D", sameRetentionForResponse = false).actions(ctx())
        assertEquals(on, off)
        assertEquals("^telemetry$", (on.last() as MigrationAction.DeclarePolicy).pattern)
    }

    @Test
    fun `a typed response name is covered, not the suggestion`() {
        val actions = StreamForm(name = "telemetry", withResponse = true, responseName = "telemetry-replies", keepFor = "7D").actions(ctx())
        assertEquals("^(telemetry|telemetry-replies)$", (actions.last() as MigrationAction.DeclarePolicy).pattern)
    }

    @Test
    fun `the policy comes after every stream and every route step`() {
        val actions = StreamForm(
            name = "device-commands", withResponse = true, answersTopic = "devices/+/answer", requestsTopic = "commands/#", keepFor = "7D",
        ).actions(ctx())
        assertEquals(
            listOf("DeclareStream", "DeclareStream", "DeclareBinding", "DeclareBinding", "DeclarePolicy"),
            actions.map { it::class.simpleName },
            "the policy is the last step",
        )
        assertEquals(
            listOf(BindingDestinationType.QUEUE, BindingDestinationType.QUEUE),
            actions.filterIsInstance<MigrationAction.DeclareBinding>().map { it.destinationType },
        )
    }

    @Test
    fun `the policy name follows the naming style, retention is a derived word`() {
        mapOf(
            NamingStyle.KEBAB to "telemetry-retention", NamingStyle.NONE to "telemetry-retention", NamingStyle.SNAKE to "telemetry_retention",
            NamingStyle.DOT to "telemetry.retention", NamingStyle.CAMEL to "telemetryRetention",
        ).forEach { (style, expected) ->
            val action = StreamForm(name = "telemetry", keepFor = "7D").actions(ctx(style = style)).last() as MigrationAction.DeclarePolicy
            assertEquals(expected, action.name, "$style")
        }
    }

    @Test
    fun `a stream name with special characters is escaped in the pattern`() {
        val action = StreamForm(name = "orders.v2+x", keepFor = "7D").actions(ctx()).last() as MigrationAction.DeclarePolicy
        assertEquals("^orders\\.v2\\+x$", action.pattern)
        assertEquals("orders.v2+x-retention", action.name)
    }

    // ---- problems ----

    private fun problems(form: StreamForm) = form.problems(ctx()).map { it.field to it.message }

    @Test
    fun `a duration the broker would refuse is refused with the proposed text`() {
        listOf("7", "7d", "D", "-1D", "7 D", " 7D", "7D ", "1.5D", "abc", "7DD").forEach {
            assertEquals(listOf("keepFor" to "Enter a number and a unit, for example 7D"), problems(StreamForm(name = "telemetry", keepFor = it)), "\"$it\"")
        }
    }

    @Test
    fun `the measured units are accepted`() {
        listOf("1Y", "1M", "7D", "1h", "90m", "1000s").forEach { assertEquals(emptyList(), problems(StreamForm(name = "telemetry", keepFor = it)), it) }
    }

    @Test
    fun `a size that is not a whole number is refused, zero is let through`() {
        listOf("-1", "1.5", "abc", "1e6", " 5", "5 ", "99999999999999999999").forEach {
            assertEquals(listOf("keepAtMost" to "Enter a whole number"), problems(StreamForm(name = "telemetry", keepAtMost = it)), "\"$it\"")
        }
        assertEquals(emptyList(), problems(StreamForm(name = "telemetry", keepAtMost = "0")))
        assertEquals(emptyList(), problems(StreamForm(name = "telemetry", keepAtMost = "10000000000")))
    }

    @Test
    fun `no action while a retention field has a problem, and the name problem comes first`() {
        val bad = StreamForm(name = "telemetry", keepFor = "7d")
        assertEquals(emptyList(), bad.actions(ctx()))
        assertEquals(
            listOf("name" to PlannerTexts.STREAM_NAME_MISSING, "keepFor" to "Enter a number and a unit, for example 7D", "keepAtMost" to "Enter a whole number"),
            problems(StreamForm(name = "", keepFor = "7d", keepAtMost = "x")),
        )
    }

    @Test
    fun `the retention fields are checked even when the pair is on`() {
        assertEquals(
            listOf("keepFor" to "Enter a number and a unit, for example 7D"),
            problems(StreamForm(name = "telemetry", withResponse = true, keepFor = "7")),
        )
    }

    @Test
    fun `a name that cannot make a policy name is refused by the form, not by the apply`() {
        // a stream name of 250 characters is fine for a stream but the derived policy name is longer; the parser round trip decides, the form never throws
        val form = StreamForm(name = "s".repeat(250), keepFor = "7D")
        form.problems(ctx())
        form.actions(ctx())
    }

    // ---- opening an existing step ----

    @Test
    fun `a stream step without settings opens with empty retention fields, the policy step beside it is its own step`() {
        val form = assertIs<StreamForm>(StepForms.formOf(MigrationAction.DeclareStream("telemetry")))
        assertEquals("", form.keepFor)
        assertEquals("", form.keepAtMost)
        assertFalse(form.withResponse)
    }

    @Test
    fun `a stream step that carries settings stays read-only, as before`() {
        assertEquals(null, StepForms.formOf(MigrationAction.DeclareStream("telemetry", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D")))))
    }
}
