package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser

/** M9-12: the pending file of the tests and the rig that has it open ("Edit steps"). */
internal object EditFileFixtures {
    const val VERSION = "0005"

    /** The text of a file the app would write. */
    fun raw(version: String = VERSION, description: String = "Orders", vararg actions: MigrationAction): String =
        MigrationParser.exportToJson(version, description, actions.toList())

    fun checksum(raw: String): String = MigrationParser.computeChecksum(raw)

    val ordersQueue = MigrationAction.DeclareQueue("orders", "quorum")
    val eventsExchange = MigrationAction.DeclareExchange("events", "topic")
    val binding = MigrationAction.DeclareBinding("events", "orders", "order.created")

    /** Three steps that the wizard can build. */
    val threeSteps: List<MigrationAction> = listOf(ordersQueue, eventsExchange, binding)

    /** A rig with [rawText] in the folder (and the migration 0004 as latest) and the planner open on it. */
    fun open(rawText: String = raw(actions = threeSteps.toTypedArray()), version: String = VERSION, context: PlannerContext = PlannerContext()): PlannerRig =
        PlannerRig(
            context = context,
            rawFiles = listOf(rawText),
            mode = PlannerMode.EditFile(version, checksum(rawText)),
        )

    /** Steps the wizard cannot build: arguments, a delete, an operator policy. */
    val readOnlyThree: List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("jobs", "quorum", arguments = mapOf("x-single-active-consumer" to ArgumentValue.Flag(true))),
        MigrationAction.DeleteQueue("legacy-orders"),
        MigrationAction.DeclareOperatorPolicy("op-limit", "^jobs$", mapOf("max-length" to ArgumentValue.Whole(100)), "/", "queues", 0),
    )

    fun policy(name: String, limit: Long) =
        MigrationAction.DeclarePolicy(name, "^orders$", mapOf("max-length" to ArgumentValue.Whole(limit)), "/", "queues")
}
