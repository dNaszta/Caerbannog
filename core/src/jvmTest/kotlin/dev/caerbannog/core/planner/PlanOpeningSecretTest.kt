package dev.caerbannog.core.planner

import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-12 step 2 (security, A8): a `declareUser` file with a sentinel hash. The decision refuses it before any plan is built, so no view model is created for it
 * and no text of the decision, the draft or any state holds the sentinel. The copy path is closed too (`canCopy` false), and `PlanOpening.planOf` is never
 * called for it by the app;  a stray call is refused because the draft store itself refuses a broker-level action (checked here).
 * RED by compilation until `PlanOpening` exists.
 */
class PlanOpeningSecretTest {
    private val sentinel = "SENTINEL-HASH-9f3a"

    private val file = run {
        val raw = MigrationParser.exportToJson(
            "0001", "access",
            listOf(MigrationAction.DeclareVhost("orders"), MigrationAction.DeclareUser("svc", SecretValue.of(sentinel), "rabbit_password_hashing_sha256")),
        )
        assertTrue(raw.contains(sentinel), "premise: the file holds the hash")
        (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
    }

    @Test
    fun `the decision for a hash file holds the sentinel nowhere and offers no copy`() {
        val d = assertIs<PlanOpeningDecision.NotOffered>(PlanOpening.decide(file, rawKeysKnown = true, appliedOn = listOf("prod-eu")))
        assertEquals("This file holds password hashes. Edit it outside the app.", d.reason)
        assertFalse(d.toString().contains(sentinel), "toString of the decision")
        assertFalse(d.reason.contains(sentinel))
        assertFalse((d.detail ?: "").contains(sentinel))
        assertFalse(d.canCopy)
    }

    @Test
    fun `the file's own text never shows the sentinel`() = assertFalse(file.toString().contains(sentinel))

    @Test
    fun `no plan can be made from such a file at all, so no step, state or draft can hold the hash`() {
        // defence in depth (contract chosen by the test developer, architect to confirm): planOf refuses a file with a broker-level action
        val failure = assertFailsWith<IllegalArgumentException> { PlanOpening.planOf(file, "/") }
        assertFalse((failure.message ?: "").contains(sentinel), "the refusal repeats the hash")
    }

    @Test
    fun `planOf refuses every broker-level action, not only the user`() {
        listOf<MigrationAction>(MigrationAction.DeclareVhost("v"), MigrationAction.SetPermissions("v", "u"), MigrationAction.SetTopicPermissions("v", "u", "amq.topic")).forEach { access ->
            val raw = MigrationParser.exportToJson("0002", "d", listOf(MigrationAction.DeclareQueue("q"), access))
            val f = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
            assertFailsWith<IllegalArgumentException>(access.actionName) { PlanOpening.planOf(f, "/") }
        }
    }
}
