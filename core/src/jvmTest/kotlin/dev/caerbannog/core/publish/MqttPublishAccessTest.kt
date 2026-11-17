package dev.caerbannog.core.publish

import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.planner.MqttRoute
import dev.caerbannog.core.planner.MqttTestMessage
import dev.caerbannog.core.planner.TestMessageOffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * M9-10e: the one rule behind every control that opens the Publish MQTT dialog. The MQTT port is checked first, then the write permission;
 * an unfinished permission check arrives as `publishingEnabled = false`. The reason is the decided `noWritePermission(vhost)` and nothing else.
 */
class MqttPublishAccessTest {

    @Test
    fun `no MQTT port is NoMqttPort whatever the permission`() {
        assertEquals(MqttPublishAccess.NoMqttPort, MqttPublishAccess.of(hasMqtt = false, publishingEnabled = true, vhost = "/"))
        assertEquals(MqttPublishAccess.NoMqttPort, MqttPublishAccess.of(hasMqtt = false, publishingEnabled = false, vhost = "/"), "the port is checked first")
    }

    @Test
    fun `an MQTT port without publishing enabled is NoWritePermission with the decided reason`() {
        assertEquals(
            MqttPublishAccess.NoWritePermission(noWritePermission("/")),
            MqttPublishAccess.of(hasMqtt = true, publishingEnabled = false, vhost = "/"),
        )
        assertEquals(
            "Test publishing is disabled: no write permission on virtual host /.",
            (MqttPublishAccess.of(hasMqtt = true, publishingEnabled = false, vhost = "/") as MqttPublishAccess.NoWritePermission).reason,
        )
    }

    @Test
    fun `the reason names the virtual host`() {
        for (vhost in listOf("/", "orders", "my vhost", "a/b", "")) {
            assertEquals(
                MqttPublishAccess.NoWritePermission(noWritePermission(vhost)),
                MqttPublishAccess.of(hasMqtt = true, publishingEnabled = false, vhost = vhost),
                "vhost '$vhost'",
            )
        }
    }

    @Test
    fun `an MQTT port with publishing enabled is Allowed, whatever the virtual host`() {
        for (vhost in listOf("/", "orders")) {
            assertEquals(MqttPublishAccess.Allowed, MqttPublishAccess.of(hasMqtt = true, publishingEnabled = true, vhost = vhost))
        }
    }

    @Test
    fun `the vhost does not matter when there is no MQTT port or when allowed`() {
        assertEquals(MqttPublishAccess.of(false, false, "/"), MqttPublishAccess.of(false, false, "orders"))
        assertEquals(MqttPublishAccess.of(true, true, "/"), MqttPublishAccess.of(true, true, "orders"))
    }

    // The test message maps the same rule: its four answers do not change.
    private val route = MqttRoute("sensors/+/temp", "sensors.*.temp", "telemetry")
    private fun offer(status: MigrationStatus, hasMqtt: Boolean, publishing: Boolean, vhost: String = "/") =
        MqttTestMessage.offer(status, route, hasMqtt, publishing, vhost)

    @Test
    fun `MqttTestMessage offer still gives the same four answers`() {
        val applied = MigrationStatus.Applied("abc")
        assertEquals(TestMessageOffer.NotOffered, offer(MigrationStatus.Pending, hasMqtt = true, publishing = true))
        assertEquals(TestMessageOffer.Disabled("This connection has no MQTT port"), offer(applied, hasMqtt = false, publishing = true))
        assertEquals(TestMessageOffer.Disabled(noWritePermission("orders")), offer(applied, hasMqtt = true, publishing = false, vhost = "orders"))
        assertEquals(TestMessageOffer.Available, offer(applied, hasMqtt = true, publishing = true))
    }
}
