package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.planner.EditFileFixtures.VERSION
import dev.caerbannog.core.planner.EditFileFixtures.raw
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-12 step 2, written first. RED by compilation until `PlannerMode`, the `mode` parameter of `PlannerViewModel` and the state below exist.
 *
 * Contract chosen where the brief is silent:
 *  - `sealed interface PlannerMode { data object NewPlan; data class EditFile(val version: String, val baseChecksum: String) }`
 *  - `PlannerViewModel(..., mode: PlannerMode = PlannerMode.NewPlan)`: last parameter. In `EditFile` the view model reads `version` from the repository of the
 *    folder in use (`folderPorts`), builds the plan with `PlanOpening.planOf` (description of the file, one step per action) and NEVER calls the draft store.
 *  - `PlannerState.mode`, `PlannerState.hasChanges` (the plan's steps or description differ from the file as opened), `PlannerState.saveLabel` ("Save as migration"
 *    or "Save 0005"), `PlannerState.title` ("Topology planner" or "Topology planner · 0005").
 *  - the existing events carry the file mode: `saveAsMigration()` is "Save 0005", `dismiss()` is "Discard changes" and the back arrow.
 *  - `PlannerQuestion.DiscardChanges(version)`: text "Discard the changes to 0005?", confirm label "Discard changes", cancel label "Keep editing".
 *  - after a save: `state.saved == SavedPlan("0005", "0005.json")`, the file holds `exportToJson(version, description, actions)`.
 *  - a file that changed or vanished since it was opened: `errorMessage` "0005.json changed on disk since it was opened", `errorDetail`
 *    "It was not overwritten. Open it again to edit the new content." (mockup 13); nothing is written.
 *  - a refused write (folder says no): `errorMessage` = `PlannerTexts.FILE_NOT_WRITTEN`, the plan keeps the change.
 */
class PlannerViewModelEditFileTest {
    private val original = raw(actions = EditFileFixtures.threeSteps.toTypedArray())
    private val rig = EditFileFixtures.open(original)
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    private fun fileText(version: String = VERSION): String = rig.folder.files.getValue(version).rawJson

    // ---- opening ----

    @Test
    fun `the plan is built from the file with one step per action in its order, and the file's description`() {
        assertEquals(EditFileFixtures.threeSteps, rig.state.plan.steps.map { it.action })
        assertEquals(listOf(1, 2, 3), rig.state.plan.steps.map { it.id })
        assertEquals("Orders", rig.state.description)
        assertEquals(PlannerMode.EditFile(VERSION, EditFileFixtures.checksum(original)), rig.state.mode)
        assertEquals("Topology planner · 0005", rig.state.title)
        assertEquals("Save 0005", rig.state.saveLabel)
        assertTrue(rig.state.isLoaded)
    }

    @Test
    fun `a new plan keeps its mode, title and label`() {
        val fresh = PlannerRig()
        try {
            assertEquals(PlannerMode.NewPlan, fresh.state.mode)
            assertEquals("Topology planner", fresh.state.title)
            assertEquals("Save as migration", fresh.state.saveLabel)
            assertFalse(fresh.state.hasChanges)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `opening reads the file and never the draft`() {
        assertEquals(0, rig.store.loads, "the draft store was read")
        assertEquals(emptyList(), rig.store.savedTexts)
        assertEquals(0, rig.store.clears)
        assertFalse(rig.store.hasDraft)
    }

    @Test
    fun `opening is not a change and nothing is written`() {
        assertFalse(rig.state.hasChanges)
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(emptyList(), rig.folder.attempts)
        assertEquals(original, fileText())
    }

    // ---- Save is enabled only after a change ----

    @Test
    fun `Save is disabled until the plan changes, and says why`() {
        val gate = assertIs<SaveGate.Disabled>(rig.state.saveGate)
        assertTrue(gate.reason.isNotBlank())
        vm.saveAsMigration()
        assertEquals(emptyList(), rig.folder.updates, "a save without a change wrote the file")
        assertNull(rig.state.saved)
        assertEquals(original, fileText())
    }

    @Test
    fun `each kind of change enables Save`() {
        val changes: List<(PlannerRig) -> Unit> = listOf(
            { r -> r.queue("invoices") },
            { r -> r.vm.removeStep(r.state.plan.steps.first().id) },
            { r -> r.vm.moveStepDown(r.state.plan.steps.first().id) },
            { r -> r.vm.setDescription("Orders and invoices") },
        )
        changes.forEachIndexed { i, change ->
            val r = EditFileFixtures.open(original)
            try {
                change(r)
                assertTrue(r.state.hasChanges, "change $i")
                assertEquals(SaveGate.Enabled, r.state.saveGate, "change $i")
            } finally {
                r.close()
            }
        }
    }

    // ---- Save writes the same version, as the app's layout ----

    @Test
    fun `Save rewrites the same version with the actions of the steps and writes no other file`() {
        rig.queue("invoices")
        vm.saveAsMigration()
        val expected = MigrationParser.exportToJson(VERSION, "Orders", EditFileFixtures.threeSteps + MigrationAction.DeclareQueue("invoices", "quorum"))
        assertEquals(listOf(VERSION to expected), rig.folder.updates)
        assertEquals(expected, fileText())
        assertEquals(listOf(VERSION), rig.folder.updated)
        assertEquals(emptyList(), rig.folder.written, "another file was written")
        assertEquals(emptyList(), rig.folder.attempts, "the import/export path was used")
        assertEquals(setOf("0004", VERSION), rig.folder.files.keys)
        assertEquals(SavedPlan(VERSION, "0005.json"), rig.state.saved)
        assertNull(rig.state.errorMessage)
    }

    @Test
    fun `a saved file is still pending, the app wrote only the file and nothing else`() {
        rig.queue("invoices")
        vm.saveAsMigration()
        // the file is a valid migration with the same version, and the draft store was never touched
        assertEquals(VERSION, (MigrationParser.parse(fileText()) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration.version)
        assertEquals(0, rig.store.loads)
        assertFalse(rig.store.hasDraft)
    }

    @Test
    fun `the first save rewrites a hand-written file in the app's layout`() {
        val handWritten = """{"description":"Orders","version":"0005","up":[{"name":"orders","action":"declareQueue"}]}"""
        val r = EditFileFixtures.open(handWritten)
        try {
            r.queue("invoices")
            r.vm.saveAsMigration()
            val expected = MigrationParser.exportToJson(
                VERSION, "Orders", listOf(MigrationAction.DeclareQueue("orders", "quorum"), MigrationAction.DeclareQueue("invoices", "quorum")),
            )
            assertEquals(expected, r.folder.files.getValue(VERSION).rawJson)
        } finally {
            r.close()
        }
    }

    // ---- the checksum guard ----

    @Test
    fun `a file that changed on disk is not overwritten and says so`() {
        rig.queue("invoices")
        val theirs = raw(description = "Theirs", actions = arrayOf(MigrationAction.DeclareQueue("theirs")))
        rig.folder.changeOnDisk(VERSION, theirs)
        vm.saveAsMigration()
        assertEquals("0005.json changed on disk since it was opened", rig.state.errorMessage)
        assertEquals("It was not overwritten. Open it again to edit the new content.", rig.state.errorDetail)
        assertEquals(emptyList(), rig.folder.updates, "the folder was asked to write")
        assertEquals(theirs, fileText())
        assertNull(rig.state.saved)
        assertEquals(4, rig.state.plan.steps.size, "my change was thrown away")
        assertFalse(rig.state.dismissed)
    }

    @Test
    fun `a file that is gone is not created again`() {
        rig.queue("invoices")
        rig.folder.deleteOnDisk(VERSION)
        vm.saveAsMigration()
        assertEquals("0005.json changed on disk since it was opened", rig.state.errorMessage)
        assertEquals(emptyList(), rig.folder.updates)
        assertFalse(VERSION in rig.folder.files)
    }

    @Test
    fun `asking again after a refusal is refused again, the base is not replaced`() {
        rig.queue("invoices")
        val theirs = raw(actions = arrayOf(MigrationAction.DeclareQueue("theirs")))
        rig.folder.changeOnDisk(VERSION, theirs)
        vm.saveAsMigration()
        vm.saveAsMigration()
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(theirs, fileText())
    }

    @Test
    fun `a file that changed only in layout counts as unchanged by the checksum rule`() {
        // computeChecksum normalises line endings and surrounding whitespace: the same file with a final newline is the same file
        rig.queue("invoices")
        rig.folder.changeOnDisk(VERSION, original + "\n")
        vm.saveAsMigration()
        assertEquals(listOf(VERSION), rig.folder.updated)
        assertNull(rig.state.errorMessage)
    }

    @Test
    fun `a refused write keeps the change and says the file could not be written`() {
        rig.queue("invoices")
        rig.folder.failVersion(VERSION)
        vm.saveAsMigration()
        assertEquals(PlannerTexts.FILE_NOT_WRITTEN, rig.state.errorMessage)
        assertNull(rig.state.saved)
        assertEquals(4, rig.state.plan.steps.size)
        assertEquals(original, fileText())
        assertFalse(rig.state.dismissed)
    }

    @Test
    fun `a read-only folder disables Save with its reason and writes nothing`() {
        rig.queue("invoices")
        rig.folder.readOnly = true
        vm.saveAsMigration()
        assertIs<SaveGate.Disabled>(rig.state.saveGate)
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(original, fileText())
    }

    // ---- leaving ----

    @Test
    fun `leaving without a change writes nothing, asks nothing and leaves the file's bytes alone`() {
        val asked = mutableListOf<PlannerQuestion>()
        vm.dismiss()
        rig.state.question?.let { asked += it }
        assertEquals(emptyList(), asked)
        assertTrue(rig.state.dismissed)
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(emptyList(), rig.folder.attempts)
        assertEquals(original, fileText())
        assertEquals(0, rig.store.clears)
        assertFalse(rig.store.hasDraft)
    }

    @Test
    fun `leaving with a change asks the question and does nothing yet`() {
        vm.removeStep(1)
        vm.dismiss()
        assertEquals(PlannerQuestion.DiscardChanges(VERSION), rig.state.question)
        assertEquals("Discard the changes to 0005?", rig.state.question!!.text)
        assertEquals("Discard changes", rig.state.question!!.confirmLabel)
        assertEquals("Keep editing", rig.state.question!!.cancelLabel)
        assertFalse(rig.state.dismissed)
        assertEquals(2, rig.state.plan.steps.size)
    }

    @Test
    fun `Keep editing leaves the planner open with the change`() {
        vm.removeStep(1)
        vm.dismiss()
        vm.cancelQuestion()
        assertNull(rig.state.question)
        assertFalse(rig.state.dismissed)
        assertEquals(2, rig.state.plan.steps.size)
        assertTrue(rig.state.hasChanges)
    }

    @Test
    fun `confirming discards the change, closes the planner and leaves the file and the draft alone`() {
        vm.removeStep(1)
        vm.dismiss()
        vm.confirm()
        assertTrue(rig.state.dismissed)
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(original, fileText())
        assertEquals(0, rig.store.clears, "the discard cleared the draft of a new plan")
    }

    // ---- the draft of a new plan is left alone ----

    @Test
    fun `an existing draft is not read, written, cleared or moved while a file is edited and saved`() {
        val store = dev.caerbannog.core.planner.fakes.FakePlanDraftStore()
        store.putDraft("My draft", listOf(MigrationAction.DeclareQueue("a"), MigrationAction.DeclareQueue("b"), MigrationAction.DeclareQueue("c")))
        val before = store.rawText
        val r = PlannerRig(store = store, rawFiles = listOf(original), mode = PlannerMode.EditFile(VERSION, EditFileFixtures.checksum(original)))
        try {
            r.queue("invoices")
            r.vm.setDescription("changed")
            r.vm.saveAsMigration()
            r.vm.dismiss()
            assertEquals(before, store.rawText)
            assertEquals(0, store.loads)
            assertEquals(emptyList(), store.savedTexts)
            assertEquals(0, store.clears)
            assertEquals(0, store.moveAsides)
            assertEquals(PlannerEntry.ContinuePlan(3, "My draft"), PlannerEntry.of(runBlocking { store.load() }))
        } finally {
            r.close()
        }
    }

    @Test
    fun `an unreadable draft does not lock the planner while a file is edited`() {
        val store = dev.caerbannog.core.planner.fakes.FakePlanDraftStore()
        store.putUnreadable()
        val r = PlannerRig(store = store, rawFiles = listOf(original), mode = PlannerMode.EditFile(VERSION, EditFileFixtures.checksum(original)))
        try {
            assertNull(r.state.draftUnreadable)
            assertFalse(r.state.isLocked)
            r.queue("invoices")
            assertEquals(4, r.state.plan.steps.size)
        } finally {
            r.close()
        }
    }

    // ---- read-only steps ----

    @Test
    fun `steps the wizard cannot build are steps that can be moved and removed but not edited, and are kept as written on save`() {
        val r = EditFileFixtures.open(raw("0006", "d", *EditFileFixtures.readOnlyThree.toTypedArray()), version = "0006")
        try {
            assertEquals(3, r.state.plan.steps.size)
            assertEquals(listOf(false, false, false), r.state.plan.steps.map { it.isEditable })
            r.vm.editStep(r.state.plan.steps.first().id)
            assertEquals(WizardState.Closed, r.state.wizard, "a read-only step opened a form")
            r.vm.moveStepDown(1)
            r.vm.moveStepUp(1)
            r.queue("invoices")
            r.vm.saveAsMigration()
            val saved = (MigrationParser.parse(r.folder.files.getValue("0006").rawJson) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration.up
            assertEquals(EditFileFixtures.readOnlyThree + MigrationAction.DeclareQueue("invoices", "quorum"), saved)
        } finally {
            r.close()
        }
    }

    @Test
    fun `removing a read-only step is a change`() {
        val r = EditFileFixtures.open(raw("0006", "d", *EditFileFixtures.readOnlyThree.toTypedArray()), version = "0006")
        try {
            r.vm.removeStep(2)
            assertTrue(r.state.hasChanges)
            assertEquals(SaveGate.Enabled, r.state.saveGate)
        } finally {
            r.close()
        }
    }

    // ---- gap 10 through a file ----

    @Test
    fun `a hand-written file with two policies of one name opens with the problem on the second and Save stays disabled`() {
        val twice = raw("0008", "d", EditFileFixtures.policy("orders-dlx", 1000), EditFileFixtures.policy("orders-dlx", 2000))
        val r = EditFileFixtures.open(twice, version = "0008")
        try {
            val second = r.state.plan.steps[1].id
            assertEquals(
                listOf("The plan already declares the policy orders-dlx with other settings"),
                r.state.stepProblems(second).map { it.message },
            )
            r.queue("invoices") // a change: only the problem keeps Save disabled now
            assertEquals(SaveGate.Disabled(PlannerTexts.saveBlocked(1)), r.state.saveGate)
            r.vm.saveAsMigration()
            assertEquals(emptyList(), r.folder.updates)
        } finally {
            r.close()
        }
    }

    // ---- the file is not there ----

    @Test
    fun `opening a version the folder does not have gives an empty plan that cannot be saved`() {
        val r = PlannerRig(mode = PlannerMode.EditFile("0099", "x"))
        try {
            assertEquals(emptyList(), r.state.plan.steps)
            r.queue("a")
            r.vm.saveAsMigration()
            assertEquals(emptyList(), r.folder.updates, "a save created or wrote a file that was never there")
            assertNotNull(r.state.errorMessage)
        } finally {
            r.close()
        }
    }

    // ---- step 2.5, ruling 8: a file with no step is not a migration ----

    @Test
    fun `with every step removed Save is disabled with Add a step first and nothing is written`() {
        rig.state.plan.steps.map { it.id }.forEach { vm.removeStep(it) }
        assertEquals(emptyList(), rig.state.plan.steps)
        assertTrue(rig.state.hasChanges)
        assertEquals(SaveGate.Disabled(PlannerTexts.ADD_A_STEP_FIRST), rig.state.saveGate)
        vm.saveAsMigration()
        assertEquals(emptyList(), rig.folder.updates)
        assertEquals(original, fileText())
        assertNull(rig.state.saved)
    }
}
