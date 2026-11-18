package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.topology.BindingDestinationType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-12 step 2: "nothing is lost" (spec A2) for a whole file. Every action kind of the format that a pending file may hold (the nine topology kinds and
 * the policy kinds, with arguments, flags, deletes and operator policies; the four access kinds never reach the planner, see PlanOpeningTest), opened as
 * steps, one step added with the wizard, saved, parsed again: the old actions are equal and in their place, the new one is last.
 * RED by compilation until `PlannerMode`, `PlannerRig(mode, rawFiles)` and the file mode of the view model exist.
 */
class PlanRoundTripTest {
    private val args = mapOf(
        "x-text" to ArgumentValue.Text("v"), "x-whole" to ArgumentValue.Whole(7), "x-decimal" to ArgumentValue.Decimal(1.5),
        "x-flag" to ArgumentValue.Flag(true), "x-null" to ArgumentValue.Nothing,
        "x-items" to ArgumentValue.Items(listOf(ArgumentValue.Text("a"), ArgumentValue.Whole(2))),
        "x-table" to ArgumentValue.Table(mapOf("k" to ArgumentValue.Flag(false))),
    )

    private val everyKind: List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("orders", "quorum"),
        MigrationAction.DeclareQueue("jobs", "classic", durable = false, arguments = args, autoDelete = true),
        MigrationAction.DeclareExchange("events", "topic"),
        MigrationAction.DeclareExchange("hdrs", "headers", durable = false, autoDelete = true, internal = true, arguments = args),
        MigrationAction.DeclareBinding("events", "orders", "order.created"),
        MigrationAction.DeclareBinding("hdrs", "jobs", arguments = mapOf("x-match" to ArgumentValue.Text("any"), "format" to ArgumentValue.Text("pdf"), "pages" to ArgumentValue.Whole(10))),
        MigrationAction.DeclareBinding("events", "hdrs", "k", destinationType = BindingDestinationType.EXCHANGE, arguments = args),
        MigrationAction.DeclareStream("audit"),
        MigrationAction.DeclareStream("telemetry", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-stream-max-segment-size-bytes" to ArgumentValue.Whole(500_000_000))),
        MigrationAction.DeclarePolicy("p-limit", "^orders$", mapOf("max-length" to ArgumentValue.Whole(1000)), "/", "queues", 3),
        MigrationAction.DeclarePolicy("p-odd", "^x", mapOf("x-made-up" to ArgumentValue.Text("1")), "/", "all", 0),
        MigrationAction.DeclareOperatorPolicy("op-limit", "^jobs$", mapOf("max-length" to ArgumentValue.Whole(100)), "/", "quorum_queues", 1),
        MigrationAction.DeleteQueue("old-q"),
        MigrationAction.DeleteExchange("old-e"),
        MigrationAction.DeleteBinding("old-e2", "old-q2", "rk"),
        MigrationAction.DeleteStream("old-s"),
        MigrationAction.DeletePolicy("old-p"),
    )

    private fun saveAfterAdding(actions: List<MigrationAction>, add: (PlannerRig) -> Unit): List<MigrationAction> {
        val text = MigrationParser.exportToJson("0005", "round trip", actions)
        val rig = EditFileFixtures.open(text)
        try {
            add(rig)
            rig.vm.saveAsMigration()
            val saved = rig.folder.files.getValue("0005").rawJson
            return (MigrationParser.parse(saved) as MigrationValidationResult.Valid).migration.up
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the whole format survives open, add one step, save, parse`() {
        val saved = saveAfterAdding(everyKind) { it.queue("invoices") }
        assertEquals(everyKind + MigrationAction.DeclareQueue("invoices", "quorum"), saved)
    }

    @Test
    fun `one step of each kind alone survives`() {
        everyKind.forEach { action ->
            val saved = saveAfterAdding(listOf(action)) { it.queue("invoices") }
            assertEquals(listOf(action, MigrationAction.DeclareQueue("invoices", "quorum")), saved, action.description)
        }
    }

    @Test
    fun `a step added in the middle by moving keeps the others in place`() {
        val saved = saveAfterAdding(everyKind) { rig ->
            rig.queue("invoices")
            val id = rig.state.plan.steps.last().id
            repeat(2) { rig.vm.moveStepUp(id) }
        }
        val expected = everyKind.toMutableList().also { it.add(everyKind.size - 2, MigrationAction.DeclareQueue("invoices", "quorum")) }
        assertEquals(expected, saved)
    }

    @Test
    fun `actions of other virtual hosts than the first are kept as written`() {
        val mixed = listOf(
            MigrationAction.DeclareQueue("a", vhost = "orders"),
            MigrationAction.DeclareQueue("b", vhost = "billing", arguments = args),
            MigrationAction.DeleteQueue("c", vhost = "/"),
        )
        val saved = saveAfterAdding(mixed) { it.queue("invoices") }
        assertEquals(mixed, saved.take(3), "an old action was moved to another virtual host")
    }

    @Test
    fun `editing one editable step changes only that step`() {
        val text = MigrationParser.exportToJson("0005", "d", listOf(EditFileFixtures.ordersQueue, everyKind[1], EditFileFixtures.eventsExchange))
        val rig = EditFileFixtures.open(text)
        try {
            val first = rig.state.plan.steps.first().id
            rig.vm.editStep(first)
            rig.vm.updateForm(QueueForm(name = "orders-v2", type = "quorum"))
            rig.vm.next()
            rig.vm.addToPlan()
            rig.vm.saveAsMigration()
            val saved = (MigrationParser.parse(rig.folder.files.getValue("0005").rawJson) as MigrationValidationResult.Valid).migration.up
            assertEquals(listOf(MigrationAction.DeclareQueue("orders-v2", "quorum"), everyKind[1], EditFileFixtures.eventsExchange), saved)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the description of the file is kept`() {
        val rig = EditFileFixtures.open(MigrationParser.exportToJson("0005", "Orders, with \"quotes\" and ünïcode", listOf(EditFileFixtures.ordersQueue)))
        try {
            rig.queue("invoices")
            rig.vm.saveAsMigration()
            assertEquals("Orders, with \"quotes\" and ünïcode", rig.folder.files.getValue("0005").description)
        } finally {
            rig.close()
        }
    }
}
