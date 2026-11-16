package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M9-02 security (spec A8). A sentinel password sits in a secret store of the test (the view model's constructor takes no secret store: it cannot
 * reach one) and a sentinel hash sits in a migration of the folder. Neither may appear in the draft file, the saved file, the state or any text
 * the planner shows. A plan has no user, password or hash because the wizard has no kind for them.
 */
class PlannerSecretsTest {
    private val sentinelPassword = "S3NTINEL-PASSWORD-9f2c"
    private val sentinelHash = "S3NTINEL-HASH-77ab"
    private val rig = PlannerRig()

    private val secrets = object : SecretStore {
        private val map = mutableMapOf("uat-eu" to sentinelPassword)
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    @AfterTest
    fun close() = rig.close()

    private fun folderHoldsAUserWithAHash() {
        val raw = MigrationParser.exportToJson(
            "0003",
            "has a user",
            listOf(MigrationAction.DeclareUser("svc", SecretValue.of(sentinelHash), "rabbit_password_hashing_sha256")),
        )
        rig.folder.files["0003"] = (MigrationParser.parse(raw) as MigrationValidationResult.Valid).migration
        rig.restart()
    }

    private fun everythingShown(): String = buildString {
        append(rig.state)
        rig.store.rawText?.let { append(it) }
        rig.store.savedTexts.forEach { append(it) }
        rig.folder.attempts.forEach { append(it) }
        rig.state.saved?.let { append(it.message).append(it.fileName).append(it.version) }
        rig.state.banner?.let { append(it.title).append(it.detail) }
        rig.state.wizard.let { append(it) }
    }

    @Test
    fun `a saved plan and its draft hold neither the password nor the hash`() {
        assertEquals(sentinelPassword, secrets.retrieve("uat-eu"), "precondition: the secret is in the store")
        folderHoldsAUserWithAHash()
        rig.threeSteps()
        val draft = rig.store.rawText!!
        rig.vm.saveAsMigration()

        val saved = rig.folder.files["0005"]!!
        val all = everythingShown() + draft + saved.rawJson
        assertFalse(sentinelPassword in all)
        assertFalse(sentinelHash in all, "the hash of a migration of the folder must not reach the plan, the draft or the saved file")
        listOf("password", "passwordHash", "\"user\"", "\"hash\"").forEach { key ->
            assertFalse(saved.rawJson.contains(key, ignoreCase = true), "the saved file mentions $key")
            assertFalse(draft.contains(key, ignoreCase = true), "the draft mentions $key")
        }
        assertEquals(emptyList(), saved.up.filter { it.isBrokerLevel }, "no access action in a plan built with the wizard")
    }

    @Test
    fun `no kind of the wizard builds a user, a permission or a virtual host`() {
        val everyKind = PlannerTexts.WIZARD_KINDS
        everyKind.forEach { kind ->
            rig.vm.openWizard()
            rig.vm.chooseKind(kind)
            rig.vm.cancelWizard()
        }
        assertTrue(everyKind.none { k -> listOf("user", "permission", "virtual host", "vhost", "password").any { k.contains(it, true) } })
        assertEquals(emptyList(), rig.state.plan.steps)
    }
}
