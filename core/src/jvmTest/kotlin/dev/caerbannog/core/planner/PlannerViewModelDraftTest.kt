package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-02: the draft (spec A3, owner decision O5). One draft per machine, kept after every change that leaves a description or a step, cleared
 * when the plan is empty and has no description, read back by a new view model on the same store. Its text is
 * `MigrationParser.exportToJson("draft", description, actions)`: no new format. An empty `up` is a valid migration, so a description-only
 * draft round-trips; the virtual host of a draft without steps is not kept and reads as the connection's.
 */
class PlannerViewModelDraftTest {
    private val rig = PlannerRig()
    private val vm get() = rig.vm

    @AfterTest
    fun close() = rig.close()

    private fun draftText() = rig.store.rawText

    @Test
    fun `a planner with no draft starts empty and writes nothing`() {
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals(0, rig.store.savedTexts.size)
        assertEquals(0, rig.store.clears, "an empty plan with no draft has nothing to clear")
    }

    @Test
    fun `the draft is stored after each step is added`() {
        rig.queue("orders")
        assertEquals(1, rig.store.savedTexts.size)
        rig.exchange("events")
        assertEquals(2, rig.store.savedTexts.size)
        val parsed = MigrationParser.parse(draftText()!!)
        assertEquals(listOf("Declare quorum queue orders", "Declare direct exchange events"), (parsed as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration.up.map { it.description })
    }

    @Test
    fun `the text is the migration format with the version draft, no new format`() {
        vm.setDescription("Add orders")
        rig.queue("orders")
        val expected = MigrationParser.exportToJson("draft", "Add orders", listOf(MigrationAction.DeclareQueue("orders", "quorum", vhost = "/")))
        assertEquals(expected, draftText())
    }

    @Test
    fun `every change that leaves a step is stored (edit, move, remove, vhost and description)`() {
        rig.queue("a"); rig.queue("b")
        val (a, b) = rig.ids()
        val steps = listOf<() -> Unit>(
            { vm.editStep(a); vm.updateForm(QueueForm("a2")); vm.next(); vm.addToPlan() },
            { vm.moveStepDown(a) },
            { vm.setDescription("d") },
            { vm.setVirtualHost("/orders") },
            { vm.removeStep(b) },
        )
        steps.forEachIndexed { i, change ->
            val before = rig.store.savedTexts.size
            change()
            assertTrue(rig.store.savedTexts.size > before, "change $i did not store the draft")
            assertEquals(rig.state.plan.steps.map { it.action }, parsedActions(), "change $i: the stored draft differs from the plan")
        }
    }

    private fun parsedActions(): List<MigrationAction> =
        (MigrationParser.parse(draftText()!!) as dev.caerbannog.core.migrations.MigrationValidationResult.Valid).migration.up

    @Test
    fun `changing the naming style does not touch the draft`() {
        rig.queue("a")
        val saves = rig.store.savedTexts.size
        vm.setNamingStyle(NamingStyle.CAMEL)
        assertEquals(saves, rig.store.savedTexts.size)
    }

    @Test
    fun `removing the last step clears the draft when there is no description`() {
        rig.queue("a")
        vm.removeStep(rig.ids().single())
        assertNull(draftText())
        assertEquals(1, rig.store.clears)
    }

    @Test
    fun `removing the last step keeps a draft that has a description`() {
        vm.setDescription("Add orders")
        rig.queue("a")
        vm.removeStep(rig.ids().single())
        assertFalse(draftText() == null, "a description is worth keeping")
        assertEquals(emptyList(), parsedActions())
    }

    @Test
    fun `a description alone is a draft, and an emptied description with no step clears it`() {
        vm.setDescription("later")
        assertFalse(draftText() == null)
        vm.setDescription("")
        assertNull(draftText())
    }

    @Test
    fun `the draft survives a new view model on the same store`() {
        vm.setDescription("Add orders")
        rig.queue("orders")
        rig.restart()
        assertEquals("Add orders", rig.state.description)
        assertEquals(listOf("Declare quorum queue orders"), rig.titles())
        assertEquals(listOf(1), rig.ids())
        assertNull(rig.state.draftUnreadable)
    }

    @Test
    fun `a description-only draft round trips and takes the connection's virtual host`() {
        vm.setVirtualHost("/orders")
        vm.setDescription("later")
        rig.restart(PlannerContext(vhost = "/dev"))
        assertEquals("later", rig.state.description)
        assertEquals(emptyList(), rig.state.plan.steps)
        assertEquals("/dev", rig.state.plan.vhost, "the vhost of a draft without steps is not kept")
    }

    @Test
    fun `a draft with steps keeps its own virtual host, also when the connection's differs`() {
        rig.restart(PlannerContext(vhost = "/orders"))
        rig.queue("orders")
        rig.restart(PlannerContext(vhost = "/dev"))
        assertEquals("/orders", rig.state.plan.vhost)
        assertIs<PlannerBanner.VhostMismatch>(rig.state.banner)
    }

    @Test
    fun `a new plan starts on the connection's virtual host`() {
        rig.restart(PlannerContext(vhost = "/dev"))
        assertEquals("/dev", rig.state.plan.vhost)
    }

    @Test
    fun `a refused draft write is not a crash and the plan stays in memory`() {
        rig.store.refuseWrites = true
        rig.queue("orders")
        assertEquals(listOf("Declare quorum queue orders"), rig.titles())
        assertEquals(0, rig.store.savedTexts.size)
    }

    @Test
    fun `a refused draft write is said, once the write works the message goes`() {
        rig.store.refuseWrites = true
        rig.queue("orders")
        assertEquals("The draft could not be saved on this machine; the plan is only in memory", rig.state.errorMessage)
        rig.store.refuseWrites = false
        rig.queue("events")
        assertNull(rig.state.errorMessage)
    }

    @Test
    fun `the draft is the app's own data and the migrations folder is never asked to write it`() {
        rig.queue("orders")
        assertEquals(emptyList(), rig.folder.attempts, "the draft does not go through the migrations folder")
        assertEquals(emptyList(), rig.folder.written)
        assertFalse(rig.store.path.startsWith(PlannerRig.FOLDER))
    }

    @Test
    fun `the state says the plan is a draft that is not saved as a migration`() {
        rig.queue("orders")
        assertEquals("Draft, not saved as a migration", rig.state.draftLabel)
    }

    @Test
    fun `an unreadable draft is reported, kept byte for byte, and nothing is written over it`() {
        rig.store.putUnreadable("garbage")
        rig.restart()
        val unreadable = assertNotNullState()
        assertEquals(rig.store.path, unreadable.path)
        assertEquals("The file is not a valid plan", unreadable.reason)
        assertEquals("garbage", draftText())
        assertEquals(emptyList(), rig.state.plan.steps)
        vm.setDescription("typing")
        assertEquals("garbage", draftText(), "a description typed meanwhile must not replace the unreadable file")
        assertEquals(emptyList(), rig.store.savedTexts)
        assertEquals(0, rig.store.clears)
    }

    private fun assertNotNullState(): DraftUnreadable = rig.state.draftUnreadable ?: error("expected an unreadable draft in ${rig.state}")

    @Test
    fun `a draft that holds a password hash is unreadable, no save happens after the load, and the hash is not shown`() {
        val hash = "S3NTINEL-HASH-77ab"
        rig.store.putDraft("by hand", listOf(MigrationAction.DeclareUser("svc", dev.caerbannog.core.json.SecretValue.of(hash), "rabbit_password_hashing_sha256")))
        val original = rig.store.rawText
        rig.restart()
        assertEquals(FakePlanDraftStore.ACCESS_REASON, assertNotNullState().reason)
        assertEquals(emptyList(), rig.state.plan.steps, "the access action is not loaded as a step")
        vm.setDescription("typing"); vm.openWizard(); vm.dismiss(); vm.saveAsMigration()
        assertEquals(emptyList(), rig.store.savedTexts, "no save after such a load")
        assertEquals(0, rig.store.clears)
        assertEquals(original, rig.store.rawText)
        assertFalse(rig.state.toString().contains(hash), "the hash must not reach the state")
    }

    @Test
    fun `Try again reads the draft file again`() {
        rig.store.putUnreadable("garbage")
        rig.restart()
        rig.store.putDraft("fixed by hand", listOf(MigrationAction.DeclareQueue("orders")))
        vm.retryDraft()
        assertNull(rig.state.draftUnreadable)
        assertEquals("fixed by hand", rig.state.description)
        assertEquals(listOf("Declare quorum queue orders"), rig.titles())
    }

    @Test
    fun `a connection that cannot run migrations leaves the draft untouched and writes nothing`() {
        rig.store.putDraft("keep me", listOf(MigrationAction.DeclareQueue("orders")))
        val before = rig.store.rawText
        rig.restart(PlannerContext(unavailableReason = "This broker runs RabbitMQ 3.x: it is a migration source only"))
        vm.setDescription("x")
        vm.removeStep(1)
        vm.dismiss(); vm.confirm()
        vm.saveAsMigration()
        assertEquals(before, rig.store.rawText)
        assertEquals(emptyList(), rig.store.savedTexts)
        assertEquals(0, rig.store.clears)
        assertEquals(0, rig.store.moveAsides)
        assertEquals(emptyList(), rig.folder.attempts)
    }

    @Test
    fun `the plan is loaded when the view model is made, so the first state may be empty and the loaded one has the draft`() {
        rig.store.putDraft("d", listOf(MigrationAction.DeclareQueue("orders")))
        rig.restart()
        assertTrue(rig.state.isLoaded)
    }
}
