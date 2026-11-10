package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.ReviewFiles.q
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrations.FOLDER_READ_ONLY_MESSAGE
import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8-06b: "Generate and save" of the brief, through the view model with a fake migrations folder. The folder fake behaves like the real
 * one (a version that exists is refused, never overwritten; a read-only folder refuses every write) and records every write attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateFlowTest {
    private val m = "/migrations"

    private fun TestScope.rig(text: String = ReviewFiles.orders, initial: String? = m, versions: List<String> = emptyList(), vararg picks: String?): Pair<ReviewRig, dev.caerbannog.core.migrations.fakes.FakeMigrationFolder> {
        val folders = FakeMigrationFolders(initial, *picks)
        val folder = folders.folder(m)
        versions.forEach { folder.addMigration(it) }
        return ReviewRig(this, text, folders) to folder
    }

    // ---- pending blocks generation ----

    @Test
    fun `generate while items are undecided changes nothing but the line, opens the next undecided item and asks no folder`() = runTest {
        val (r, folder) = rig(ReviewFiles.three)
        r.accept(q("orders"))
        r.vm.generate(); r.settle()
        assertEquals(GenerationState.Idle, r.generation)
        assertEquals("2 items need a decision", r.review.pendingLine)
        assertEquals(q("invoices"), r.sheet.item, "the next undecided item opens")
        assertEquals(emptyList(), folder.attempts, "nothing is written")
        assertEquals(emptyList(), r.folders.repositoriesAsked, "the folder is not even read")
        assertEquals(emptyList(), r.folders.chooser.startedIn)
        assertFalse(r.holder.saved.value)
    }

    // ---- the happy path ----

    @Test
    fun `generate saves the file with the next free version after the highest existing one, and reports it`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders, versions = listOf("0001", "0002"))
        r.accept(q("orders"))
        r.generate()
        val report = r.done()
        assertEquals(How.Saved, report.how)
        assertEquals("1 migration saved", report.headline)
        val file = report.files.single()
        assertEquals(listOf("0003", "/", "Migrated from RabbitMQ 3.x legacy.json vhost /", "/migrations/0003.json"), listOf(file.version, file.vhost, file.description, file.path))
        assertEquals(1, file.actionCount)
        assertEquals(listOf("0003"), folder.written)
        assertEquals(setOf("0001", "0002", "0003"), folder.files.keys)
        assertTrue(r.holder.saved.value, "files were saved from this analysis")
        assertFalse(r.review.canGenerate && r.generation is GenerationState.Saving)
        assertEquals(emptyList(), r.saver.saves, "saving into the folder is not a save dialog")
    }

    @Test
    fun `an invalid file named like a version still occupies that version`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders, versions = listOf("0001"))
        folder.addInvalidFile("0007.json")
        folder.addInvalidFile("notes.json")
        folder.addInvalidFile("0009.json.bak")
        r.accept(q("orders")); r.generate()
        assertEquals("0008", r.done().files.single().version, "0007.json occupies 0007; notes.json and 0009.json.bak are not versions")
        assertEquals(listOf("0008"), folder.written)
    }

    @Test
    fun `the file is the ordinary migration the core generated, written exactly as generate returned it`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        r.accept(q("orders")); r.generate()
        val expected = (r.core.generate(GenerationRequest(emptySet(), "legacy.json")) as GenerationResult.Generated).migrations.single().file.rawJson
        assertEquals(expected, folder.attempts.single())
    }

    @Test
    fun `one file per source virtual host with consecutive versions and the number of actions of each`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts, versions = listOf("0001", "0002"))
        r.decideAll(); r.generate()
        val files = r.done().files
        assertEquals(listOf("0003", "0004"), files.map { it.version })
        assertEquals(listOf("/", "other"), files.map { it.vhost })
        assertEquals(listOf("/migrations/0003.json", "/migrations/0004.json"), files.map { it.path })
        assertEquals("2 migrations saved", r.done().headline)
        assertEquals(listOf("0003", "0004"), folder.written)
    }

    @Test
    fun `a result for a file with more than one virtual host says each file applies only on a matching connection`() = runTest {
        val (r, _) = rig(ReviewFiles.twoVhosts)
        r.decideAll(); r.generate()
        assertEquals("Each file applies only on a connection whose virtual host matches. A source with 2 virtual hosts needs one 4.x profile per virtual host.", r.done().vhostHint)
        val (one, _) = rig(ReviewFiles.orders)
        one.decideAll(); one.generate()
        assertNull(one.done().vhostHint)
    }

    @Test
    fun `a generation that has nothing left to write says so and writes no file`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        r.skip(q("orders")); r.generate()
        val report = r.done()
        assertEquals("No migration was written: nothing is left to write", report.headline)
        assertEquals(emptyList(), report.files)
        assertEquals(emptyList(), folder.attempts)
    }

    // ---- no folder ----

    @Test
    fun `without a chosen folder the app asks, and a cancel saves nothing and says no folder was chosen`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders, initial = null, picks = arrayOf(null))
        r.decideAll(); r.generate()
        assertEquals(listOf<String?>(null), r.folders.chooser.startedIn, "the dialog was shown once, with no start folder")
        assertEquals(GenerationState.Trouble(SaveProblem.NoFolderChosen, emptyList(), emptyList()), r.generation)
        assertNull(r.folders.store.folder, "nothing was chosen, nothing is stored")
        assertEquals(emptyList(), folder.attempts)
        assertEquals(emptyList(), r.folders.repositoriesAsked)
        assertFalse(r.holder.saved.value)
    }

    @Test
    fun `a folder picked at the question is stored for the Migrations screen too and used`() = runTest {
        val folders = FakeMigrationFolders(null, "/picked")
        val r = ReviewRig(this, ReviewFiles.orders, folders)
        r.decideAll(); r.generate()
        assertEquals("/picked", folders.store.folder)
        assertEquals(listOf("/picked/0001.json"), r.done().files.map { it.path })
        assertEquals(listOf("0001"), folders.folder("/picked").written)
    }

    @Test
    fun `choose folder from the trouble panel offers the current folder, a cancel changes nothing, a pick restarts the flow`() = runTest {
        val folders = FakeMigrationFolders(null, null, "/second")
        val r = ReviewRig(this, ReviewFiles.orders, folders)
        r.decideAll(); r.generate()
        assertIs<SaveProblem.NoFolderChosen>(r.trouble().problem)
        r.vm.chooseFolder(); r.settle()
        assertIs<GenerationState.Trouble>(r.generation, "cancelling the dialog changes nothing")
        assertNull(folders.store.folder)
        folders.chooser.answerNext("/second")
        r.vm.chooseFolder(); r.settle()
        assertEquals("/second", folders.store.folder)
        assertEquals("/second/0001.json", r.done().files.single().path, "the flow restarted at the folder step")
    }

    @Test
    fun `choose folder offers the folder that is chosen now as the starting place`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        r.decideAll()
        folder.readOnly = true
        r.generate()
        r.vm.chooseFolder(); r.settle()
        assertEquals(listOf<String?>(m), r.folders.chooser.startedIn)
    }

    // ---- folder problems: nothing is written ----

    @Test
    fun `a folder that does not exist or cannot be read is a trouble with the fixed text, no file is saved`() = runTest {
        for (problem in FolderProblem.entries) {
            val (r, folder) = rig(ReviewFiles.orders)
            folder.unavailable = problem
            r.decideAll(); r.generate()
            assertEquals(GenerationState.Trouble(SaveProblem.Folder(m, problem.message), emptyList(), emptyList()), r.generation, "for $problem")
            assertEquals(emptyList(), folder.attempts, "nothing is written for $problem")
            assertFalse(r.holder.saved.value)
        }
    }

    @Test
    fun `a read-only folder is reported and nothing is written, not even an attempt`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        folder.readOnly = true
        r.decideAll(); r.generate()
        assertEquals(GenerationState.Trouble(SaveProblem.Folder(m, FOLDER_READ_ONLY_MESSAGE), emptyList(), emptyList()), r.generation)
        assertEquals(emptyList(), folder.attempts, "the flow checks readOnly before it writes")
        assertEquals(emptySet(), folder.files.keys)
    }

    @Test
    fun `from a folder problem the user can export the files instead, numbered from 0001, and the folder is never touched`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders, versions = listOf("0001", "0002"))
        folder.readOnly = true
        r.decideAll(); r.generate()
        val written = folder.files.keys
        r.saver.outcome = SaveOutcome.Saved("/home/me/0001.json")
        r.vm.exportFiles(); r.settle()
        val report = r.done()
        assertEquals(How.Exported, report.how)
        assertEquals("1 migration exported", report.headline)
        assertEquals(listOf("0001.json"), r.saver.saves.map { it.suggestedName }, "no folder to number from: the numbering starts at 0001")
        assertTrue(r.saver.saves.single().text.contains("\"version\": \"0001\""), r.saver.saves.single().text)
        assertEquals(emptyList(), folder.attempts)
        assertEquals(written, folder.files.keys)
    }

    @Test
    fun `export with a cancelled dialog stops quietly and stays on the trouble`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        folder.readOnly = true
        r.decideAll(); r.generate()
        r.saver.outcome = SaveOutcome.Cancelled
        r.vm.exportFiles(); r.settle()
        assertIs<GenerationState.Trouble>(r.generation)
        assertNull(r.report.notice, "a cancelled dialog shows nothing")
    }

    @Test
    fun `export that fails says so with a fixed reason and keeps the trouble`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        folder.readOnly = true
        r.decideAll(); r.generate()
        r.saver.outcome = SaveOutcome.Failed("the disk is full")
        r.vm.exportFiles(); r.settle()
        assertIs<GenerationState.Trouble>(r.generation)
        assertEquals(ExportNotice("The migration file could not be saved: the disk is full", failed = true), r.report.notice)
    }

    // ---- a file that cannot be saved after another one was ----

    @Test
    fun `when the second file cannot be saved the first stays, the problem names both and the rest can be retried`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts)
        folder.failWrite(2, "the file could not be written")
        r.decideAll(); r.generate()
        val trouble = r.trouble()
        assertEquals(SaveProblem.FileRefused("0002", "the file could not be written"), trouble.problem)
        assertEquals(listOf("0001"), trouble.saved.map { it.version })
        assertEquals(listOf("/migrations/0001.json"), trouble.saved.map { it.path })
        assertEquals(listOf("0002"), trouble.unsaved.map { it.version })
        assertEquals(listOf(""), trouble.unsaved.map { it.path }, "an unsaved file has no path")
        assertEquals(listOf("other"), trouble.unsaved.map { it.vhost })
        assertEquals(setOf("0001"), folder.files.keys, "the saved file stays, nothing is deleted or rolled back")
        assertEquals(listOf("0001"), folder.written)
    }

    @Test
    fun `retry saves only the files that were not saved, with the same versions`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts)
        folder.failWrite(2)
        r.decideAll(); r.generate()
        val firstRaw = folder.files.getValue("0001").rawJson
        r.vm.retrySave(); r.settle()
        val report = r.done()
        assertEquals(listOf("0001", "0002"), report.files.map { it.version })
        assertEquals(listOf("/migrations/0001.json", "/migrations/0002.json"), report.files.map { it.path })
        assertEquals(listOf("0001", "0002"), folder.written)
        assertEquals(3, folder.attempts.size, "two first attempts and one retry: the saved file is not written again")
        assertEquals(firstRaw, folder.files.getValue("0001").rawJson)
        assertTrue(r.holder.saved.value)
    }

    @Test
    fun `retry when the version was taken meanwhile refuses with that reason, overwrites nothing, and generate again uses new versions`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts)
        folder.failWrite(2)
        r.decideAll(); r.generate()
        folder.beforeWrite = { attempt -> if (attempt == 3) folder.addMigration("0002") }
        val takenRaw = { folder.files.getValue("0002").rawJson }
        r.vm.retrySave(); r.settle()
        val trouble = r.trouble()
        assertEquals(SaveProblem.FileRefused("0002", "that version already exists"), trouble.problem)
        val before = takenRaw()
        r.vm.generateAgain(); r.settle()
        val report = r.done()
        assertEquals(before, takenRaw(), "the file that took the version is untouched")
        assertEquals(listOf("0003", "0004"), report.files.map { it.version }, "new versions above the highest")
    }

    @Test
    fun `a partial save can export the file that was not saved`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts)
        folder.failWrite(2)
        r.decideAll(); r.generate()
        r.saver.outcome = SaveOutcome.Saved("/x/0002.json")
        r.vm.exportFiles(); r.settle()
        assertEquals(listOf("0002.json"), r.saver.saves.map { it.suggestedName }, "only the unsaved file goes to the dialog, under its own version")
        val report = r.done()
        assertEquals(How.Exported, report.how)
        assertEquals(setOf("0001"), folder.files.keys)
    }

    // ---- generating again ----

    private fun TestScope.savedTwice(): Pair<ReviewRig, dev.caerbannog.core.migrations.fakes.FakeMigrationFolder> {
        val (r, folder) = rig(ReviewFiles.twoVhosts, versions = listOf("0001", "0002"))
        r.decideAll(); r.generate()
        assertEquals(listOf("0003", "0004"), r.done().files.map { it.version })
        return r to folder
    }

    @Test
    fun `generating again after files were saved asks first and writes nothing until confirmed, and cancel writes nothing`() = runTest {
        val (r, folder) = savedTwice()
        r.skip(q("orders"))
        val attempts = folder.attempts.size
        r.generate()
        assertEquals(GenerationState.ConfirmAgain, r.generation)
        assertEquals(attempts, folder.attempts.size, "asking writes nothing")
        r.vm.cancelGenerateAgain(); r.settle()
        assertEquals(GenerationState.Idle, r.generation)
        assertEquals(attempts, folder.attempts.size, "cancel writes nothing")
    }

    @Test
    fun `confirming writes new files with the next versions and leaves the old ones as they are`() = runTest {
        val (r, folder) = savedTwice()
        val old = listOf("0003", "0004").associateWith { folder.files.getValue(it).rawJson }
        r.skip(q("orders")); r.accept(q("orders"))
        r.generate()
        assertEquals(GenerationState.ConfirmAgain, r.generation)
        r.vm.generateAgain(); r.settle()
        assertEquals(listOf("0005", "0006"), r.done().files.map { it.version })
        assertEquals(old, listOf("0003", "0004").associateWith { folder.files.getValue(it).rawJson })
        assertEquals(listOf("0003", "0004", "0005", "0006"), folder.written)
    }

    @Test
    fun `a second generation without any change still asks`() = runTest {
        val (r, _) = savedTwice()
        r.generate()
        assertEquals(GenerationState.ConfirmAgain, r.generation)
    }

    @Test
    fun `the question survives a new window view model - it depends on the held analysis, not on the view model`() = runTest {
        val (r, _) = savedTwice()
        val second = r.newVm(); r.settle()
        second.generate(); r.settle()
        assertEquals(GenerationState.ConfirmAgain, (second.state.value as MigrateFromThreeState.Report).review.generation)
    }

    @Test
    fun `a new analysis forgets that files were saved`() = runTest {
        val (r, _) = savedTwice()
        r.vm.open(r.path); r.settle()
        assertFalse(r.holder.saved.value)
        r.decideAll(); r.generate()
        assertIs<GenerationState.Done>(r.generation)
    }

    @Test
    fun `dismissing the result goes back to idle and the decisions stay`() = runTest {
        val (r, _) = rig(ReviewFiles.orders)
        r.decideAll(); r.generate()
        r.vm.dismissResult(); r.settle()
        assertEquals(GenerationState.Idle, r.generation)
        assertEquals(0, r.review.pendingCount)
        assertTrue(r.holder.saved.value, "files were saved: the question stays")
    }

    // ---- limits and concurrency ----

    @Test
    fun `a folder with the version 9999 has no free version, and nothing is written`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders, versions = listOf("9999"))
        r.decideAll(); r.generate()
        assertEquals(GenerationState.Trouble(SaveProblem.NoFreeVersion, emptyList(), emptyList()), r.generation)
        assertEquals(emptyList(), folder.attempts)
    }

    @Test
    fun `two files where the second would pass 9999 write none`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts, versions = listOf("9998"))
        r.decideAll(); r.generate()
        assertEquals(SaveProblem.NoFreeVersion, r.trouble().problem)
        assertEquals(emptyList(), folder.attempts, "generate fails before any write, so no half result is saved")
    }

    @Test
    fun `a new event while the files are being saved does not cut the save`() = runTest {
        val (r, folder) = rig(ReviewFiles.twoVhosts)
        r.decideAll()
        val gate = CompletableDeferred<Unit>()
        folder.beforeWrite = { attempt -> if (attempt == 1) gate.await() }
        r.vm.generate(); r.settle()
        assertEquals(GenerationState.Saving, r.generation)
        assertFalse(r.review.canGenerate, "no second generate while saving")
        r.vm.open(r.path)       // a new event that starts work cancels the old job
        gate.complete(Unit)
        r.settle()
        assertEquals(listOf("0001", "0002"), folder.written, "both files were written although the job was cancelled: no half save")
    }

    @Test
    fun `the generate event during saving does not start a second save`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        r.decideAll()
        val gate = CompletableDeferred<Unit>()
        folder.beforeWrite = { gate.await() }
        r.vm.generate(); r.settle()
        r.vm.generate(); r.vm.generateAgain(); r.vm.retrySave(); r.settle()
        gate.complete(Unit); r.settle()
        assertEquals(listOf("0001"), folder.written)
        assertEquals(1, folder.attempts.size)
    }

    // ---- what is left out is shown ----

    @Test
    fun `the result lists a skipped queue as skipped by me and its binding as left out, and repeats the message-loss recommendation`() = runTest {
        val text = DefinitionsFiles.file(
            queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("modern", type = "quorum")),
            exchanges = listOf(DefinitionsFiles.exchange("events")),
            bindings = listOf(DefinitionsFiles.binding("events", "orders", "k")),
        )
        val (r, _) = rig(text)
        r.skip(q("orders")); r.generate()
        val report = r.done()
        assertTrue(LeftOutRow("orders", "skipped by you") in report.leftOut, "${report.leftOut}")
        assertTrue(report.leftOut.any { it.text.contains("The binding from \"events\" to \"orders\" is left out because the queue \"orders\" is skipped") }, "${report.leftOut}")
        assertTrue(report.notes.any { it.contains("new 4.x broker with this migration and a Shovel is the recommended path") }, "${report.notes}")
        assertTrue(report.notes.none { it.contains("is left out because") }, "binding notes are in leftOut, not in notes")
    }

    @Test
    fun `a name that cannot be written is listed as left out with its own reason`() = runTest {
        val text = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue(".."), DefinitionsFiles.queue("orders")))
        val (r, _) = rig(text)
        r.decideAll(); r.generate()
        assertTrue(r.done().leftOut.any { it.label == ".." && it.text == "its name cannot be written into a migration" }, "${r.done().leftOut}")
    }

    @Test
    fun `a single virtual host file adds the note that the virtual host must already exist`() = runTest {
        val file = DefinitionsFiles.file(version = "3.13.7", queues = listOf(DefinitionsFiles.queue("orders", vhost = null)), vhosts = null)
        val folders = FakeMigrationFolders(m)
        val r = ReviewRig(this, file, folders)
        // a per-vhost file asks for the virtual host first
        assertIs<MigrateFromThreeState.NeedsVirtualHost>(r.vm.state.value)
        r.vm.giveVirtualHost("prod"); r.settle()
        r.decideAll(); r.generate()
        assertTrue(r.done().notes.any { it.contains("the virtual host \"prod\" must already exist on the 4.x broker") }, "${r.done().notes}")
    }

    @Test
    fun `the Skipped group stays after generating`() = runTest {
        val (r, _) = rig(ReviewFiles.three)
        r.skip(q("orders")); r.accept(q("invoices")); r.accept(q("jobs"))
        r.generate()
        assertEquals(SKIPPED_GROUP_KEY, r.report.view.groups.last().key)
    }

    @Test
    fun `a decision changed after generating changes nothing in the saved result until generated again`() = runTest {
        val (r, folder) = rig(ReviewFiles.orders)
        r.decideAll(); r.generate()
        val written = folder.files.keys
        r.skip(q("orders"))
        assertEquals(written, folder.files.keys)
        assertEquals(1, folder.attempts.size)
    }
}
