package dev.caerbannog.core.planner

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationsWording
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-12 step 2, written first (RED by compilation until `PlanOpening` and `PlanOpeningDecision` exist in `core.planner`).
 *
 * Contract chosen where the brief is silent:
 *  - `PlanOpening.decide(file: MigrationFile, rawKeysKnown: Boolean, appliedOn: List<String>): PlanOpeningDecision`
 *  - `sealed interface PlanOpeningDecision { data object Openable; data class NotOffered(val reason: String, val detail: String? = null, val canCopy: Boolean = false) }`
 *    `detail` is the second line of the applied case only (mockup 13: "Changing an applied file would mark it as modified there. Copy its steps into a new plan
 *    instead."), `canCopy` is true for the applied case only: a file that holds a hash or an access action, or keys the planner does not know, is never copied (the copy
 *    would hold the hash, or lose the keys without a word).
 *  - `appliedOn` are profile names, in the order the caller gives them; the text names the first one.
 * Order of the refusals (brief): hash, other access action, unknown keys, applied elsewhere.
 */
class PlanOpeningTest {
    private val hashText = "This file holds password hashes. Edit it outside the app."
    private val accessText = "This file holds virtual hosts, users or permissions. The planner works on topology only."
    private val unknownText = "This file holds entries the planner does not know. Edit it as JSON so nothing is lost."

    private fun file(version: String, vararg actions: MigrationAction): MigrationFile {
        val raw = MigrationParser.exportToJson(version, "d", actions.toList())
        return MigrationFile(version, "d", actions.toList(), raw, MigrationParser.computeChecksum(raw))
    }

    private val queue = MigrationAction.DeclareQueue("orders")
    private val user = MigrationAction.DeclareUser("svc", SecretValue.of("SENTINEL-HASH"), "rabbit_password_hashing_sha256")
    private val vhost = MigrationAction.DeclareVhost("orders")

    private fun decide(f: MigrationFile, keysKnown: Boolean = true, appliedOn: List<String> = emptyList()) = PlanOpening.decide(f, keysKnown, appliedOn)

    @Test
    fun `a topology file that is applied nowhere and holds only known keys is openable`() {
        assertEquals(PlanOpeningDecision.Openable, decide(file("0005", queue, MigrationAction.DeleteQueue("old"))))
    }

    @Test
    fun `a declareUser gives the fixed hash text and nothing else`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0001", queue, user)))
        assertEquals(hashText, d.reason)
        assertEquals(MigrationsWording.HASH_FILE_NO_EDIT, d.reason)
        assertEquals(false, d.canCopy, "a copy would put the hash in the draft")
    }

    @Test
    fun `every other broker-level action gives the access text`() {
        listOf<MigrationAction>(
            vhost,
            MigrationAction.SetPermissions("orders", "svc"),
            MigrationAction.SetTopicPermissions("orders", "svc", "amq.topic"),
        ).forEach { access ->
            val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0001", queue, access)), access.actionName)
            assertEquals(accessText, d.reason, access.actionName)
            assertEquals(false, d.canCopy, access.actionName)
        }
    }

    @Test
    fun `a keys problem gives the unknown-entries text`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0007", queue), keysKnown = false))
        assertEquals(unknownText, d.reason)
        assertEquals(false, d.canCopy, "a copy would drop the unknown entries without a word")
    }

    @Test
    fun `a file applied on a connection says where, through the echo rule, and may be copied`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0002", queue), appliedOn = listOf("prod-eu")))
        assertEquals("0002 is applied on prod-eu, so it is not edited", d.reason)
        assertEquals("Changing an applied file would mark it as modified there. Copy its steps into a new plan instead.", d.detail)
        assertEquals(true, d.canCopy)
    }

    @Test
    fun `a connection name that may not be echoed is left out and the sentence stays whole`() {
        listOf("prod \"eu\"", "x".repeat(65), "line\nbreak", "").forEach { name ->
            val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0002", queue), appliedOn = listOf(name)))
            assertEquals("0002 is applied on a connection of this machine, so it is not edited", d.reason, "name: ${name.take(20)}")
            assertTrue(!d.reason.contains(name) || name.isEmpty())
        }
    }

    @Test
    fun `with several connections the first one named by the caller is used`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0002", queue), appliedOn = listOf("prod-eu", "uat-eu")))
        assertEquals("0002 is applied on prod-eu, so it is not edited", d.reason)
    }

    @Test
    fun `the order of the refusals is hash, access, unknown keys, applied elsewhere`() {
        val applied = listOf("prod-eu")
        assertEquals(hashText, (decide(file("1", vhost, user), keysKnown = false, appliedOn = applied) as PlanOpeningDecision.NotOffered).reason, "hash beats access, keys, applied")
        assertEquals(accessText, (decide(file("1", vhost), keysKnown = false, appliedOn = applied) as PlanOpeningDecision.NotOffered).reason, "access beats keys, applied")
        assertEquals(unknownText, (decide(file("1", queue), keysKnown = false, appliedOn = applied) as PlanOpeningDecision.NotOffered).reason, "keys beat applied")
        assertTrue((decide(file("1", queue), keysKnown = true, appliedOn = applied) as PlanOpeningDecision.NotOffered).reason.contains("is applied on"))
    }

    @Test
    fun `an applied file with unknown keys is not copied either`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(decide(file("0002", queue), keysKnown = false, appliedOn = listOf("prod-eu")))
        assertEquals(false, d.canCopy)
    }

    @Test
    fun `an empty file is openable`() {
        assertEquals(PlanOpeningDecision.Openable, decide(file("0009")))
    }

    // ---- the copy: the steps of a file become a plan ----

    @Test
    fun `planOf gives one step per action in the file's order with the ids 1 to n and the file's description`() {
        val f = file("0002", queue, MigrationAction.DeclareExchange("ev", "topic"), MigrationAction.DeleteQueue("old"))
        val plan = PlanOpening.planOf(f, defaultVhost = "/")
        assertEquals(listOf(1, 2, 3), plan.steps.map { it.id })
        assertEquals(f.up, plan.steps.map { it.action })
        assertEquals("d", plan.description)
    }

    @Test
    fun `planOf takes the virtual host of the first action and the default for an empty file`() {
        assertEquals("orders", PlanOpening.planOf(file("1", MigrationAction.DeclareQueue("q", vhost = "orders")), "/").vhost)
        assertEquals("uat", PlanOpening.planOf(file("2"), "uat").vhost)
    }
}
