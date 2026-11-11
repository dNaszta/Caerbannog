package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileChooser
import dev.caerbannog.core.migrate3x.fakes.FakeDefinitionsFileLoader
import dev.caerbannog.core.migrate3x.fakes.FakeReportFileSaver
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertIs
import kotlin.test.fail

/** M8-06b: files for the review tests, through the real parser and analyzer. Every queue is a plain 3.x classic queue unless said. */
internal object ReviewFiles {
    fun classic(vararg names: String): String = DefinitionsFiles.file(queues = names.map { DefinitionsFiles.queue(it) })

    val orders: String = classic("orders")
    val three: String = classic("orders", "invoices", "jobs")

    /** "orders" proposes quorum (tick), "prio" keeps classic, "temp" has only a manual fix, plus one compatible queue. */
    val mixed: String = DefinitionsFiles.file(
        queues = listOf(
            DefinitionsFiles.queue("orders"),
            DefinitionsFiles.queue("prio", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
            DefinitionsFiles.queue("temp", durable = false),
            DefinitionsFiles.queue("modern", type = "quorum"),
        ),
    )

    /** Two source virtual hosts: "/" and "other", each with one classic queue. */
    val twoVhosts: String = DefinitionsFiles.file(
        queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("billing", vhost = "other")),
        // M8-12b: only "/" is declared. A declared virtual host other than "/" makes an access file (the first file, numbers shift); [twoDeclaredVhosts] is that case.
        vhosts = listOf("/"),
    )

    /** Two source virtual hosts, both declared: the generation writes an access file first, then one topology file per virtual host. */
    val twoDeclaredVhosts: String = DefinitionsFiles.file(
        queues = listOf(DefinitionsFiles.queue("orders"), DefinitionsFiles.queue("billing", vhost = "other")),
        vhosts = listOf("/", "other"),
    )

    fun q(name: String, vhost: String = "/") = ReviewItem(ItemKind.Queue, vhost, name)
}

/** One view model on virtual time, loaded with [text]; every helper runs the scheduler until idle. Plain calls of events, no logic. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReviewRig(
    val test: TestScope,
    text: String,
    val folders: FakeMigrationFolders = FakeMigrationFolders(),
    val label: String = "legacy.json",
    val holder: AnalysisHolder = AnalysisHolder(),
) {
    val path = "/data/$label"
    val loader = FakeDefinitionsFileLoader(mapOf(path to FileLoadResult.Loaded(text)))
    val chooser = FakeDefinitionsFileChooser()
    val saver = FakeReportFileSaver()
    var vm: MigrateFromThreeViewModel = newVm()

    fun newVm(): MigrateFromThreeViewModel {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        return MigrateFromThreeViewModel(loader, chooser, saver, holder, folders.ports, CoroutineScope(dispatcher), dispatcher).also { vm = it }
    }

    init {
        vm.open(path)
        settle()
    }

    fun settle() = test.advanceUntilIdle()

    val report: MigrateFromThreeState.Report
        get() = vm.state.value as? MigrateFromThreeState.Report ?: fail("expected the report but the state is ${vm.state.value}")
    val review: ReviewState get() = report.review
    val sheet: ItemSheet get() = review.sheet ?: fail("no sheet is open; review state: $review")
    val generation: GenerationState get() = review.generation
    val core: ConversionReview get() = holder.held.value!!.review

    fun open(item: ReviewItem) { vm.openItem(item); settle() }

    /** Opens [item], ticks every tick the sheet shows, accepts. */
    fun accept(item: ReviewItem) {
        open(item)
        sheet.ticks.forEach { vm.setTick(it.acknowledgement, true) }
        vm.accept(); settle()
    }

    fun skip(item: ReviewItem) { open(item); vm.skip(); settle() }

    fun replace(item: ReviewItem, json: String) {
        open(item); vm.startReplace(); settle(); vm.submitReplace(json); settle()
    }

    /** Decides every pending item the way a patient user does: accept when the sheet allows it, else skip. */
    fun decideAll() {
        for (item in core.pending.toList()) {
            open(item)
            sheet.ticks.forEach { vm.setTick(it.acknowledgement, true) }
            if (sheet.canAccept) vm.accept() else vm.skip()
            settle()
        }
        check(review.pendingCount == 0) { "decideAll left ${review.pendingCount} pending" }
    }

    fun generate() { vm.generate(); settle() }

    fun done(): GenerationReport = assertIs<GenerationState.Done>(generation, "generation was $generation").report
    fun trouble(): GenerationState.Trouble = assertIs(generation, "generation was $generation")

    /** The state of every group of the report view, by key. */
    fun groupKeys(): List<String> = report.view.groups.map { it.key }

    fun rowsOf(key: String): List<RowView> {
        val group = report.view.groups.firstOrNull { it.key == key } ?: fail("no group $key; groups: ${groupKeys()}")
        if (!group.open) { vm.toggleGroup(key); settle() }
        val rows = report.view.rows(key)
        return (0 until rows.size).map { rows.at(it) }
    }
}

/** A core review of a file text, for tests that need the core's own answer (not a literal). */
internal object ConversionReviewsRef {
    fun core(text: String): ConversionReview {
        val document = (DefinitionsParser.parse(text) as DefinitionsParseResult.Parsed).document
        return ConversionReview.start(document, CompatibilityAnalyzer.analyze(document))
    }
}
