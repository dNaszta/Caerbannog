package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction

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
            accessLine = generated.access?.let(MigrateFromThreeTexts::accessLine),
            usersNotMigrated = generated.notMigrated.map { LeftOutRow(escapeForText(it.name), notMigratedText(it.reason)) },
            permissionsLeftOut = generated.leftOutPermissions.map {
                LeftOutRow(MigrateFromThreeTexts.permissionLabel(escapeForText(it.user), escapeForText(it.vhost)), MigrateFromThreeTexts.USER_NOT_MIGRATED)
            },
            secretWarning = MigrateFromThreeTexts.SECRET_FILE_WARNING.takeIf {
                generated.migrations.any { m -> m.file.up.any { it is MigrationAction.DeclareUser } }
            },
        )
    }

    /** A file as it will be listed: [path] is escaped text for display, empty while the file is not saved. */
    fun fileOf(migration: GeneratedMigration, path: String): SavedFile = SavedFile(
        version = migration.file.version,
        vhost = if (migration.access) MigrateFromThreeTexts.ACCESS_FILE_LABEL else escapeForText(migration.vhost),
        description = escapeForText(migration.file.description),
        actionCount = migration.file.up.size,
        path = path,
    )

    private fun notMigratedText(reason: NotMigratedReason): String = when (reason) {
        NotMigratedReason.Guest -> MigrateFromThreeTexts.GUEST_NOT_MIGRATED
        NotMigratedReason.NoPasswordHash -> MigrateFromThreeTexts.NO_HASH_NOT_MIGRATED
        NotMigratedReason.NoAlgorithm -> MigrateFromThreeTexts.NO_ALGORITHM_NOT_MIGRATED
        // The algorithm name comes from the file: escaped like every name.
        is NotMigratedReason.UnsupportedAlgorithm -> MigrateFromThreeTexts.unsupportedAlgorithmNotMigrated(escapeForText(reason.algorithm))
    }

    private fun skippedText(reason: SkipReason): String = when (reason) {
        SkipReason.SkippedByUser -> MigrateFromThreeTexts.SKIPPED_BY_YOU
        SkipReason.NameCannotBeWritten -> MigrateFromThreeTexts.NAME_CANNOT_BE_WRITTEN
    }
}
