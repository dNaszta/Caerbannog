package dev.caerbannog.ui.migrate3x

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.caerbannog.core.migrate3x.GenerationReport
import dev.caerbannog.core.migrate3x.GenerationState
import dev.caerbannog.core.migrate3x.How
import dev.caerbannog.core.migrate3x.LeftOutRow
import dev.caerbannog.core.migrate3x.MigrateFromThreeTexts
import dev.caerbannog.core.migrate3x.ReviewState
import dev.caerbannog.core.migrate3x.SavedFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // ---- M8-12b round 1: the panel is tall enough for an access result, and says who may apply the access file ----

    private val defaultVhostNote = "The default virtual host / already exists on 4.x and is not changed"
    private val adminSentence = "The access migration needs a user with the administrator tag and the level Full control; apply it before the topology files."

    /** The data of the screenshot: the line, three users not migrated, two permissions left out and the default virtual host note. */
    private fun screenshotReport() = report(How.Saved).copy(
        usersNotMigrated = listOf(
            LeftOutRow("guest", "not created; it has a publicly known password"),
            LeftOutRow("old", "set a new password on the broker; its password hash uses rabbit_password_hashing_bcrypt, which 4.x does not accept"),
            LeftOutRow("ldap-user", "no password hash in the export; set a password on the broker if it needs one"),
        ),
        permissionsLeftOut = listOf(LeftOutRow("guest on /", "the user is not migrated"), LeftOutRow("old on orders", "the user is not migrated")),
        notes = listOf(defaultVhostNote),
    )

    @Test
    fun `an access result shows the permissions left out and the default virtual host note, displayed and not only in the tree`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(screenshotReport())
        onNodeWithText("Permissions left out (2)").assertIsDisplayed()
        onNodeWithText("guest on /: the user is not migrated").assertIsDisplayed()
        onNodeWithText(defaultVhostNote).assertIsDisplayed()
    }

    private fun manyRows(prefix: String) = (1..40).map { LeftOutRow("$prefix$it", "skipped by you") }

    @Test
    fun `a result without access keeps the old panel height`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved, withAccess = false).copy(leftOut = manyRows("q")))
        val height = onNodeWithTag("migrate3x-result").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(height <= 400.dp, "a result without access data grew to $height (the old maximum is 360 dp of content)")
    }

    @Test
    fun `a result with an access section may be taller, up to 560 dp of content`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved).copy(usersNotMigrated = manyRows("u")))
        val height = onNodeWithTag("migrate3x-result").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(height >= 500.dp && height <= 620.dp, "an access result is $height high, expected the taller panel (560 dp of content)")
    }

    @Test
    fun `the access sentence is drawn under the access line when an access file exists, and not otherwise`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved))
        assertTextHas("migrate3x-access-needs-admin", adminSentence)
    }

    @Test
    fun `no access sentence for a result without an access file`() = runDesktopComposeUiTest(1280, 1000) {
        showResult(report(How.Saved, withAccess = false))
        assertNotDrawn("migrate3x-access-needs-admin")
    }
}
