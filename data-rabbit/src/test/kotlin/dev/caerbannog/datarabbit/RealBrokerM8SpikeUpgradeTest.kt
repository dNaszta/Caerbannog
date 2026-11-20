package dev.caerbannog.datarabbit

import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Volume
import com.rabbitmq.client.MessageProperties
import com.rabbitmq.client.ReturnListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * M8-13 spike S-N (docs/tasks/M8-00-spike-results.md, section "S-N"): why classic queues came back empty after an in-place
 * RabbitMQ 3.13 to 4.4 upgrade. Living proof of the measured answer, so a newer image that changes it fails here on purpose.
 *
 * Method: one Docker volume is the broker's data directory; a 3.13 node is stopped gracefully (or killed) and a node of
 * another image is started on the same volume with the same host name, which keeps the node name `rabbit@upg-host`.
 * Counting uses a requeueing peek (`ack_requeue_true`), which is exact, so there is no statistics lag to wait for.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeUpgradeTest {

    private companion object {
        const val OLD = BrokerImages.LEGACY_3X
        const val HOST = "upg-host"
        /** Bound for the broker's confirms of one batch of messages; a slow runner may need seconds, a missing confirm must fail. */
        const val CONFIRM_TIMEOUT_MS = 60_000L
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

    private fun newVolume(): String = "m8-sn-${UUID.randomUUID().toString().take(8)}".also {
        docker.createVolumeCmd().withName(it).exec()
        volumes += it
    }

    /** A broker of [image] on the data directory [volume]; the host name is fixed so the node name survives an image change. */
    private fun start(image: String, volume: String): Pair<RabbitMQContainer, MgmtApi> {
        val container = RabbitMQContainer(image).withCreateContainerCmdModifier { cmd ->
            cmd.withHostName(HOST)
            cmd.hostConfig!!.withBinds(Bind(volume, Volume("/var/lib/rabbitmq")))
        }
        container.start()
        running += container
        return container to MgmtApi(container)
    }

    /** `docker stop` with a 60 s grace period: the node shuts down cleanly. */
    private fun stopGracefully(container: RabbitMQContainer) {
        docker.stopContainerCmd(container.containerId).withTimeout(60).exec()
        container.stop()
        running.remove(container)
    }

    /** `docker kill`: no clean shutdown at all. */
    private fun kill(container: RabbitMQContainer) {
        docker.killContainerCmd(container.containerId).exec()
        container.stop()
        running.remove(container)
    }

    private fun seed(api: MgmtApi, queue: String, arguments: String, count: Int = 3, deliveryMode: Int = 2, padding: Int = 0, durable: Boolean = true) {
        assertTrue(api.declareQueue(queue, arguments, durable = durable).ok, "declare $queue")
        val pad = "x".repeat(padding)
        repeat(count) { i -> assertTrue(api.publish(queue, "$queue-$i$pad", """{"delivery_mode":$deliveryMode}""").ok, "publish $queue") }
    }

    /**
     * Seeds [queues] (name to arguments, 3 persistent messages each) on a node the test is about to `docker kill`, and returns
     * only when the broker holds all of it on disk. [seed] is not enough for that, because the management API answers before
     * the broker has finished writing:
     *
     * - The queue itself. A declaration is a Mnesia transaction (the 3.13 metadata store) that the management API reports before
     *   Mnesia has settled it on disk (it logs through asynchronous `disk_log` appends), so a kill soon after a declare can take
     *   the queue with it: after the restart the log says "Recovering 1 queues" and the queue answers 404 while its sibling is
     *   intact. This is the CI failure; it reproduced on a CPU-starved node, once with the queue's name already in
     *   `LATEST.LOG` at the kill, so the log file alone is no proof. [dumpQueueDefinitions] is the barrier.
     * - The messages. A version-2 queue keeps new index entries and small bodies in a write buffer in the node's memory until its
     *   process goes idle or a confirm is due (read from the 3.13.7 beam files; never seen to lose messages here). A publisher
     *   confirm is sent only after that flush ([publishConfirmed]).
     *
     * Neither is an fsync: `docker kill` leaves what the node has already handed to the OS, which is the state this test needs.
     */
    private fun seedBeforeKill(node: RabbitMQContainer, api: MgmtApi, queues: Map<String, String>) {
        for ((queue, arguments) in queues) {
            assertTrue(api.declareQueue(queue, arguments).ok, "declare $queue")
            publishConfirmed(node, queue, List(3) { "$queue-$it" })
        }
        dumpQueueDefinitions(node, queues.keys)
    }

    /** Publishes [bodies] to [queue] over AMQP with publisher confirms and returns only when the broker has confirmed every one. */
    private fun publishConfirmed(node: RabbitMQContainer, queue: String, bodies: List<String>) {
        val unroutable = AtomicInteger()
        withAmqp(node, "m8-sn-seed") { connection ->
            connection.createChannel().use { channel ->
                channel.confirmSelect()
                // Mandatory, to the default exchange: a missing queue comes back as basic.return, before the confirm.
                channel.addReturnListener(ReturnListener { _, _, _, _, _, _ -> unroutable.incrementAndGet() })
                bodies.forEach { channel.basicPublish("", queue, true, MessageProperties.PERSISTENT_BASIC, it.toByteArray()) }
                channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS)
            }
        }
        assertEquals(0, unroutable.get(), "publish to $queue was returned as unroutable")
    }

    /**
     * Moves Mnesia's transaction log into the table files (`mnesia:dump_log()`, which answers `dumped`) and returns when the
     * names of [queues] are in the durable-queue table files `rabbit_durable_queue.DCD` or `.DCL`, which recovery reads first.
     * That is observed, not assumed: a declaration whose transaction was still being committed when the dump ran is not in the
     * files yet, so the dump is repeated, within the bound of [awaitUntil].
     */
    private fun dumpQueueDefinitions(node: RabbitMQContainer, queues: Collection<String>) {
        var seen = "no dump yet"
        try {
            awaitUntil("the Mnesia table files to hold the definitions of $queues") {
                val dump = node.execInContainer("rabbitmqctl", "eval", "mnesia:dump_log().")
                val missing = queues.filter { queue ->
                    node.execInContainer("sh", "-c", "grep -a -l -F '$queue' /var/lib/rabbitmq/mnesia/rabbit@$HOST/rabbit_durable_queue.DC*").exitCode != 0
                }
                seen = "dump_log answered '${dump.stdout.trim()}' (exit ${dump.exitCode}), not yet in the table files: $missing"
                dump.exitCode == 0 && missing.isEmpty()
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}; the last attempt: $seen", e)
        }
    }

    private fun waitForCount(api: MgmtApi, queue: String, expected: Int) {
        // The peek is exact, but a freshly started node may need a moment before the queue answers.
        var seen = "no answer yet"
        try {
            awaitUntil("$queue to hold $expected messages") {
                runCatching { api.peek(queue).size }
                    .onSuccess { seen = "$it messages" }
                    .onFailure { seen = "no peek answer ($it)" }
                    .getOrDefault(-1) == expected
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}; the last peek saw $seen", e)
        }
    }

    private fun count(api: MgmtApi, queue: String): Int = api.peek(queue).size

    /** The version appears with the queue's first statistics, a few seconds after declaring it (or after a node start). */
    private fun storageVersion(api: MgmtApi, queue: String): Int? {
        awaitUntil("statistics of $queue") { api.get("/api/queues/%2F/$queue").let { it.ok && it.obj.containsKey("messages") } }
        return api.get("/api/queues/%2F/$queue").obj["storage_version"]?.toString()?.toIntOrNull()
    }

    private fun logOf(container: RabbitMQContainer): String = container.logs.replace(Regex("\u001B\\[[0-9;]*m"), "")

    /** The queues every test seeds on 3.13: the version-1 and version-2 index, every flavour that could matter. */
    private fun seedMatrix(api: MgmtApi) {
        seed(api, "v1-persist", """{"x-queue-version":1}""")
        seed(api, "default-persist", "{}")
        seed(api, "v2-persist", """{"x-queue-version":2}""")
        seed(api, "v1-transient-msgs", """{"x-queue-version":1}""", deliveryMode = 1)
        seed(api, "v2-transient-msgs", """{"x-queue-version":2}""", deliveryMode = 1)
        seed(api, "v1-large", """{"x-queue-version":1}""", padding = 6000)
        seed(api, "v2-large", """{"x-queue-version":2}""", padding = 6000)
        seed(api, "v1-lazy", """{"x-queue-version":1,"x-queue-mode":"lazy"}""")
        seed(api, "v1-prio", """{"x-queue-version":1,"x-max-priority":5}""")
        seed(api, "v2-prio", """{"x-queue-version":2,"x-max-priority":5}""")
        assertTrue(api.declareQueue("v1-empty", """{"x-queue-version":1}""").ok)
        seed(api, "v1-nondurable", """{"x-queue-version":1}""", durable = false)
        seed(api, "quorum", """{"x-queue-type":"quorum"}""")
        awaitUntil("all seeded messages visible") { count(api, "quorum") == 3 }
    }

    @Test
    fun `3_13 reports the classic queue index version as storage_version, version 1 is the 3_13 default`() {
        val (_, api) = start(OLD, newVolume())
        seed(api, "p-v1", """{"x-queue-version":1}""", count = 1)
        seed(api, "p-v2", """{"x-queue-version":2}""", count = 1)
        seed(api, "p-default", "{}", count = 1)
        seed(api, "p-quorum", """{"x-queue-type":"quorum"}""", count = 1)

        assertEquals(1, storageVersion(api, "p-v1"))
        assertEquals(2, storageVersion(api, "p-v2"))
        assertEquals(1, storageVersion(api, "p-default"), "a classic queue declared without arguments uses the version 1 index on 3.13")
        assertEquals(null, storageVersion(api, "p-quorum"), "a quorum queue has no classic index version")
        // The list endpoint carries it too, which is what a live scan of a broker would use.
        fun listed(): Map<String, String?> = api.get("/api/queues/%2F").arr.associate {
            val queue = it as kotlinx.serialization.json.JsonObject
            queue["name"].toString().trim('"') to queue["storage_version"]?.toString()
        }
        awaitUntil("storage_version of every classic queue in the list endpoint") { listed()["p-default"] != null && listed()["p-v2"] != null }
        val byQueue = listed()
        assertEquals("1", byQueue["p-default"], "list endpoint: $byQueue")
        assertEquals("2", byQueue["p-v2"], "list endpoint: $byQueue")
    }

    @Test
    fun `a direct 3_13 to 4_4 upgrade empties every version-1 classic queue and keeps version-2 and quorum queues`() {
        val volume = newVolume()
        val (old, oldApi) = start(OLD, volume)
        seedMatrix(oldApi)
        stopGracefully(old)

        val (new, api) = start(BrokerImages.CURRENT_4X, volume)
        // The node boots and serves; what is lost is only found by looking.
        for (lost in listOf("v1-persist", "default-persist", "v1-large", "v1-lazy", "v1-prio", "v1-empty")) {
            assertEquals(0, count(api, lost), "$lost is a version-1 queue: its messages are gone on 4.4")
        }
        for (kept in listOf("v2-persist", "v2-large", "v2-prio", "quorum")) {
            assertEquals(3, count(api, kept), "$kept survives")
        }
        // Not related to the version: transient messages never survive a restart, non-durable queues are not recreated.
        assertEquals(0, count(api, "v2-transient-msgs"), "transient messages do not survive any restart")
        assertEquals(404, api.get("/api/queues/%2F/v1-nondurable").status, "a non-durable queue is gone")

        // The loss is silent for anything that only asks the API: the queue is reported as running and accepts new messages.
        assertEquals("running", api.get("/api/queues/%2F/v1-persist").obj["state"]!!.toString().trim('"'))
        assertTrue(api.publish("v1-persist", "after-upgrade").ok)
        assertEquals(1, count(api, "v1-persist"))
        // The only trace is in the broker log.
        val log = logOf(new)
        assertTrue(log.contains("failed to initialise") && log.contains("rabbit_classic_queue_index_v2"), "no crash trace in the 4.4 log")
    }

    @Test
    fun `the loss starts with RabbitMQ 4_3, direct upgrades to 4_0, 4_1 and 4_2 convert version-1 queues and keep their messages`() {
        for ((image, loses) in listOf(BrokerImages.UPGRADE_4_0 to false, BrokerImages.UPGRADE_4_1 to false, BrokerImages.UPGRADE_4_2 to false, BrokerImages.UPGRADE_4_3 to true)) {
            val volume = newVolume()
            val (old, oldApi) = start(OLD, volume)
            seed(oldApi, "v1", """{"x-queue-version":1}""")
            seed(oldApi, "v2", """{"x-queue-version":2}""")
            stopGracefully(old)

            val (_, api) = start(image, volume)
            assertEquals(3, count(api, "v2"), "$image keeps the version-2 queue")
            assertEquals(if (loses) 0 else 3, count(api, "v1"), "$image and the version-1 queue")
            running.forEach { runCatching { it.stop() } }
            running.clear()
        }
    }

    @Test
    fun `going through 4_0 first keeps every classic queue and the next jump to 4_4 is clean`() {
        val volume = newVolume()
        val (old, oldApi) = start(OLD, volume)
        seedMatrix(oldApi)
        stopGracefully(old)

        val (mid, midApi) = start(BrokerImages.UPGRADE_4_0, volume)
        for (queue in listOf("v1-persist", "default-persist", "v1-large", "v1-lazy", "v1-prio", "v2-persist", "v2-large", "v2-prio", "quorum")) {
            assertEquals(3, count(midApi, queue), "$queue after the hop to 4.0")
        }
        stopGracefully(mid)

        val (_, api) = start(BrokerImages.CURRENT_4X, volume)
        for (queue in listOf("v1-persist", "default-persist", "v1-large", "v1-lazy", "v1-prio", "v2-persist", "v2-large", "v2-prio", "quorum")) {
            assertEquals(3, count(api, queue), "$queue after 3.13 -> 4.0 -> 4.4")
        }
    }

    @Test
    fun `converting with the queue-version policy and a graceful restart of the 3_13 node keeps the messages, an unconverted queue loses them`() {
        val volume = newVolume()
        val (first, api1) = start(OLD, volume)
        seed(api1, "conv-restart", "{}")
        seed(api1, "conv-live", "{}")
        seed(api1, "stays-v1", "{}")
        assertEquals(1, storageVersion(api1, "conv-restart"))

        // conv-restart: policy, then a graceful restart of the 3.13 node, which converts the index when the queue starts.
        assertTrue(api1.put("/api/policies/%2F/convert-r", """{"pattern":"^conv-restart$","apply-to":"queues","definition":{"queue-version":2},"priority":0}""").ok)
        stopGracefully(first)
        val (second, api2) = start(OLD, volume)
        assertEquals(2, storageVersion(api2, "conv-restart"), "after the restart the queue has the version 2 index")
        assertEquals(3, count(api2, "conv-restart"), "the conversion keeps the messages on 3.13")

        // conv-live: policy set on the RUNNING node, no restart afterwards. The management API reports version 2 at once. Whether
        // the index was really converted by the time the node stops was NOT deterministic in the spike (it survived in two runs
        // and was lost in one where the node was stopped 3 s after the policy), so its message count is deliberately not asserted.
        assertTrue(api2.put("/api/policies/%2F/convert-l", """{"pattern":"^conv-live$","apply-to":"queues","definition":{"queue-version":2},"priority":0}""").ok)
        awaitUntil("policy applied to conv-live") { api2.get("/api/queues/%2F/conv-live").obj["effective_policy_definition"].toString().contains("queue-version") }
        assertEquals(2, storageVersion(api2, "conv-live"), "the API reports the new version after the policy")
        assertEquals(1, storageVersion(api2, "stays-v1"))
        stopGracefully(second)

        val (_, api) = start(BrokerImages.CURRENT_4X, volume)
        assertEquals(3, count(api, "conv-restart"), "policy plus node restart on 3.13: survives 4.4")
        assertEquals(0, count(api, "stays-v1"), "never converted: lost on 4.4")
    }

    @Test
    fun `a forced kill of the 3_13 node does not change the outcome, it is the index version and not the way the node stopped`() {
        val volume = newVolume()
        val (old, oldApi) = start(OLD, volume)
        // Not seed(): the kill below must meet a seed that is on disk, or the control measures the timing of the kill.
        seedBeforeKill(old, oldApi, mapOf("k-v1" to """{"x-queue-version":1}""", "k-v2" to """{"x-queue-version":2}"""))
        kill(old)

        // Control: the same 3.13 image recovers both queues after the kill.
        val (again, againApi) = start(OLD, volume)
        waitForCount(againApi, "k-v1", 3)
        waitForCount(againApi, "k-v2", 3)
        kill(again)

        val (_, api) = start(BrokerImages.CURRENT_4X, volume)
        assertEquals(3, count(api, "k-v2"), "version 2 survives a kill and the upgrade")
        assertEquals(0, count(api, "k-v1"), "version 1 is lost on 4.4 with a kill just as with a graceful stop")
    }
}
