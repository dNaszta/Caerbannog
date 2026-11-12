package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files

/**
 * M8-13 spike S-P (docs/tasks/M8-00-spike-results.md, section "S-P"): the `guest` loopback restriction of a plain install.
 * The official image ships `loopback_users.guest = false` in `/etc/rabbitmq/conf.d/10-defaults.conf`; here that file is replaced
 * by one without the line, so the broker runs with its built-in default (guest only from loopback). The host connects through
 * the mapped port, which reaches the broker from the Docker bridge gateway, not from 127.0.0.1 inside the container.
 * No AMQP client is on the test class path (part A adds one), so the AMQP handshake is a few bytes written by hand.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeGuestLoopbackTest {

    private fun strictBroker(image: String): RabbitMQContainer {
        val conf = Files.createTempFile("10-defaults", ".conf")
        Files.writeString(conf, "log.console = true\n") // the image file minus `loopback_users.guest = false`
        return RabbitMQContainer(image)
            .withCopyFileToContainer(MountableFile.forHostPath(conf, 420) /* 0644: the broker user must read it */, "/etc/rabbitmq/conf.d/10-defaults.conf")
    }

    /** AMQP 0-9-1 handshake with PLAIN by hand; returns "tune" on success or "close <code> <text>" when the broker refuses. */
    private fun amqpLogin(container: RabbitMQContainer, user: String, password: String): String {
        Socket(container.host, container.getMappedPort(5672)).use { socket ->
            socket.soTimeout = 15_000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf('A'.code.toByte(), 'M'.code.toByte(), 'Q'.code.toByte(), 'P'.code.toByte(), 0, 0, 9, 1)); out.flush()
            readFrame(input) // connection.start
            val args = ByteArrayOutputStream()
            DataOutputStream(args).apply {
                writeShort(10); writeShort(11)                 // connection.start-ok
                writeInt(0)                                    // empty client properties table
                writeByte(5); write("PLAIN".toByteArray())     // mechanism
                val response = "\u0000$user\u0000$password".toByteArray()
                writeInt(response.size); write(response)       // response
                writeByte(5); write("en_US".toByteArray())     // locale
            }
            val payload = args.toByteArray()
            out.writeByte(1); out.writeShort(0); out.writeInt(payload.size); out.write(payload); out.writeByte(0xCE); out.flush()
            val reply = try { readFrame(input) } catch (e: java.io.EOFException) { return "closed without a frame" }
            val classId = (reply[0].toInt() and 0xFF shl 8) or (reply[1].toInt() and 0xFF)
            val methodId = (reply[2].toInt() and 0xFF shl 8) or (reply[3].toInt() and 0xFF)
            return when {
                classId == 10 && methodId == 30 -> "tune"
                classId == 10 && methodId == 50 -> {
                    val code = (reply[4].toInt() and 0xFF shl 8) or (reply[5].toInt() and 0xFF)
                    val len = reply[6].toInt() and 0xFF
                    "close $code ${String(reply, 7, len)}"
                }
                else -> "other $classId.$methodId"
            }
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        input.readByte(); input.readShort()
        val bytes = ByteArray(input.readInt())
        input.readFully(bytes); input.readByte()
        return bytes
    }

    @Test
    fun `S-P with the default loopback_users guest is refused from outside the container on AMQP and management API, another user is not`() {
        strictBroker("rabbitmq:4-management").use { broker ->
            broker.start()
            val api = MgmtApi(broker)
            val http = api.get("/api/overview")
            println("S-P management guest: ${http.status} ${http.body}")
            assertEquals(401, http.status)
            val amqp = amqpLogin(broker, "guest", "guest")
            println("S-P amqp guest: $amqp")
            val logged = broker.logs.lines().filter { it.contains("localhost") || it.contains("loopback") }
            println("S-P broker log lines: $logged")
            assertTrue(amqp.startsWith("close 403") || amqp.startsWith("closed without"), amqp)
            assertTrue(logged.any { it.contains("can only log in via localhost") }, "the broker log names the reason: $logged")


            assertEquals(0, broker.execInContainer("rabbitmqctl", "add_user", "svc", "svc-pass").exitCode)
            assertEquals(0, broker.execInContainer("rabbitmqctl", "set_user_tags", "svc", "administrator").exitCode)
            assertEquals(0, broker.execInContainer("rabbitmqctl", "set_permissions", "-p", "/", "svc", ".*", ".*", ".*").exitCode)
            assertEquals(200, api.get("/api/overview", "svc", "svc-pass").status)
            assertEquals("tune", amqpLogin(broker, "svc", "svc-pass"))
        }
    }

    @Test
    fun `S-P control, the stock image config lets guest in from outside`() {
        RabbitMQContainer("rabbitmq:4-management").use { broker ->
            broker.start()
            assertEquals(200, MgmtApi(broker).get("/api/overview").status)
            assertEquals("tune", amqpLogin(broker, "guest", "guest"))
        }
    }
}
