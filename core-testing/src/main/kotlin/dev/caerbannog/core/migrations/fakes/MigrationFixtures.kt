package dev.caerbannog.core.migrations.fakes

import dev.caerbannog.core.migrations.MigrationAction

/**
 * M8-04: reads the migration files of `core-testing/src/main/resources/fixtures/m8-04/`. One copy for `core` jvmTest and `acceptance-tests`.
 * [OLD_FORMAT] is a file in the format of the first release (M5-04); [OLD_FORMAT_CHECKSUM] was computed with the code of that release
 * and is written here, never recomputed from the parser under test. [EXPORT_GOLDEN] is what `MigrationParser.exportToJson` wrote for
 * the default-only action list of MigrationBackwardCompatibilityTest before M8-04 (recorded once from the M5-04 code, byte for byte).
 */
object MigrationFixtures {
    const val OLD_FORMAT: String = "migration-old-format.json"
    const val WIDENED: String = "migration-widened.json"
    const val EXPORT_GOLDEN: String = "migration-export-golden.json"

    /** FNV-1a 64 over the normalised text of [OLD_FORMAT], computed independently of the parser (a few lines of Python), 2026-11-09. */
    const val OLD_FORMAT_CHECKSUM: String = "0d4b4988f4940d74"

    fun read(name: String): String {
        val path = "fixtures/m8-04/$name"
        val stream = MigrationFixtures::class.java.classLoader.getResourceAsStream(path) ?: error("missing fixture $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Actions that use no M8-04 feature (every new field at its default); `exportToJson` of this list must equal [EXPORT_GOLDEN] byte for byte. */
    fun defaultOnlyActions(): List<MigrationAction> = listOf(
        MigrationAction.DeclareQueue("orders", "quorum"),
        MigrationAction.DeclareQueue("scratch", "classic", vhost = "uat", durable = false),
        MigrationAction.DeclareStream("audit"),
        MigrationAction.DeclareExchange("events", "topic"),
        MigrationAction.DeclareExchange("tmp.x", "fanout", durable = false),
        MigrationAction.DeclareBinding("events", "orders", "order.created"),
        MigrationAction.DeclareBinding("events", "audit"),
        MigrationAction.DeclarePolicy("limits", "^orders"),
        MigrationAction.DeleteBinding("events", "old", "x"),
        MigrationAction.DeleteQueue("old"),
        MigrationAction.DeleteExchange("old.x"),
        MigrationAction.DeleteStream("old.stream"),
        MigrationAction.DeletePolicy("old-policy"),
    )
}
