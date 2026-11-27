package dev.caerbannog.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** M8-02a: the pure decisions of the 3.x source role. Unknown is never 3.x and never 4.x. */
class MigrationSourceDecisionsTest {

    @Test
    fun `only a known major version below 4 is a migration source`() {
        val table = mapOf(
            "3.13.7" to true, "3" to true, "3.8.0-rc.1" to true, "0.9" to true,
            "4.0.0" to false, "4.1" to false, "10.1" to false, "4" to false,
            "3 anything\nsecond line" to false, "3.13.7\nx" to false, "3.${"1".repeat(31)}" to false, " 3.13.7 " to true, "3.13.7-rc.1" to true,
            null to false, "" to false, "x" to false, "v3.13" to false, " 3.13" to true, "-3.1" to false, "99999999999.1" to false,
        )
        table.forEach { (version, expected) -> assertEquals(expected, isMigrationSourceOnly(version), "isMigrationSourceOnly($version)") }
    }

    @Test
    fun `displayableBrokerVersion keeps a plain version and rejects every odd text`() {
        val accepted = mapOf("3.13.7" to "3.13.7", "3.13.7-rc.1" to "3.13.7-rc.1", "4.0.0+build" to "4.0.0+build", " 3.13.7 " to "3.13.7", "3" to "3", "10.1" to "10.1", "3.13.7~beta_1" to "3.13.7~beta_1")
        accepted.forEach { (input, expected) -> assertEquals(expected, displayableBrokerVersion(input), "displayableBrokerVersion([$input])") }
        val rejected = listOf(
            null, "", "   ", "x", "v3.13", ".3", "3.", "-3", "3 anything\nsecond line", "3.13.7\nx", "3.13.7\u0000", "3.13 7", "3.13.7\u202e",
            "3." + "1".repeat(31), "<script>", "3.13.7;rm", "3.13.7 -rc",
        )
        rejected.forEach { assertEquals(null, displayableBrokerVersion(it), "displayableBrokerVersion([${it?.replace("\n", "\\n")}]) must be null") }
        assertEquals("3." + "1".repeat(30), displayableBrokerVersion("3." + "1".repeat(30)), "32 characters are accepted")
        assertEquals(null, displayableBrokerVersion("3." + "1".repeat(31)), "33 characters are not")
    }

    @Test
    fun `a migration source result is locked and cannot publish whatever else is true`() {
        val result = PermissionCheckResult.MigrationSource("3.13.7")
        assertFalse(result.topologyUnlocked, "topology must be locked")
        assertFalse(result.publishingEnabled, "test publishing must be off")
        assertEquals("3.13.7", result.brokerVersion)
    }

    @Test
    fun `the pill says migration source only in the warning tone`() {
        val pill = permissionPill(PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource("3.13.7")))
        assertEquals(PermissionPill("RabbitMQ 3.x: migration source only", PillTone.WARNING), pill)
    }

    @Test
    fun `the banner names the user and the version`() {
        val profile = ConnectionProfile(name = "legacy-eu", host = "h", virtualHost = "/", username = "svc", topologyControlLevel = TopologyControlLevel.FULL_CONTROL)
        val banner = permissionBanner(profile, PermissionCheckStatus.Done(PermissionCheckResult.MigrationSource("3.13.7")))
        assertEquals("svc is connected to RabbitMQ 3.13.7: migration source only.", banner.headline)
        assertEquals("This broker is read only for Caerbannog. Topology changes and test publishing are off; use Migrate from 3.x.", banner.detail)
        assertEquals(PillTone.WARNING, banner.tone)
    }

    @Test
    fun `existing results keep their pills`() {
        val unlocked = PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = true)
        val locked = PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = false)
        assertEquals(PermissionPill("Can change topology", PillTone.OK), permissionPill(PermissionCheckStatus.Done(unlocked)))
        assertEquals(PermissionPill("Topology locked", PillTone.LOCKED), permissionPill(PermissionCheckStatus.Done(locked)))
        assertEquals(PermissionPill("Not checked", PillTone.NEUTRAL), permissionPill(null))
    }

    @Test
    fun `a migration source offers only the migration from 3x`() {
        assertEquals(setOf(ConnectionFeature.MigrateFromThreeX), availableFeatures(PermissionCheckResult.MigrationSource("3.13.7")))
    }

    @Test
    fun `every other result keeps every feature`() {
        val all = ConnectionFeature.entries.toSet()
        listOf(
            null,
            PermissionCheckResult.Checked(true, true),
            PermissionCheckResult.Checked(false, false),
            PermissionCheckResult.DeclaredByUser(true, true, PermissionCheckResult.Unreadable(UnreadableCause.TIMEOUT)),
            PermissionCheckResult.Unreadable(UnreadableCause.NOT_AUTHORIZED),
        ).forEach { assertEquals(all, availableFeatures(it), "features for $it") }
    }

    @Test
    fun `the feature list names the entries the app already has plus the migration and Access (M10-02)`() {
        assertEquals(
            setOf("Streams", "Topology", "TopologyEdits", "Publish", "Search", "Migrations", "MigrateFromThreeX", "Access"),
            ConnectionFeature.entries.map { it.name }.toSet(),
        )
    }
}
