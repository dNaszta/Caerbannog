package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.LeftOutRow
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SavedFile
import kotlin.test.Test
import kotlin.test.assertFalse

/** M8-12b: the result panel of a generation with access data: the line, the access card, the secret warning (Saved and Exported) and the two lists, with the tags of the brief. */
@OptIn(ExperimentalTestApi::class)
class MigrateFromThreeAccessResultUiTest {
    private val warning = "This file contains password hashes. Anyone who has it can try to guess the passwords offline. Keep it secret: do not commit it to git or put it in a shared or synced folder."

    private fun access(path: String) = SavedFile("0001", "access", "Access migrated from RabbitMQ 3.x legacy-eu.json", 9, path)
    private fun topology(path: String) = SavedFile("0002", "/", "Migrated from RabbitMQ 3.x legacy-eu.json vhost /", 214, path)

    private fun report(how: How, withAccess: Boolean = true) = GenerationReport(
        how = how, headline = if (how == How.Saved) "2 migrations saved" else "2 migrations exported",
        files = if (withAccess) listOf(access(if (how == How.Saved) "/m/0001.json" else ""), topology("")) else listOf(topology("")),
        leftOut = emptyList(), notes = emptyList(), vhostHint = null,
        accessLine = if (withAccess) "Access: 1 virtual host, 4 users, 6 permissions, 1 topic permission" else null,
        usersNotMigrated = if (withAccess) listOf(LeftOutRow("guest", "not created; it has a publicly known password"), LeftOutRow("old", "set a new password on the broker; its password hash uses rabbit_password_hashing_bcrypt, which 4.x does not accept")) else emptyList(),
        permissionsLeftOut = if (withAccess) listOf(LeftOutRow("guest on /", "the user is not migrated")) else emptyList(),
        secretWarning = if (withAccess) warning else null,
    )

    private fun androidx.compose.ui.test.DesktopComposeUiTest.showResult(report: GenerationReport) {
        val base = MigrateScreenFiles.report()
        val review = ReviewState(1, 0, 1, 0, 0, "Every item is decided", null, false, null, GenerationState.Done(report))
        show(base.copy(review = review))
    }

    @Test
    fun `a saved result shows the access line, the access card, the warning and the two lists`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved))
        assertDrawn("migrate3x-result")
        assertTextHas("migrate3x-result", "Access: 1 virtual host, 4 users, 6 permissions, 1 topic permission", "access, all virtual hosts", "readable by you only")
        assertTextHas("migrate3x-access-warning", warning)
        assertTextHas("migrate3x-result-users-not-migrated", "Users not migrated (2)", "guest", "not created; it has a publicly known password", "old", "which 4.x does not accept")
        assertTextHas("migrate3x-result-permissions-left-out", "Permissions left out (1)", "guest on /", "the user is not migrated")
    }

    @Test
    fun `an exported result shows the same warning`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Exported))
        assertTextHas("migrate3x-access-warning", warning)
        assertFalse(textOf("migrate3x-result").contains("readable by you only"), "an exported file's place is the user's choice: no promise about it")
    }

    @Test
    fun `a result without access data shows no access line, no warning and no lists`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved, withAccess = false))
        assertDrawn("migrate3x-result")
        assertNotDrawn("migrate3x-access-warning")
        assertNotDrawn("migrate3x-result-users-not-migrated")
        assertNotDrawn("migrate3x-result-permissions-left-out")
        assertFalse(textOf("migrate3x-result").contains("Access:"))
    }
}
