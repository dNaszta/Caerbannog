package dev.caerbannog.devseed

/**
 * The `POST /api/definitions` body for the many-queues set (D3 refresh-cost trial). Every queue is declared with the
 * same arguments on each run, so importing it twice is accepted by the broker (idempotent). Vhost, users and
 * permissions belong to 001-base.json, not here.
 */
object ManyQueuesDefinitions {
    fun build(vhost: String, count: Int): String {
        val escapedVhost = escape(vhost)
        return QueueNames.generate(count).joinToString(prefix = "{\"queues\":[", postfix = "]}", separator = ",") { name ->
            "{\"name\":\"${escape(name)}\",\"vhost\":\"$escapedVhost\",\"durable\":true,\"auto_delete\":false," +
                "\"arguments\":{\"x-queue-type\":\"quorum\"}}"
        }
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
