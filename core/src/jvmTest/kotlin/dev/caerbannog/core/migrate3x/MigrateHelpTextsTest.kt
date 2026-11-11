package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.help.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-14 (owner decision D1, 2026-11-11): the texts of the Migrate from 3.x screen, as approved in the table of the brief. A changed word is a changed test. Row numbers are the brief's. */
class MigrateHelpTextsTest {
    private val expectedTips = listOf(
        HelpText("m3x-3", "A definitions.json exported from the 3.x broker. Export the whole broker if you also want users and permissions; a one-virtual-host export has none."),
        HelpText("m3x-4", "Reads the connected 3.x broker with GET requests only. Its user needs the administrator tag to export definitions."),
        HelpText("m3x-6", "Blocker: will not work on 4.x as it is. Warning: works, but behaves differently and you decide. Info: nothing to do, or a manual step. The numbers count the whole report, whatever the filter."),
        HelpText("m3x-7", "Classic queues with the old index (version 1) are silently emptied by an in-place upgrade to RabbitMQ 4.3 or later. Do not upgrade in place: build a new 4.x broker and move the messages with a Shovel."),
        HelpText("m3x-8", "Open an item to accept the proposal, choose an alternative, skip it or replace it with your own actions. Generate migration unlocks when every item is decided."),
        HelpText("m3x-9", "Write the proposed 4.x declaration into the migration."),
        HelpText("m3x-10", "Leave this item out of the migration. It moves to Skipped by you and can be put back."),
        HelpText("m3x-11", "Write your own migration actions for this item. The app checks them and never writes a delete."),
        HelpText("m3x-12", "Ordering by priority may change. Quorum queues order messages by priority differently from classic queues."),
        HelpText("m3x-13", "Writes the files into your migrations folder. Nothing is sent to any broker."),
        HelpText("m3x-15", "Virtual hosts, users with their password hashes, and permissions. It is numbered first so it is applied first."),
        HelpText("m3x-16", "guest is never migrated. For a user whose hash 4.x does not accept, set a new password on the broker; the app has no password entry."),
        HelpText("m3x-17", "Their user is not migrated, so their permissions are not written."),
    )

    @Test
    fun `the tips of the screen are the thirteen approved texts, in table order`() {
        assertEquals(expectedTips.map { it.id }, MigrateFromThreeTexts.HELP_TIPS.map { it.id })
        expectedTips.forEach { expected ->
            assertEquals(expected.text, MigrateFromThreeTexts.HELP_TIPS.firstOrNull { it.id == expected.id }?.text, "tip ${expected.id}")
        }
    }

    @Test
    fun `the advice lines are the approved texts`() {
        assertEquals("Analyse a RabbitMQ 3.x broker, decide what to change for 4.x, and generate the migration files. Nothing is ever written to the 3.x broker.", MigrateFromThreeTexts.ADVICE_TOP)
        assertEquals("The virtual host must already exist on the 4.x broker. Because this file has no users or permissions, no access migration can be generated from it.", MigrateFromThreeTexts.ADVICE_VHOST)
        assertEquals("Next: connect the 4.x broker and apply these files from the Migrations screen. Apply the access file first.", MigrateFromThreeTexts.ADVICE_NEXT_STEP)
        assertEquals("Delete the file when you are done.", MigrateFromThreeTexts.DELETE_THE_FILE)
    }

    @Test
    fun `the tip of the 3x pill is pinned for the connection screen`() {
        assertEquals("This broker is only read. To migrate, connect your 4.x broker too.", MigrateFromThreeTexts.HELP_TIP_3X_PILL)
    }

    @Test
    fun `the tips agree with the words the screen already uses`() {
        val byId = MigrateFromThreeTexts.HELP_TIPS.associate { it.id to it.text }
        assertEquals(true, byId["m3x-12"].orEmpty().startsWith(ReportTexts.TICK_ORDERING), "row 12 starts with the tick label the review draws")
        assertEquals(true, byId["m3x-10"].orEmpty().contains(MigrateFromThreeTexts.SKIPPED_GROUP_TITLE), "row 10 names the group the skipped item moves to")
    }

    @Test
    fun `no tip or advice holds a secret word, a url or a sentinel`() {
        val all = MigrateFromThreeTexts.HELP_TIPS.map { it.text } + listOf(MigrateFromThreeTexts.ADVICE_TOP, MigrateFromThreeTexts.ADVICE_VHOST, MigrateFromThreeTexts.ADVICE_NEXT_STEP)
        assertEquals(true, all.isNotEmpty() && all.all { it.isNotBlank() }, "empty texts")
        all.forEach { text -> listOf("SENTINEL", "amqp://", "http://", "https://", "password:").forEach { assertEquals(false, text.contains(it), "$it in: $text") } }
    }
}
