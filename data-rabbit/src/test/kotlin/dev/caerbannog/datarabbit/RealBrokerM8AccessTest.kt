package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.json.SecretValue
import dev.caerbannog.core.migrate3x.DefinitionsParseResult
import dev.caerbannog.core.migrate3x.DefinitionsParser
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.FailureKind
import dev.caerbannog.core.migrations.InMemoryMigrationHistoryRepository
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.streams.UnavailableStreamManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Network
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.Collections

/**
 * M8-12c live proof (step 4 runs it): the REAL executor against a 3.13 source and a 4.x target on one network. A hash exported from 3.13 lets the user log in on
 * 4.x; a rerun is a no-op; a differing hash and a differing vhost are conflicts and nothing is overwritten; `/` is never touched; a non-administrator is refused
 * by the runner before any request. Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8Access*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM8AccessTest {

    companion object {
        private val network: Network = Network.newNetwork()
        private val old: RabbitMQContainer = broker("rabbitmq:3.13-management").withNetwork(network).withNetworkAliases("old")
        private val new: RabbitMQContainer = broker("rabbitmq:4-management").withNetwork(network).withNetworkAliases("new")
        private lateinit var o: MgmtApi
        private lateinit var n: MgmtApi

        @JvmStatic @BeforeAll fun start() { old.start(); new.start(); o = MgmtApi(old); n = MgmtApi(new) }
        @JvmStatic @AfterAll fun stop() { new.stop(); old.stop(); network.close() }
    }

    private val sent: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun executor(): ManagementTopologyMigrationExecutor {
        val recorder = createClientPlugin("m8-12c-recorder") { onRequest { request, _ -> sent += "${request.method.value} ${request.url.build().encodedPath}" } }
        return ManagementTopologyMigrationExecutor.overClient(
            HttpClient(Java) { expectSuccess = false; install(recorder) }, "http://${new.host}:${new.getMappedPort(15672)}", "guest", "guest", UnavailableStreamManager,
        ) { }
    }

    private fun run(e: ManagementTopologyMigrationExecutor, actions: List<MigrationAction>) = actions.map { runBlocking { e.execute(it) } }

    /** The access actions of one user of a real 3.13 export: what the generator of M8-12b will write, built here from the parsed definitions. */
    private fun actionsFor(user: String, vhost: String): List<MigrationAction> {
        val document = (DefinitionsParser.parse(o.get("/api/definitions").body) as DefinitionsParseResult.Parsed).document
        val u = document.users.single { it.name == user }
        return listOf(
            MigrationAction.DeclareVhost(vhost),
            MigrationAction.DeclareUser(u.name, u.passwordHash!!, u.hashingAlgorithm!!, u.tags),
            MigrationAction.SetPermissions(vhost, user, ".*", ".*", ".*"),
        )
    }

    private fun source(user: String, password: String, vhost: String) {
        o.put("/api/vhosts/$vhost", "{}"); o.put("/api/users/$user", """{"password":"$password","tags":"management"}""")
        o.put("/api/permissions/$vhost/$user", """{"configure":".*","write":".*","read":".*"}""")
    }

    private fun clean(user: String, vhost: String) {
        n.delete("/api/users/$user"); n.delete("/api/vhosts/$vhost"); o.delete("/api/users/$user"); o.delete("/api/vhosts/$vhost")
    }

    @Test
    fun `a real hash from 3x lets the user log in on 4x with the old password, and a second apply writes nothing`() {
        val user = "m812c-svc"; val vhost = "m812c-v"
        try {
            source(user, "old-password", vhost)
            val actions = actionsFor(user, vhost)
            assertTrue(run(executor(), actions).all { it == ActionResult.Success }, "first apply")
            assertEquals(200, n.get("/api/whoami", user, "old-password").status, "the user cannot log in on 4.x with the old password")
            sent.clear()
            assertTrue(run(executor(), actions).all { it == ActionResult.Success }, "second apply")
            assertTrue(sent.none { it.startsWith("PUT") || it.startsWith("DELETE") }, "the rerun wrote: $sent")
        } finally { clean(user, vhost) }
    }

    @Test
    fun `a different hash on 4x is a conflict and the stored hash is unchanged`() {
        val user = "m812c-diff"; val vhost = "m812c-d"
        try {
            source(user, "old-password", vhost)
            n.put("/api/vhosts/$vhost", "{}"); n.put("/api/users/$user", """{"password":"another-password","tags":"management"}""")
            val results = run(executor(), actionsFor(user, vhost))
            val conflict = results[1] as? ActionResult.Failed ?: error("expected a conflict, got ${results[1]}")
            assertEquals(FailureKind.Conflict, conflict.kind)
            assertEquals(200, n.get("/api/whoami", user, "another-password").status, "the hash was overwritten")
            assertEquals(401, n.get("/api/whoami", user, "old-password").status)
        } finally { clean(user, vhost) }
    }

    @Test
    fun `an existing vhost with another description is a conflict, equal or uncarried settings are a no-op, and the default vhost is never touched`() {
        val vhost = "m812c-desc"
        try {
            n.put("/api/vhosts/$vhost", """{"description":"Something else"}""")
            sent.clear()
            val e = executor()
            assertEquals(FailureKind.Conflict, (runBlocking { e.execute(MigrationAction.DeclareVhost(vhost, "Orders")) } as ActionResult.Failed).kind)
            assertEquals("Something else", n.get("/api/vhosts/$vhost").obj["description"]?.toString()?.trim('"'))
            assertEquals(ActionResult.Success, runBlocking { e.execute(MigrationAction.DeclareVhost(vhost, "Something else")) })
            assertEquals(ActionResult.Success, runBlocking { e.execute(MigrationAction.DeclareVhost(vhost)) })
            sent.clear()
            assertEquals(ActionResult.Success, runBlocking { e.execute(MigrationAction.DeclareVhost("/", "Production")) })
            assertEquals(emptyList<String>(), sent, "the default virtual host caused a request")
        } finally { n.delete("/api/vhosts/$vhost") }
    }

    @Test
    fun `a user without the administrator tag is refused by the runner before any request`() {
        sent.clear()
        val file = MigrationFile("0001", "access", listOf(MigrationAction.DeclareUser("x", SecretValue.of("h"), "rabbit_password_hashing_sha256")), "{}", "c")
        val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor())
        val dry = runner.dryRun(file, true, TopologyControlLevel.FULL_CONTROL, "/", setOf("management", "policymaker"))
        assertFalse(dry.canApply)
        val applied = runBlocking { runner.applyPending("new", listOf(file), true, TopologyControlLevel.FULL_CONTROL, "/", setOf("management")) }
        assertTrue(applied.toString().contains("administrator"), applied.toString())
        assertEquals(emptyList<String>(), sent, "a request was sent before the runner refused")
        assertTrue(MigrationParser.computeChecksum("x").isNotEmpty())
    }
}
