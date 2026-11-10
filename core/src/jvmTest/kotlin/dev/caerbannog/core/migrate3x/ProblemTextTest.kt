package dev.caerbannog.core.migrate3x

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** M8-06b rulings 3 and 13: `MigrateFromThreeTexts.problemText(trouble)` builds every problem text; one test per [SaveProblem], full sentences. */
class ProblemTextTest {
    private fun file(version: String, saved: Boolean = false) = SavedFile(version, "/", "d", 1, if (saved) "/m/$version.json" else "")
    private fun trouble(problem: SaveProblem, saved: List<SavedFile> = emptyList(), unsaved: List<SavedFile> = emptyList()) =
        GenerationState.Trouble(problem, saved, unsaved)
    private fun text(t: GenerationState.Trouble) = MigrateFromThreeTexts.problemText(t)

    @Test
    fun `no folder chosen`() = assertEquals("No folder was chosen. Nothing was saved.", text(trouble(SaveProblem.NoFolderChosen)))

    @Test
    fun `a folder problem is its message, then no file was saved, without the path`() {
        assertEquals("The folder is read-only. No file was saved.", text(trouble(SaveProblem.Folder("/m", "The folder is read-only"))))
        assertEquals("The folder does not exist. No file was saved.", text(trouble(SaveProblem.Folder("/m", "The folder does not exist"))))
        assertFalse(text(trouble(SaveProblem.Folder("/secret/path", "The folder is read-only"))).contains("/secret/path"))
    }

    @Test
    fun `no free version`() = assertEquals(
        "The migrations folder has no free version number left (the highest is 9999).",
        text(trouble(SaveProblem.NoFreeVersion)),
    )

    @Test
    fun `a refused file with one saved and two unsaved versions`() = assertEquals(
        "0004.json was not saved: that version already exists. Saved: 0003. Not saved: 0004, 0005. The saved files stay in the folder.",
        text(trouble(SaveProblem.FileRefused("0004", "that version already exists"), listOf(file("0003", true)), listOf(file("0004"), file("0005")))),
    )

    @Test
    fun `a refused file with none saved reads none`() = assertEquals(
        "0001.json was not saved: the file could not be written. Saved: none. Not saved: 0001. The saved files stay in the folder.",
        text(trouble(SaveProblem.FileRefused("0001", "the file could not be written"), emptyList(), listOf(file("0001")))),
    )

    @Test
    fun `several saved versions are joined in file order`() = assertEquals(
        "0005.json was not saved: r. Saved: 0003, 0004. Not saved: 0005. The saved files stay in the folder.",
        text(trouble(SaveProblem.FileRefused("0005", "r"), listOf(file("0003", true), file("0004", true)), listOf(file("0005")))),
    )

    @Test
    fun `a reason with a control character is escaped`() {
        val shown = text(trouble(SaveProblem.FileRefused("0002", "bad\u0007‮name"), listOf(file("0001", true)), listOf(file("0002"))))
        assertEquals("0002.json was not saved: bad\\u0007\\u202Ename. Saved: 0001. Not saved: 0002. The saved files stay in the folder.", shown)
        assertFalse(shown.any { it == '\u0007' || it == '‮' })
    }
}
