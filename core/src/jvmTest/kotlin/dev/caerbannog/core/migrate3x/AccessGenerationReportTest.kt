package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles as F
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-12b: the result data of a generation with access: the line, the two lists with the approved wording (D6), the secret-file warning (D3), escaped names, no hash. */
class AccessGenerationReportTest {
    private val warning = "This file contains password hashes. Anyone who has it can try to guess the passwords offline. Keep it secret: do not commit it to git or put it in a shared or synced folder."

    private fun generated(text: String): GenerationResult.Generated =
        assertIs(ConversionReviewsRef.core(text).generate(GenerationRequest(emptySet(), "legacy.json")))

    private fun report(g: GenerationResult.Generated, how: How = How.Saved) =
        GenerationReportBuilder.report(how, g.migrations.map { GenerationReportBuilder.fileOf(it, "/m/${it.file.version}.json") }, g, virtualHosts = 1)

    private val full = F.accessFile(
        vhosts = listOf(F.vhost("/orders")),
        users = listOf(F.user("svc", "SENTINEL-HASH-xyz"), F.user("guest"), F.user("old", algorithm = "rabbit_password_hashing_bcrypt"), F.user("ldap", hash = null, algorithm = null), F.user("noalg", algorithm = null)),
        permissions = listOf(F.permission("/orders", "svc"), F.permission("/orders", "old"), F.permission("/orders", "guest")),
    )

    @Test
    fun `the approved wording of the warning and the lists`() {
        assertEquals(warning, MigrateFromThreeTexts.SECRET_FILE_WARNING)
        assertEquals("Users not migrated", MigrateFromThreeTexts.NOT_MIGRATED_TITLE)
        assertEquals("Permissions left out", MigrateFromThreeTexts.PERMISSIONS_LEFT_OUT_TITLE)
        assertEquals("not created; it has a publicly known password", MigrateFromThreeTexts.GUEST_NOT_MIGRATED)
        assertEquals("set a new password on the broker; its password hash uses rabbit_password_hashing_bcrypt, which 4.x does not accept", MigrateFromThreeTexts.unsupportedAlgorithmNotMigrated("rabbit_password_hashing_bcrypt"))
        assertEquals("its password hash has no hashing algorithm; set a new password on the broker", MigrateFromThreeTexts.NO_ALGORITHM_NOT_MIGRATED)
        assertEquals("no password hash in the export; set a password on the broker if it needs one", MigrateFromThreeTexts.NO_HASH_NOT_MIGRATED)
        assertEquals("the user is not migrated", MigrateFromThreeTexts.USER_NOT_MIGRATED)
        assertEquals("guest on /", MigrateFromThreeTexts.permissionLabel("guest", "/"))
        assertEquals("access, all virtual hosts", MigrateFromThreeTexts.ACCESS_PILL)
        assertEquals("readable by you only", MigrateFromThreeTexts.READABLE_BY_YOU_ONLY)
    }

    @Test
    fun `the access line counts what the file holds`() {
        assertEquals("Access: 1 virtual host, 4 users, 6 permissions, 1 topic permission", MigrateFromThreeTexts.accessLine(AccessSummary(1, 4, 6, 1)))
        assertEquals("Access: 2 virtual hosts, 1 user, 1 permission, 2 topic permissions", MigrateFromThreeTexts.accessLine(AccessSummary(2, 1, 1, 2)))
        assertEquals("Access: 1 virtual host, 1 user, 1 permission, 0 topic permissions", report(generated(full)).accessLine)
        // the thousands separator of every other count of the screen ("1,204 bindings"): a private helper must not shadow `plural`
        assertEquals("Access: 1,204 virtual hosts, 12,345 users, 6 permissions, 1 topic permission", MigrateFromThreeTexts.accessLine(AccessSummary(1204, 12345, 6, 1)))
        assertEquals("60,000 items", MigrateFromThreeTexts.itemCount(60_000))
    }

    @Test
    fun `the users not migrated are listed with a reason each and the permissions of those users are left out`() {
        val r = report(generated(full))
        assertEquals(
            setOf(
                LeftOutRow("guest", MigrateFromThreeTexts.GUEST_NOT_MIGRATED),
                LeftOutRow("old", MigrateFromThreeTexts.unsupportedAlgorithmNotMigrated("rabbit_password_hashing_bcrypt")),
                LeftOutRow("ldap", MigrateFromThreeTexts.NO_HASH_NOT_MIGRATED),
                LeftOutRow("noalg", MigrateFromThreeTexts.NO_ALGORITHM_NOT_MIGRATED),
            ),
            r.usersNotMigrated.toSet(),
        )
        assertEquals(
            setOf(LeftOutRow("old on /orders", "the user is not migrated"), LeftOutRow("guest on /orders", "the user is not migrated")),
            r.permissionsLeftOut.toSet(),
        )
    }

    @Test
    fun `the secret warning is set when a generated file holds a declareUser, on Saved and on Exported, else not`() {
        assertEquals(warning, report(generated(full), How.Saved).secretWarning)
        assertEquals(warning, report(generated(full), How.Exported).secretWarning)
        assertNull(report(generated(F.file(queues = listOf(F.queue("jobs", type = "quorum"))))).secretWarning)
        assertNull(report(generated(F.accessFile(vhosts = listOf(F.vhost("/orders"))))).secretWarning, "a file with no user holds no hash")
    }

    @Test
    fun `the access file is listed as access, and a report without access data has no access fields`() {
        val r = report(generated(full))
        assertEquals("access", r.files.firstOrNull()?.vhost)
        val plain = report(generated(F.file(queues = listOf(F.queue("jobs", type = "quorum")))))
        assertNull(plain.accessLine)
        assertEquals(emptyList(), plain.usersNotMigrated)
        assertEquals(emptyList(), plain.permissionsLeftOut)
    }

    @Test
    fun `names from the file are escaped and no hash is in the report`() {
        val text = F.accessFile(vhosts = listOf(F.vhost("/")), users = listOf(F.user("evil\nname‮", "SENTINEL-HASH-esc", "x_unknown")), permissions = listOf(F.permission("/", "evil\nname‮")))
        val r = report(generated(text))
        assertTrue(r.usersNotMigrated.isNotEmpty(), "the premise: the user is listed")
        assertFalse(r.usersNotMigrated.joinToString { it.label }.contains('\n') || r.permissionsLeftOut.joinToString { it.label }.contains('\n'))
        assertEquals(true, r.usersNotMigrated.firstOrNull()?.label?.contains("\\u000A"), r.usersNotMigrated.toString())
        assertFalse(r.toString().contains("SENTINEL-HASH"))
    }
}
