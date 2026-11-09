package dev.caerbannog.core.migrations.fakes

/**
 * M5-08a round 1: THE table of statuses (and the one body fact that matters) of the management API calls a migration executor makes, MEASURED on
 * `rabbitmq:4-management` (2026-11-09, `RealBrokerM5MigrationTest` measures it again and fails with the measured value on any difference).
 * [FakeManagementBroker] answers from this table and from nothing else, so the fake can never again invent a status or a body (the first fake
 * invented `PRECONDITION_FAILED` and a 204 for a repeated binding).
 *
 * [bodyHas] is a substring the broker's answer contains (null: no claim); it exists only to describe the real answer, the app never matches it.
 */
object ManagementStatusTable {

    data class Row(val id: String, val status: Int, val bodyHas: String? = null)

    private val notFoundBody = """{"error":"not_found","reason":"Not Found"}"""

    val rows: List<Row> = listOf(
        Row("queue.put.created", 201),
        Row("queue.put.repeated", 204),
        Row("queue.put.other-settings", 400, "inequivalent arg"),
        Row("queue.get.absent", 404, "Not Found"),
        Row("queue.delete.present", 204),
        Row("queue.delete.absent", 404, "Not Found"),
        Row("exchange.put.created", 201),
        Row("exchange.put.repeated", 204),
        Row("exchange.put.other-settings", 400, "inequivalent arg"),
        Row("exchange.put.unknown-type", 400, "unknown exchange type"),
        Row("exchange.get.absent", 404, "Not Found"),
        Row("exchange.delete.present", 204),
        Row("exchange.delete.absent", 404, "Not Found"),
        Row("binding.post.created", 201),
        Row("binding.post.repeated", 201),
        Row("binding.post.missing-source", 404, "no exchange"),
        Row("binding.post.missing-destination", 404, "no queue"),
        Row("binding.get.list", 200),
        Row("binding.get.absent-items", 200),
        Row("binding.delete.present", 204),
        Row("binding.delete.absent", 404, "Not Found"),
        Row("policy.put.created", 201),
        Row("policy.put.replaced", 204),
        Row("policy.put.unrecognised-key", 400, "are not recognised policy settings"),
        Row("policy.get.absent", 404, "Not Found"),
        Row("policy.delete.present", 204),
        Row("policy.delete.absent", 404, "Not Found"),
        Row("operator-policy.put.created", 201),
        Row("operator-policy.put.replaced", 204),
        Row("operator-policy.get.absent", 404, "Not Found"),
        Row("operator-policy.delete.present", 204),
        Row("operator-policy.delete.absent", 404, "Not Found"),
        Row("vhost.put.queue.missing-vhost", 404, "Not Found"),
        Row("vhost.put.exchange.missing-vhost", 404, "Not Found"),
        Row("vhost.get.queue.missing-vhost", 404, "Not Found"),
    )

    private val byId = rows.associateBy { it.id }

    fun status(id: String): Int = (byId[id] ?: error("no row $id in the measured table")).status

    /** The status as a reply with a body shaped like the measured one. */
    fun reply(id: String, detail: String = ""): FakeManagementBroker.Reply {
        val row = byId[id] ?: error("no row $id in the measured table")
        val body = when {
            row.status in 200..299 -> ""
            row.bodyHas == "Not Found" -> notFoundBody
            row.status == 404 -> """{"error":"not_found","reason":"${row.bodyHas}${if (detail.isEmpty()) "" else " $detail"}"}"""
            row.bodyHas == "inequivalent arg" -> """{"error":"bad_request","reason":"inequivalent arg $detail"}"""
            row.bodyHas == "unknown exchange type" -> """{"error":"bad_request","reason":"unknown exchange type $detail"}"""
            else -> """{"error":"bad_request","reason":"Validation failed\n\n[{<<\"$detail\">>,1}] ${row.bodyHas}\n"}"""
        }
        return FakeManagementBroker.Reply(row.status, body)
    }
}
