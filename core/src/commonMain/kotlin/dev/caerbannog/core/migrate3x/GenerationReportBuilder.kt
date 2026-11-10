package dev.caerbannog.core.migrate3x

/** Pure: the result panel of a generation. Every string taken from the file or the folder path is escaped here, so the screen only draws. */
internal object GenerationReportBuilder {
    fun report(how: How, files: List<SavedFile>, generated: GenerationResult.Generated, virtualHosts: Int, extraNotes: List<String> = emptyList()): GenerationReport {
        val skipped = generated.skipped.map { LeftOutRow(escapeForText(it.item.name.orEmpty()), skippedText(it.reason)) }
        val (bindings, others) = generated.notes.partition { it.item?.kind == ItemKind.Binding }
        val bindingRows = bindings.map { LeftOutRow(escapeForText(it.item?.name.orEmpty()), escapeForText(it.text)) }
        val headline = when {
            generated.migrations.isEmpty() -> MigrateFromThreeTexts.NOTHING_WRITTEN
            how == How.Saved -> MigrateFromThreeTexts.savedHeadline(files.size)
            else -> MigrateFromThreeTexts.exportedHeadline(files.size)
        }
        return GenerationReport(
            how = how,
            headline = headline,
            files = files,
            leftOut = skipped + bindingRows,
            notes = others.map { escapeForText(it.text) } + extraNotes,
            vhostHint = if (virtualHosts > 1) MigrateFromThreeTexts.vhostHint(virtualHosts) else null,
        )
    }

    /** A file as it will be listed: [path] is escaped text for display, empty while the file is not saved. */
    fun fileOf(migration: GeneratedMigration, path: String): SavedFile = SavedFile(
        version = migration.file.version,
        vhost = escapeForText(migration.vhost),
        description = escapeForText(migration.file.description),
        actionCount = migration.file.up.size,
        path = path,
    )

    private fun skippedText(reason: SkipReason): String = when (reason) {
        SkipReason.SkippedByUser -> MigrateFromThreeTexts.SKIPPED_BY_YOU
        SkipReason.NameCannotBeWritten -> MigrateFromThreeTexts.NAME_CANNOT_BE_WRITTEN
    }
}
