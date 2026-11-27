package dev.caerbannog.core.access

/** M10-04: a fake [PatternEngine] over `kotlin.text.Regex` (test only: the production engine is RE2/J). Records every pattern it was asked to compile, so a test can prove an ORDER of checks. */
class RecordingEngine(private val rejects: Set<String> = emptySet()) : PatternEngine {
    val compiled = mutableListOf<String>()

    override fun compile(pattern: String): PatternCompilation {
        compiled += pattern
        if (pattern in rejects) return PatternCompilation.Rejected
        val regex = try { Regex(pattern) } catch (_: IllegalArgumentException) { return PatternCompilation.Rejected }
        return PatternCompilation.Compiled(CompiledPattern { regex.containsMatchIn(it) })
    }
}

fun cell(text: String, hidden: Boolean = false): PatternCell =
    if (hidden) PatternCell(SecretGuard.HIDDEN, PatternReading.HasPattern(SecretGuard.HIDDEN), hidden = true)
    else PatternCell(text, if (text.isEmpty()) PatternReading.NoPermission else PatternReading.HasPattern(text))

fun permissions(
    configure: PatternCell = cell(".*"), write: PatternCell = cell(".*"), read: PatternCell = cell(".*"), vhost: String = "/orders",
    topic: List<TopicPermissionRow> = emptyList(), user: String = "svc-orders",
) = UserPermissions(user, listOf(PermissionRow(vhost, configure, write, read)), topic)

fun topicRow(exchange: String = "amq.topic", write: PatternCell = cell(".*"), read: PatternCell = cell(".*"), vhost: String = "/orders") = TopicPermissionRow(vhost, exchange, write, read)

fun publish(name: String, vhost: String = "/orders", routingKey: String? = null) = AccessQuestion(AccessAction.PublishToExchange, vhost, name, routingKey)

fun readQueue(name: String, vhost: String = "/orders") = AccessQuestion(AccessAction.ReadFromQueue, vhost, name)

fun configure(name: String, vhost: String = "/orders") = AccessQuestion(AccessAction.Configure, vhost, name)
