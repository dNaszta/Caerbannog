package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M9-10b step 2: when the Migrations screen offers "Send a test message": applied first, then the MQTT port, then the write permission. */
class MqttTestMessageOfferTest {
    private val route = MqttRoute("sensors/+/temp", "sensors.*.temp", "telemetry")
    private val noPort = "This connection has no MQTT port"
    private fun noWrite(vhost: String) = "Test publishing is disabled: no write permission on virtual host $vhost."

    private fun offer(status: MigrationStatus, hasMqtt: Boolean, publishing: Boolean, vhost: String = "/") =
        MqttTestMessage.offer(status, route, hasMqtt, publishing, vhost)

    @Test
    fun `a file that is not applied offers nothing, whatever else is missing`() {
        for (status in listOf(MigrationStatus.Pending, MigrationStatus.Modified("a", "b"))) {
            for (mqtt in listOf(true, false)) for (publishing in listOf(true, false)) {
                assertEquals(TestMessageOffer.NotOffered, offer(status, mqtt, publishing), "$status mqtt=$mqtt publishing=$publishing")
            }
        }
    }

    @Test
    fun `an applied file on a connection without an MQTT port is disabled with the port text, before the write text`() {
        assertEquals(TestMessageOffer.Disabled(noPort), offer(MigrationStatus.Applied("abc"), hasMqtt = false, publishing = true))
        assertEquals(TestMessageOffer.Disabled(noPort), offer(MigrationStatus.Applied("abc"), hasMqtt = false, publishing = false))
    }

    @Test
    fun `an applied file without write permission is disabled with the write text naming the virtual host`() {
        assertEquals(TestMessageOffer.Disabled(noWrite("/")), offer(MigrationStatus.Applied("abc"), hasMqtt = true, publishing = false))
        assertEquals(TestMessageOffer.Disabled(noWrite("prod")), offer(MigrationStatus.Applied("abc"), hasMqtt = true, publishing = false, vhost = "prod"))
    }

    @Test
    fun `an applied file with an MQTT port and write permission is available`() {
        assertEquals(TestMessageOffer.Available, offer(MigrationStatus.Applied("abc"), hasMqtt = true, publishing = true))
    }
}
