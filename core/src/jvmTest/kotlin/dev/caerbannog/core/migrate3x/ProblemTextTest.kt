package dev.caerbannog.core.migrate3x

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-06b ruling 3: `MigrateFromThreeTexts.problemText` builds every problem text; one test per [SaveProblem]. */
class ProblemTextTest {
    @Test
    fun `no folder chosen`() = assertEquals("No folder was chosen. Nothing was saved.", MigrateFromThreeTexts.problemText(SaveProblem.NoFolderChosen))

    @Test
    fun `a folder problem is its message, then no file was saved`() {
        assertEquals("The folder is read-only. No file was saved.", MigrateFromThreeTexts.problemText(SaveProblem.Folder("/m", "The folder is read-only")))
        assertEquals("The folder does not exist. No file was saved.", MigrateFromThreeTexts.problemText(SaveProblem.Folder("/m", "The folder does not exist")))
    }

    @Test
    fun `a refused file names the version, the reason and that the saved files stay`() {
        val text = MigrateFromThreeTexts.problemText(SaveProblem.FileRefused("0002", "the file could not be written"))
        assertTrue(text.startsWith("0002.json was not saved: the file could not be written."), text)
        assertTrue(text.endsWith("The saved files stay in the folder."), text)
    }

    @Test
    fun `no free version`() = assertEquals(
        "The migrations folder has no free version number left (the highest is 9999).",
        MigrateFromThreeTexts.problemText(SaveProblem.NoFreeVersion),
    )

    @Test
    fun `the path of a folder problem is not part of the text (the panel shows it under it)`() =
        assertFalse(MigrateFromThreeTexts.problemText(SaveProblem.Folder("/secret/path", "The folder is read-only")).contains("/secret/path"))
}
