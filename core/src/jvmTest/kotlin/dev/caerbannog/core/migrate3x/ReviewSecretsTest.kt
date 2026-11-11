package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.ReviewFiles.q
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M8-06b: nothing secret and nothing raw from a file in the review state, the sheet, the result, the saved files and every `toString`.
 * The file of the secret test has a user with a hash and a shovel parameter whose URI holds a password (M8-01 sentinels).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewSecretsTest {
    private val sentinels = listOf("SENTINEL-HASH", "SENTINEL-PASSWORD", "amqp://", "amqps://")

    private fun everything(r: ReviewRig): String {
        val state = r.vm.state.value as MigrateFromThreeState.Report
        val rows = state.view.groups.flatMap { g ->
            if (!g.open) r.vm.toggleGroup(g.key)
            r.settle()
            val view = (r.vm.state.value as MigrateFromThreeState.Report).view
            (0 until view.rows(g.key).size).map { view.rows(g.key).at(it).toString() }
        }
        val latest = r.vm.state.value as MigrateFromThreeState.Report
        return listOf(latest.review.toString(), latest.review.sheet.toString(), latest.review.generation.toString(), latest.toString(), rows.joinToString("\n"), r.core.toString()).joinToString("\n")
    }

    @Test
    fun `no sentinel reaches the review state, a sheet, the result, the saved files or a toString, in a whole run`() = runTest {
        val folders = FakeMigrationFolders("/migrations")
        val withItems = DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)
        val r = ReviewRig(this, withItems, folders)
        val all = StringBuilder(everything(r))
        for (item in r.core.items) { r.open(item); all.append(r.review.sheet.toString()); r.vm.startReplace(); r.settle(); all.append(r.review.sheet.toString()); r.vm.closeItem(); r.settle() }
        r.decideAll(); r.generate()
        all.append(everything(r))
        // M8-12b: the access file holds the real hash by design (the broker needs it): its own text is the one place a hash may be, so it is not scanned here;
        // the hash in every state, result and toString is what this test guards (and AccessGenerationFlowTest checks the access file separately)
        for (f in folders.folder("/migrations").attempts) if (!f.contains("\"declareUser\"")) all.append(f)
        val text = all.toString()
        assertTrue(r.review.generation is GenerationState.Done || r.review.generation is GenerationState.Idle, "the run reached the end: ${r.review.generation}")
        sentinels.forEach { assertFalse(text.contains(it), "$it reached the review state, the result or a saved file") }
    }

    @Test
    fun `a hostile name in a note, a finding, a left-out row and a path is escaped and nothing is interpreted`() = runTest {
        val hostile = "<b>x</b>\u0007\u202Eend"
        val text = DefinitionsFiles.file(
            queues = listOf(DefinitionsFiles.queue(hostile), DefinitionsFiles.queue("skipme")),
            exchanges = listOf(DefinitionsFiles.exchange("events")),
            bindings = listOf(DefinitionsFiles.binding("events", "skipme", "k")),
            policies = listOf(DefinitionsFiles.policy("pol\u0007\u202E", "^x", mapOf("ha-mode" to JsonPrimitive("all"), "max-length" to JsonPrimitive(5)))),
        )
        val folders = FakeMigrationFolders("/m\u0007x")
        val r = ReviewRig(this, text, folders)
        r.skip(q("skipme"))
        for (item in r.core.items.filter { it != q("skipme") }) { r.open(item); r.vm.startReplace(); r.settle(); r.vm.closeItem(); r.settle() }
        r.decideAll(); r.generate()
        val shown = everything(r)
        assertTrue(r.generation is GenerationState.Done, "the run reached the result: ${r.generation}")
        for (raw in listOf("\u0007", "\u202E")) assertFalse(shown.contains(raw), "a raw U+${raw[0].code.toString(16)} is in the state the screen draws")
        assertTrue(shown.contains("\\u0007"), "the control character is shown escaped")
        val report = r.done()
        assertTrue(report.files.all { '\u0007' !in it.path && it.path.contains("\\u0007") }, "the path of a saved file is escaped: ${report.files.map { it.path }}")
        assertTrue(report.leftOut.all { row -> row.label.none { it == '\u0007' || it == '\u202E' } && row.text.none { it == '\u0007' || it == '\u202E' } }, "${report.leftOut}")
        assertTrue(report.notes.all { n -> n.none { it == '\u0007' || it == '\u202E' } }, "${report.notes}")
        assertTrue(report.notes.any { it.contains("<b>x</b>") && it.contains("\\u0007") }, "markup stays text, the control character is escaped: ${report.notes}")
    }

    @Test
    fun `an unsaved file of a partial save has no path and the problem text holds no file content`() = runTest {
        val folders = FakeMigrationFolders("/migrations")
        folders.folder("/migrations").failWrite(2, "the file could not be written")
        val r = ReviewRig(this, ReviewFiles.twoVhosts, folders)
        r.decideAll(); r.generate()
        val text = r.generation.toString()
        assertFalse(text.contains("declareQueue"), "no file content in the trouble: $text")
        assertFalse(text.contains("rawJson"))
    }

    @Test
    fun `source guard - the review files never call expose and core holds no java import`() {
        val dir = File("src/commonMain/kotlin/dev/caerbannog/core/migrate3x")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(File(dir, "ReviewState.kt").isFile, "ReviewState.kt is where the brief puts the new types")
        val review = files.filter { it.name.startsWith("Review") || it.name.startsWith("ItemSheet") || it.name == "MigrateFromThreeViewModel.kt" || it.name == "ReportViewBuilder.kt" || it.name == "ReportView.kt" }
        assertTrue(review.none { it.readText().contains("expose(") }, "expose( in ${review.filter { it.readText().contains("expose(") }.map { it.name }}")
        val java = files.filter { Regex("""(?m)^\s*import\s+java\.""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue(java.isEmpty(), "java.* imported in core: $java")
    }
}
