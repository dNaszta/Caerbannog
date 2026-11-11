package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles as F
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-12b: Generate in the review writes the access file first, shows the line, the lists and the warning, and the export hands the access file to the saver as a secret. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessGenerateScreenFlowTest {
    private val text = F.accessFile(
        vhosts = listOf(F.vhost("/"), F.vhost("/orders")), users = listOf(F.user("svc", "SENTINEL-HASH-xyz"), F.user("guest")),
        permissions = listOf(F.permission("/orders", "svc"), F.permission("/", "guest")),
        queues = listOf(F.queue("jobs", type = "quorum", vhost = "/")),
    )

    @Test
    fun `saving into the folder writes the access file first with the real hash, and the result shows the line, the lists and the warning`() = runTest {
        val folders = FakeMigrationFolders("/m")
        val folder = folders.folder("/m")
        val r = ReviewRig(this, text, folders)
        r.decideAll(); r.generate()
        val report = r.done()
        assertEquals(listOf("access", "/"), report.files.map { it.vhost })
        assertEquals(listOf("0001", "0002"), report.files.map { it.version })
        assertTrue(folder.files.getValue("0001").toString().contains("SENTINEL-HASH-xyz"), "the file must hold the real hash")
        assertEquals("Access: 1 virtual host, 1 user, 1 permission, 0 topic permissions", report.accessLine)
        assertEquals(MigrateFromThreeTexts.SECRET_FILE_WARNING, report.secretWarning)
        assertEquals(listOf("guest"), report.usersNotMigrated.map { it.label })
        assertEquals(listOf("guest on /"), report.permissionsLeftOut.map { it.label })
        assertFalse(r.report.toString().contains("SENTINEL-HASH") || report.toString().contains("SENTINEL-HASH"), "a hash is in the state")
    }

    @Test
    fun `exporting hands the access file to the saver as a secret and the topology file as an ordinary file, with the warning on the result`() = runTest {
        val folders = FakeMigrationFolders("/m")
        folders.folder("/m").readOnly = true
        val r = ReviewRig(this, text, folders)
        r.decideAll(); r.generate()
        r.saver.outcome = SaveOutcome.Saved("/home/me/x.json")
        r.vm.exportFiles(); r.settle()
        assertEquals(listOf("0001.json", "0002.json"), r.saver.saves.map { it.suggestedName })
        assertEquals(listOf(true, false), r.saver.saves.map { it.secret }, "only the file that holds a hash is a secret")
        assertEquals(MigrateFromThreeTexts.SECRET_FILE_WARNING, r.done().secretWarning)
        assertEquals(How.Exported, r.done().how)
    }

    @Test
    fun `the JSON export of the report is not a secret file`() = runTest {
        val r = ReviewRig(this, text)
        r.vm.exportReportJson(); r.settle()
        assertEquals(listOf(false), r.saver.saves.map { it.secret })
    }
}
