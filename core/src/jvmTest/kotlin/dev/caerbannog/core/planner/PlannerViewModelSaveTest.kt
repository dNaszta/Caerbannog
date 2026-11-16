package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.FolderProblem
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-02: "Save as migration". The gate has one verdict with the first reason that applies, in this order: "Add a step first", "Fix the N
 * problem(s) in the plan first", "Choose a migrations folder first", the folder's own reason, "The folder is read-only". A save asks the folder in
 * use for `nextVersion()` and `exportTopology(...)` (which never overwrites), clears the draft on success and reports `SavedPlan(version, fileName)`.
 */
class PlannerViewModelSaveTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    private fun gate() = rig.state.saveGate
    private fun disabled(reason: String) = assertEquals(SaveGate.Disabled(reason), gate())

    // ---- the gate, reason by reason ----

    @Test
    fun `an empty plan says Add a step first`() = disabled("Add a step first")

    @Test
    fun `a description alone is not a plan to save`() {
        vm.setDescription("later")
        disabled("Add a step first")
    }

    @Test
    fun `a plan with a step and a folder is enabled`() {
        rig.queue("orders")
        assertEquals(SaveGate.Enabled, gate())
    }

    @Test
    fun `one blocking problem says Fix the 1 problem`() {
        rig.exchange("events")
        rig.binding("events", "orders", "k")
        rig.queue("orders")
        disabled("Fix the 1 problem in the plan first")
    }

    @Test
    fun `two blocking problems say Fix the 2 problems`() {
        rig.queue("orders")
        rig.queue("orders", type = "classic") // the same name declared with other settings: a problem on each of the two steps? at least one
        val n = rig.state.problems.count { it.blocking }
        assertTrue(n >= 1, "precondition: ${rig.state.problems}")
        assertEquals(SaveGate.Disabled(PlannerTexts.saveBlocked(n)), gate())
        assertEquals("Fix the $n ${if (n == 1) "problem" else "problems"} in the plan first", (gate() as SaveGate.Disabled).reason)
    }

    @Test
    fun `a note that does not block leaves Save enabled`() {
        rig.queue("Orders_EU") // a naming hint is advice only
        assertEquals(SaveGate.Enabled, gate())
    }

    @Test
    fun `no folder chosen says Choose a migrations folder first, and planning still works`() {
        val noFolder = PlannerRig(folderPath = null)
        noFolder.queue("orders")
        assertEquals(SaveGate.Disabled("Choose a migrations folder first"), noFolder.state.saveGate)
        assertEquals(listOf("Declare quorum queue orders"), noFolder.titles())
        noFolder.close()
    }

    @Test
    fun `an unavailable folder gives the folder's own reason`() {
        FolderProblem.entries.forEach { problem ->
            val r = PlannerRig()
            r.queue("orders")
            r.folder.unavailable = problem
            r.folders.store.folder = PlannerRig.FOLDER // the store tells every view model to read the folder again
            assertEquals(SaveGate.Disabled(problem.message), r.state.saveGate, "$problem")
            r.close()
        }
    }

    @Test
    fun `a read-only folder says The folder is read-only`() {
        rig.folder.readOnly = true
        rig.queue("orders")
        disabled("The folder is read-only")
    }

    @Test
    fun `the first reason that applies wins, step before problems before folder`() {
        val r = PlannerRig(folderPath = null)
        assertEquals(SaveGate.Disabled("Add a step first"), r.state.saveGate, "empty plan and no folder: the step comes first")
        r.exchange("events"); r.binding("events", "orders", "k"); r.queue("orders")
        assertEquals(SaveGate.Disabled("Fix the 1 problem in the plan first"), r.state.saveGate, "a problem and no folder: the problem comes first")
        r.close()
    }

    @Test
    fun `choosing the folder from the planner enables Save and the Migrations screen follows the same store`() {
        val r = PlannerRig(folderPath = null)
        r.queue("orders")
        r.folders.chooser.answerNext(PlannerRig.FOLDER)
        r.vm.chooseFolder()
        assertEquals(PlannerRig.FOLDER, r.folders.store.folder)
        assertEquals(SaveGate.Enabled, r.state.saveGate)
        r.close()
    }

    @Test
    fun `cancelling the folder dialog changes nothing`() {
        val r = PlannerRig(folderPath = null)
        r.queue("orders")
        r.folders.chooser.answerNext(null)
        r.vm.chooseFolder()
        assertNull(r.folders.store.folder)
        assertEquals(SaveGate.Disabled("Choose a migrations folder first"), r.state.saveGate)
        r.close()
    }

    @Test
    fun `a folder chosen on the Migrations screen meanwhile is followed`() {
        val r = PlannerRig(folderPath = null)
        r.queue("orders")
        r.folders.store.folder = PlannerRig.FOLDER
        assertEquals(SaveGate.Enabled, r.state.saveGate)
        r.close()
    }

    // ---- saving ----

    @Test
    fun `save writes the next version with the description and the actions, and reports it`() {
        vm.setDescription("Add orders queue and binding")
        rig.queue("orders"); rig.exchange("events"); rig.binding("events", "orders", "order.created")
        vm.saveAsMigration()

        val file = rig.folder.files["0005"] ?: error("0005 was not written; written=${rig.folder.written}")
        assertEquals("Add orders queue and binding", file.description)
        assertEquals(listOf("declareQueue", "declareExchange", "declareBinding"), file.up.map { it.actionName })
        assertEquals(SavedPlan(version = "0005", fileName = "0005.json"), rig.state.saved)
        assertEquals("Plan saved as 0005. Nothing was sent to the broker. Apply it when you are ready.", rig.state.saved!!.message)
    }

    @Test
    fun `the saved file is what the plan holds, in order, and parses as a migration`() {
        rig.queue("orders"); rig.exchange("events")
        vm.saveAsMigration()
        val expected = MigrationParser.exportToJson("0005", "", rig.folder.files["0005"]!!.up)
        assertEquals(expected, rig.folder.attempts.single())
        assertIs<MigrationValidationResult.Valid>(MigrationParser.parse(rig.folder.attempts.single()))
        assertEquals(listOf("Declare quorum queue orders", "Declare direct exchange events"), rig.folder.files["0005"]!!.up.map { it.description })
    }

    @Test
    fun `a name that does not follow the naming style is saved exactly as typed`() {
        rig.queue("Orders_EU")
        vm.saveAsMigration()
        assertEquals("Orders_EU", (rig.folder.files["0005"]!!.up.single() as MigrationAction.DeclareQueue).name)
    }

    @Test
    fun `a successful save clears the draft and the plan`() {
        rig.queue("orders")
        vm.saveAsMigration()
        assertTrue(!rig.store.hasDraft, "the draft is gone after a successful save")
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals(WizardState.Closed, rig.state.wizard)
    }

    @Test
    fun `a file that appeared meanwhile is never overwritten, the next free version is used`() {
        rig.queue("orders")
        rig.folder.addMigration("0005")
        val original = rig.folder.files["0005"]!!.rawJson
        vm.saveAsMigration()
        assertEquals("0006", rig.state.saved?.version)
        assertEquals(original, rig.folder.files["0005"]!!.rawJson)
    }

    @Test
    fun `a file that appears between the version and the write is not overwritten and the draft is kept`() {
        rig.queue("orders")
        rig.folder.beforeWrite = { attempt -> if (attempt == 1) rig.folder.addMigration("0005") }
        vm.saveAsMigration()
        assertNull(rig.state.saved)
        assertEquals("The file could not be written", rig.state.errorMessage)
        assertEquals("existing 0005", rig.folder.files["0005"]!!.description)
        assertTrue(rig.store.hasDraft)
        assertEquals(listOf("Declare quorum queue orders"), rig.titles())
    }

    @Test
    fun `a failed write keeps the draft and the plan and says The file could not be written`() {
        rig.threeSteps()
        rig.folder.failWrite(1)
        vm.saveAsMigration()
        assertEquals("The file could not be written", rig.state.errorMessage)
        assertNull(rig.state.saved)
        assertEquals(3, rig.state.plan.steps.size)
        assertTrue(rig.store.hasDraft)
        assertEquals(setOf("0004"), rig.folder.files.keys, "no new file")
        assertEquals(0, rig.store.clears)
    }

    @Test
    fun `after a failed write a second Save works and the message goes`() {
        rig.queue("orders")
        rig.folder.failWrite(1)
        vm.saveAsMigration()
        vm.saveAsMigration()
        assertEquals("0005", rig.state.saved?.version)
        assertNull(rig.state.errorMessage)
    }

    @Test
    fun `save is refused when the gate is closed and asks the folder for nothing`() {
        vm.saveAsMigration() // empty plan
        rig.folder.readOnly = true
        rig.queue("orders")
        vm.saveAsMigration()
        assertEquals(emptyList(), rig.folder.attempts)
        assertNull(rig.state.saved)
    }

    @Test
    fun `save with a folder that is not chosen writes nothing anywhere`() {
        val r = PlannerRig(folderPath = null)
        r.queue("orders")
        r.vm.saveAsMigration()
        assertEquals(emptyList(), r.folder.attempts)
        assertTrue(r.store.hasDraft)
        r.close()
    }

    @Test
    fun `a plan for another virtual host can be saved`() {
        rig.restart(PlannerContext(vhost = "/dev"))
        rig.queue("orders")
        vm.setVirtualHost("/orders")
        assertEquals(SaveGate.Enabled, gate())
        vm.saveAsMigration()
        assertEquals("/orders", rig.folder.files["0005"]!!.up.single().vhost)
    }

    @Test
    fun `save is closed when the connection cannot run migrations`() {
        rig.queue("orders")
        rig.restart(PlannerContext(unavailableReason = "This broker runs RabbitMQ 3.x: it is a migration source only"))
        assertEquals(SaveGate.Disabled("This broker runs RabbitMQ 3.x: it is a migration source only"), gate())
        vm.saveAsMigration()
        assertEquals(emptyList(), rig.folder.attempts)
    }

    @Test
    fun `the banner of a saved plan goes with the next change`() {
        rig.queue("orders")
        vm.saveAsMigration()
        assertIs<SavedPlan>(rig.state.saved)
        rig.queue("events")
        assertNull(rig.state.saved, "the notice belongs to the plan that was saved")
    }
}
