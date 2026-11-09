package dev.caerbannog.core.migrations

interface MigrationFileRepository {
    suspend fun listFiles(): List<MigrationFile>
    suspend fun importFile(rawJson: String): MigrationValidationResult
    suspend fun exportTopology(
        version: String,
        description: String,
        actions: List<MigrationAction>,
    ): MigrationFile
    suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult
    suspend fun nextVersion(): String

    /** Valid migrations, the invalid files and the folder state in one answer; the default suits repositories without a folder. */
    suspend fun load(): FolderListing = FolderListing.Listed(listFiles())
}

class InMemoryMigrationFileRepository : MigrationFileRepository {
    private val files = mutableMapOf<String, MigrationFile>()

    override suspend fun listFiles(): List<MigrationFile> {
        return files.values.sortedBy { it.version }
    }

    override suspend fun importFile(rawJson: String): MigrationValidationResult {
        val existing = files.keys.toSet()
        val result = MigrationParser.parse(rawJson, existing)
        if (result is MigrationValidationResult.Valid) {
            files[result.migration.version] = result.migration
        }
        return result
    }

    override suspend fun exportTopology(
        version: String,
        description: String,
        actions: List<MigrationAction>,
    ): MigrationFile {
        val rawJson = MigrationParser.exportToJson(version, description, actions)
        val checksum = MigrationParser.computeChecksum(rawJson)
        val file = MigrationFile(
            version = version,
            description = description,
            up = actions,
            rawJson = rawJson,
            checksum = checksum,
        )
        files[version] = file
        return file
    }

    override suspend fun updateFile(version: String, newRawJson: String): MigrationValidationResult {
        val existingOthers = files.keys.filter { it != version }.toSet()
        val result = MigrationParser.parse(newRawJson, existingOthers)
        if (result is MigrationValidationResult.Valid) {
            files[version] = result.migration
        }
        return result
    }

    override suspend fun nextVersion(): String {
        val maxNumber = files.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
        return (maxNumber + 1).toString().padStart(4, '0')
    }

    fun addFile(migrationFile: MigrationFile) {
        files[migrationFile.version] = migrationFile
    }

    fun clear() {
        files.clear()
    }
}
