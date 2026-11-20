package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.LiveAnalysisOutcome
import dev.caerbannog.core.migrate3x.LiveDefinitionsSource
import dev.caerbannog.core.migrate3x.analyseLive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.caerbannog.core.migrate3x.DefinitionsParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M8-13 spike S-R (docs/tasks/M8-00-spike-results.md, section "S-R"): what a CLUSTERED RabbitMQ 3.13 returns for
 * `GET /api/definitions` and `GET /api/queues` when classic queues are mirrored for real, and whether the product's
 * definitions parser copes. Two nodes (`rabbit@rabbit1`, `rabbit@rabbit2`) on a Docker network, joined with `rabbitmqctl join_cluster`.
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8SpikeClusterTest*'`.
 */
@Tag("broker")
@Tag("spike")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8SpikeClusterTest {

    companion object {
        private const val COOKIE = "caerbannog-spike-cookie"
        private val network: Network = Network.newNetwork()

        private fun node(host: String): RabbitMQContainer = broker("rabbitmq:3.13-management")
            .withNetwork(network).withNetworkAliases(host).withEnv("RABBITMQ_ERLANG_COOKIE", COOKIE)
            .withCreateContainerCmdModifier { it.withHostName(host) }

        private val one = node("rabbit1")
        private val two = node("rabbit2")
        private lateinit var a: MgmtApi

        @JvmStatic @BeforeAll fun start() {
            one.start(); two.start()
            for (cmd in listOf(listOf("stop_app"), listOf("join_cluster", "rabbit@rabbit1"), listOf("start_app"))) {
                val r = two.execInContainer("rabbitmqctl", *cmd.toTypedArray())
                check(r.exitCode == 0) { "rabbitmqctl ${cmd.joinToString(" ")} on node 2: ${r.stdout} ${r.stderr}" }
            }
            a = MgmtApi(one)
            awaitUntil("two running nodes in /api/nodes", 90) { a.get("/api/nodes").let { it.ok && it.arr.size == 2 && it.arr.all { n -> n.jsonObject["running"]?.jsonPrimitive?.content == "true" } } }
        }

        @JvmStatic @AfterAll fun stop() { two.stop(); one.stop(); network.close() }
    }

    private fun keys(o: JsonObject) = o.keys.sorted()

    @Test
    fun `S-R a two node 3_13 cluster with mirrored classic queues, export shape and parser`() {
        for ((pattern, name, def) in listOf(
            Triple("^m-all", "ha-all", """{"ha-mode":"all","ha-sync-mode":"automatic"}"""),
            Triple("^m-exactly", "ha-exactly", """{"ha-mode":"exactly","ha-params":2,"ha-sync-mode":"automatic"}"""),
            Triple("^m-nodes", "ha-nodes", """{"ha-mode":"nodes","ha-params":["rabbit@rabbit1","rabbit@rabbit2"]}"""),
        )) assertTrue(a.put("/api/policies/%2F/$name", """{"pattern":"$pattern","apply-to":"queues","priority":1,"definition":$def}""").ok)
        for (q in listOf("m-all", "m-exactly", "m-nodes", "plain")) {
            assertTrue(a.declareQueue(q).ok, q)
            repeat(3) { a.publish(q, "$q-$it") }
        }
        assertTrue(a.declareQueue("m-all-on-2", """{}""").ok)
        assertTrue(a.put("/api/queues/%2F/pinned-on-2", """{"durable":true,"auto_delete":false,"arguments":{},"node":"rabbit@rabbit2"}""").ok)
        assertTrue(a.put("/api/queues/%2F/q-quorum", """{"durable":true,"auto_delete":false,"arguments":{"x-queue-type":"quorum"}}""").ok)
        awaitUntil("mirrors synchronised on m-all", 90) {
            a.get("/api/queues/%2F/m-all").obj["synchronised_slave_nodes"]?.jsonArray?.isNotEmpty() == true
        }
        awaitUntil("statistics for every queue", 60) { a.get("/api/queues/%2F").arr.size >= 7 && a.get("/api/queues/%2F").arr.all { (it as JsonObject)["storage_version"] != null || (it as JsonObject)["type"]?.jsonPrimitive?.content == "quorum" } }

        val overview = a.get("/api/overview").obj
        println("S-R overview rabbitmq_version=${overview["rabbitmq_version"]} cluster_name=${overview["cluster_name"]}")
        val nodes = a.get("/api/nodes").arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        println("S-R nodes: $nodes")

        val live = a.get("/api/queues/%2F").arr.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        for ((name, q) in live) {
            println("S-R /api/queues $name: node=${q["node"]} type=${q["type"]} storage_version=${q["storage_version"]} policy=${q["policy"]} " +
                "slave_nodes=${q["slave_nodes"]} synchronised_slave_nodes=${q["synchronised_slave_nodes"]} mirror_pids=${q["mirror_pids"]} " +
                "members=${q["members"]} online=${q["online"]} leader=${q["leader"]} effective_policy_definition=${q["effective_policy_definition"]} messages=${q["messages"]}")
        }
        println("S-R /api/queues key set of m-all: ${keys(live["m-all"]!!)}")

        val export = a.get("/api/definitions")
        assertTrue(export.ok, export.toString())
        val def = export.obj
        println("S-R definitions top-level keys: ${keys(def)}")
        println("S-R definitions rabbit_version=${def["rabbit_version"]} rabbitmq_version=${def["rabbitmq_version"]}")
        for (q in def["queues"]!!.jsonArray.map { it.jsonObject }) println("S-R definitions queue ${q["name"]}: ${q.toString()}")
        for (p in def["policies"]!!.jsonArray) println("S-R definitions policy: $p")
        println("S-R definitions global_parameters: ${def["global_parameters"]}")
        println("S-R definitions node/mirror mentions in the whole body: ${Regex("rabbit@rabbit[12]").findAll(export.body).count()} x node names, 'slave' ${Regex("slave").findAll(export.body).count()}, 'mirror' ${Regex("mirror").findAll(export.body).count()}")

        val parsed = assertInstanceOf(DefinitionsParseResult.Parsed::class.java, DefinitionsParser.parse(export.body), "the product's parser on a clustered export")
        val names = parsed.document.queues.map { it.name }.toSet()
        println("S-R parser queues: $names; policies: ${parsed.document.policies.map { it.name }}")
        assertTrue(names.containsAll(setOf("m-all", "m-exactly", "m-nodes", "plain", "m-all-on-2", "pinned-on-2", "q-quorum")), "every queue of the cluster is read: $names")
    }

    private class Passwords : SecretStore {
        override fun store(profileName: String, password: String) = Unit
        override fun retrieve(profileName: String): String = "guest"
        override fun delete(profileName: String) = Unit
    }

    @Test
    fun `S-R the app's live analysis of node 1 of the cluster reads storage versions of every classic queue and flags the mirrored ones`() {
        val profile = ConnectionProfile(
            name = "cluster", host = one.host, virtualHost = "/", username = "guest",
            managementPort = one.getMappedPort(15672), streamPort = closedLocalPort(), tls = false,
        )
        for (q in listOf("la-m-all", "la-plain")) assertTrue(a.declareQueue(q).ok)
        assertTrue(a.put("/api/policies/%2F/ha-la", """{"pattern":"^la-m-","apply-to":"queues","priority":1,"definition":{"ha-mode":"all"}}""").ok)
        val opened = runBlocking { withTimeout(60_000) { RabbitMqConnectionGateway(Passwords()).open(profile) } }
        val connection = assertInstanceOf(OpenResult.Opened::class.java, opened, "opening node 1: $opened").connection
        try {
            val reads = assertInstanceOf(ManagementThreeXReads::class.java, connection.threeXReads)
            val outcome = runBlocking { withTimeout(120_000) { analyseLive(LiveDefinitionsSource("cluster", connection.brokerVersion, reads) { delay(it) }) } }
            val held = assertInstanceOf(LiveAnalysisOutcome.Analysed::class.java, outcome, "live analysis of a cluster node: $outcome").held
            val findings = held.report.findings
            println("S-R live analysis findings: ${findings.map { "${it.kind} ${it.name} ${it.rule}" }}")
            assertTrue(findings.any { it.name == "la-m-all" && it.rule == "classic-not-replicated" }, "mirrored queue is flagged")
            assertTrue(findings.any { it.rule == "mirroring-in-use" }, "mirroring flagged from the deprecated feature list on a cluster")
        } finally {
            connection.close()
        }
    }
}
