package dev.caerbannog.datarabbit

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * M8-13 spike S-N follow-ups (docs/tasks/M8-00-spike-results.md, section "S-N follow-ups"): `classic_queue.default_version`,
 * messages published over MQTT into a version-1 classic queue and the 4.0 / 4.4 upgrade, and the timing of the policy-without-restart
 * conversion. Same method as [RealBrokerM8SpikeUpgradeTest] (one volume, fixed host name, graceful stop). AMQP 1.0 publishing is NOT
 * covered: there is no AMQP 1.0 client on the test class path.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeUpgradeFollowUpTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeUpgradeFollowUpTest {

    private companion object {
        const val OLD = "rabbitmq:3.13-management"
        const val HOST = "upg-host"
    }

    private val docker = DockerClientFactory.instance().client()
    private val volumes = mutableListOf<String>()
    private val running = mutableListOf<RabbitMQContainer>()

    @AfterEach
    fun cleanUp() {
        running.forEach { runCatching { it.stop() } }
        running.clear()
        volumes.forEach { runCatching { docker.removeVolumeCmd(it).exec() } }
        volumes.clear()
    }

    private fun newVolume(): String = "m8-snf-${UUID.randomUUID().toString().take(8)}".also {
        docker.createVolumeCmd().withName(it).exec()
        volumes += it
    }

    private fun start(container: RabbitMQContainer, volume: String): Pair<RabbitMQContainer, MgmtApi> {
        container.withCreateContainerCmdModifier { cmd ->
            cmd.withHostName(HOST)
            cmd.hostConfig!!.withBinds(Bind(volume, Volume("/var/lib/rabbitmq")))
        }
        container.start()
        running += container
        return container to MgmtApi(container)
    }

    private fun start(image: String, volume: String) = start(RabbitMQContainer(image), volume)

    private fun stopGracefully(container: RabbitMQContainer) {
        docker.stopContainerCmd(container.containerId).withTimeout(60).exec()
        container.stop()
        running.remove(container)
    }

    /** Waits on the clock (the delay is the thing measured), never a bare sleep. */
    private fun pauseSeconds(seconds: Long) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        if (seconds > 0) awaitUntil("a measured pause of $seconds s", seconds + 5) { System.nanoTime() >= until }
    }

    private fun storageVersion(api: MgmtApi, queue: String): Int? {
        awaitUntil("statistics of $queue") { api.get("/api/queues/%2F/$queue").let { it.ok && it.obj.containsKey("messages") } }
        return api.get("/api/queues/%2F/$queue").obj["storage_version"]?.toString()?.toIntOrNull()
    }

    private fun count(api: MgmtApi, queue: String): Int {
        awaitUntil("$queue answers") { runCatching { api.peek(queue) }.isSuccess }
        return api.peek(queue).size
    }

    @Test
    fun `S-N classic_queue_default_version 2 makes new classic queues version 2 on 3_13 and 4_x, and an explicit x-queue-version still wins`() {
        for (image in listOf(OLD, "rabbitmq:4-management")) {
            RabbitMQContainer(image).withRabbitMQConfig(MountableFile.forClasspathResource("spike/classic-default-v2.conf")).use { withConf ->
                withConf.start()
                val api = MgmtApi(withConf)
                api.declareQueue("dflt")
                val explicitDeclare = api.declareQueue("explicit-1", """{"x-queue-version":1}""")
                val dflt = storageVersion(api, "dflt")
                val explicit = if (explicitDeclare.ok) storageVersion(api, "explicit-1") else null
                println("S-N default_version=2 on $image: default queue v$dflt, x-queue-version 1: declare ${explicitDeclare.status} ${explicitDeclare.reason()} / queue v$explicit")
                assertEquals(2, dflt, image)
            }
            RabbitMQContainer(image).use { plain ->
                plain.start()
                val api = MgmtApi(plain)
                api.declareQueue("dflt")
                val explicitDeclare = api.declareQueue("explicit-1", """{"x-queue-version":1}""")
                val plainDefault = storageVersion(api, "dflt")
                println("S-N no config on $image: default queue v$plainDefault, x-queue-version 1: declare ${explicitDeclare.status} ${explicitDeclare.reason()} / queue v${if (explicitDeclare.ok) storageVersion(api, "explicit-1") else null}")
                assertEquals(if (image == OLD) 1 else 2, plainDefault, "the default of $image without configuration")
                assertEquals(image == OLD, explicitDeclare.ok, "x-queue-version 1 is accepted on 3.13 and refused on 4.4")
            }
        }
    }

    @Test
    fun `S-N messages published over MQTT into a version-1 classic queue and the upgrade to 4_0 and 4_4`() {
        for (target in listOf("rabbitmq:4.0-management", "rabbitmq:4-management")) {
            val volume = newVolume()
            val (old, api) = start(brokerWithPlugins(OLD, "rabbitmq_mqtt").apply { addExposedPort(1883) }, volume)
            api.declareQueue("from-mqtt")
            assertEquals(201, api.post("/api/bindings/%2F/e/amq.topic/q/from-mqtt", """{"routing_key":"spike"}""").status)
            awaitUntil("MQTT listener", 60) { runCatching { publishMqtt(old, "probe-${UUID.randomUUID()}") }.isSuccess }
            api.drain("from-mqtt")
            repeat(3) { publishMqtt(old, "mqtt-$it") }
            awaitUntil("3 MQTT messages on the queue") { api.peek("from-mqtt").size == 3 }
            val version = storageVersion(api, "from-mqtt")
            val props = api.post("/api/queues/%2F/from-mqtt/get", """{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}""").arr[0].toString()
            println("S-N MQTT queue on 3.13: storage_version=$version, first message: ${props.take(300)}")
            stopGracefully(old)

            val (_, api2) = start(target, volume)
            val after = count(api2, "from-mqtt")
            println("S-N MQTT-published messages in a v$version classic queue after 3.13 -> $target: $after of 3")
            assertEquals(1, version)
            assertEquals(if (target.contains("4.0")) 3 else 0, after, "$target: converted and kept up to 4.2, lost on 4.3 and later, like management-published messages")
            running.forEach { runCatching { it.stop() } }
            running.clear()
        }
    }

    private fun publishMqtt(broker: RabbitMQContainer, payload: String) {
        val client = MqttClient("tcp://${broker.host}:${broker.getMappedPort(1883)}", "spike-${UUID.randomUUID()}", MemoryPersistence())
        try {
            client.connect(MqttConnectOptions().apply { userName = "guest"; password = "guest".toCharArray(); isCleanSession = true; connectionTimeout = 5 })
            client.publish("spike", payload.toByteArray(), 1, false)
        } finally {
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }

    @Test
    fun `S-N policy queue-version 2 on a running 3_13 node, how long until the on-disk index survives a jump to 4_4`() {
        for (seconds in listOf(0L, 3L, 10L, 0L, 3L, 10L)) {
            val volume = newVolume()
            val (old, api) = start(OLD, volume)
            api.declareQueue("t-q")
            repeat(3) { api.publish("t-q", "m$it") }
            assertEquals(1, storageVersion(api, "t-q"), "starts as version 1")
            assertEquals(true, api.put("/api/policies/%2F/qv2", """{"pattern":"^t-","apply-to":"queues","priority":1,"definition":{"queue-version":2}}""").ok)
            val apiAfterPolicy = storageVersion(api, "t-q")
            pauseSeconds(seconds)
            val apiLater = api.get("/api/queues/%2F/t-q").obj["storage_version"]
            stopGracefully(old)
            val (_, api2) = start("rabbitmq:4-management", volume)
            val survived = count(api2, "t-q")
            println("S-N policy-without-restart, stop ${seconds}s after the policy: API storage_version right after the policy=$apiAfterPolicy and ${seconds}s later=$apiLater; messages on 4.4: $survived of 3")
            assertEquals(3, survived, "stopped ${seconds}s after the policy (the loss seen once in the S-N run at 3 s did not reproduce)")
            running.forEach { runCatching { it.stop() } }
            running.clear()
        }
    }
}
