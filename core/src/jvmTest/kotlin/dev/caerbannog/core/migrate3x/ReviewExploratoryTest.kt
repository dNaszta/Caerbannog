package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.ReviewFiles.q
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M8-06b step 4 exploratory checks that found or could find a bug: a save racing a new analysis, un-skip then generate again, no delete, no gateway. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewExploratoryTest {
    @Test
    fun `a save that finishes after a new analysis was opened does not mark the new analysis as saved`() = runTest {
        val folders = dev.caerbannog.core.migrations.fakes.FakeMigrationFolders("/migrations")
        val folder = folders.folder("/migrations")
        val r = ReviewRig(this, ReviewFiles.twoVhosts, folders)
        r.decideAll()
        val gate = CompletableDeferred<Unit>()
        folder.beforeWrite = { attempt -> if (attempt == 1) gate.await() }
        r.vm.generate(); r.settle()
        r.vm.open(r.path); r.settle()          // a new analysis replaces the old one while the save is waiting
        gate.complete(Unit); r.settle()
        assertEquals(listOf("0001", "0002"), folder.written, "the save is not cut")
        assertFalse(r.holder.saved.value, "the files belong to the OLD analysis; the new one was never saved")
        assertEquals(GenerationState.Idle, r.generation, "the new analysis shows no result of the old one")
        assertEquals(2, r.review.pendingCount, "the new analysis starts with its items undecided")
    }

    @Test
    fun `un-skip after a result, then generate, opens the item and writes nothing, and only the next generate asks`() = runTest {
        val folders = dev.caerbannog.core.migrations.fakes.FakeMigrationFolders("/migrations")
        val r = ReviewRig(this, ReviewFiles.three, folders)
        r.skip(q("orders")); r.accept(q("invoices")); r.accept(q("jobs"))
        r.generate()
        assertIs<GenerationState.Done>(r.generation)
        val attempts = folders.folder("/migrations").attempts.size
        r.vm.unskip(q("orders")); r.settle()
        r.generate()
        assertEquals(q("orders"), r.sheet.item, "the undecided item opens")
        assertEquals(attempts, folders.folder("/migrations").attempts.size, "nothing is written while an item is undecided")
        r.accept(q("orders")); r.generate()
        assertEquals(GenerationState.ConfirmAgain, r.generation)
        r.vm.generateAgain(); r.settle()
        assertTrue(r.done().files.single().version > "0001", "new version")
        assertEquals(2, folders.folder("/migrations").written.size)
    }

    @Test
    fun `no generated file holds a delete action, whatever the decisions`() = runTest {
        val folders = dev.caerbannog.core.migrations.fakes.FakeMigrationFolders("/migrations")
        val r = ReviewRig(this, ReviewFiles.mixed, folders)
        r.decideAll(); r.generate()
        assertTrue(folders.folder("/migrations").attempts.isNotEmpty())
        assertTrue(folders.folder("/migrations").attempts.none { it.contains("\"delete") }, "a delete action was written")
    }

    @Test
    fun `source guard - the view model has no gateway, no management client, no shovel`() {
        val text = File("src/commonMain/kotlin/dev/caerbannog/core/migrate3x/MigrateFromThreeViewModel.kt").readText()
        listOf("Gateway", "Management", "Shovel", "HttpClient").forEach { assertFalse(text.contains(it), "the view model mentions $it") }
    }
}
