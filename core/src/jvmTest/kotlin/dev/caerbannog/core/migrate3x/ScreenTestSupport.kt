package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import kotlinx.serialization.json.JsonPrimitive

/** M8-06a: held analyses for the screen tests, built through the real parser and analyzer (no hand-made findings). */
internal object ScreenFiles {
    const val SENTINEL_HASH = "SENTINEL-HASH"
    const val SENTINEL_PASSWORD = "SENTINEL-PASSWORD"

    /** Blockers (ha policy key, non-durable queue), warnings, infos (unreadable pattern), message loss and a compatible exchange and binding. */
    val mixed: String = DefinitionsFiles.file(
        queues = listOf(
            DefinitionsFiles.queue("orders"),
            DefinitionsFiles.queue("jobs", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
            DefinitionsFiles.queue("temp", durable = false),
            DefinitionsFiles.queue("modern", type = "quorum"),
        ),
        exchanges = listOf(DefinitionsFiles.exchange("events"), DefinitionsFiles.exchange("audit", "fanout")),
        bindings = listOf(DefinitionsFiles.binding("events", "modern", "k")),
        policies = listOf(
            DefinitionsFiles.policy("ha-all", "^orders", mapOf("ha-mode" to JsonPrimitive("all"))),
            DefinitionsFiles.policy("lookahead", "^(?!amq\\.).*", mapOf("max-length" to JsonPrimitive(10))),
        ),
    )

    val withUserAndShovel: String get() = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)

    fun parse(text: String): DefinitionsDocument = (DefinitionsParser.parse(text) as? DefinitionsParseResult.Parsed)?.document
        ?: error("the test file was not parsed as a full export")

    fun held(text: String = mixed, label: String = "legacy.json"): HeldAnalysis {
        val document = parse(text)
        return HeldAnalysis(label, document, CompatibilityAnalyzer.analyze(document))
    }
}
