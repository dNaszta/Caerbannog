package dev.caerbannog.core.migrations

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-02: the folder verdict of the Migrations screen as a pure function `folderStateOf(listing, path)` (extracted from the private function
 * of `MigrationsViewModel`, no behaviour change), so the planner and the screen cannot disagree. Written from the old private function.
 */
class FolderStateOfTest {
    private val path = "/work/migrations"

    @Test
    fun `no listing means choose a folder, whatever the path`() {
        assertEquals(MigrationFolderState.ChooseFolder, folderStateOf(null, null))
        assertEquals(MigrationFolderState.ChooseFolder, folderStateOf(null, path))
    }

    @Test
    fun `an unavailable folder carries its path and typed problem`() {
        FolderProblem.entries.forEach { problem ->
            assertEquals(
                MigrationFolderState.FolderUnavailable("/gone", problem),
                folderStateOf(FolderListing.Unavailable("/gone", problem), path),
            )
        }
    }

    @Test
    fun `a read-only listing is read-only with the chosen path`() {
        assertEquals(MigrationFolderState.FolderReadOnly(path), folderStateOf(FolderListing.Listed(emptyList(), readOnly = true), path))
    }

    @Test
    fun `a read-only listing without a known path is ready (the old in-memory mode)`() {
        assertEquals(MigrationFolderState.Ready(null), folderStateOf(FolderListing.Listed(emptyList(), readOnly = true), null))
    }

    @Test
    fun `a writable listing is ready with the path`() {
        assertEquals(MigrationFolderState.Ready(path), folderStateOf(FolderListing.Listed(emptyList()), path))
        assertEquals(MigrationFolderState.Ready(null), folderStateOf(FolderListing.Listed(emptyList()), null))
    }
}
