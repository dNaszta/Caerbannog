package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.BrokerFinding
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.fakes.FakeManagementBroker
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M9-05 step 4 (test developer): hostile names, odd virtual hosts and huge or deep bodies through the real check. */
class ManagementDeclareCheckExploratoryTest {
    private val seen = mutableListOf<M508a.Seen>()
    private val names = listOf("<b>x</b>", "x".repeat(200), "‮evil", "a/b", "100%25", "a b", "q?x=1", "q#f", "naïve", "%", "..a", "get", "a+b", "~")

    private fun find(action: MigrationAction, engine: io.ktor.client.engine.mock.MockEngine = M508a.engineOver(FakeManagementBroker(), seen)): BrokerFinding =
        runBlocking { ManagementDeclareCheck(ResourceReader(M508a.http(engine), M508a.requests())).find(action) }

    private fun allowedGetsOnly() = seen.forEach {
        assertEquals("GET", it.method)
        assertTrue(ExecutorPathAllowList.isAllowed("GET", it.path), "not an allowed GET: ${it.path}")
    }

    @Test
    fun `hostile names of every kind of resource only produce allowed GETs`() {
        for (n in names) {
            seen.clear()
            listOf(
                MigrationAction.DeclareExchange(n), MigrationAction.DeclarePolicy(n, "^x"), MigrationAction.DeclareOperatorPolicy(n, "^x"), MigrationAction.DeclareStream(n),
                MigrationAction.DeclareBinding(n, n, n), MigrationAction.DeclareBinding(n, n, n, destinationType = BindingDestinationType.EXCHANGE),
                MigrationAction.DeleteBinding(n, n, n), MigrationAction.DeleteQueue(n), MigrationAction.DeclareQueue(n, vhost = n),
            ).forEach { find(it) }
            allowedGetsOnly()
        }
    }

    @Test
    fun `an odd virtual host is encoded into one segment`() {
        for (v in listOf("/", "/orders", "a/b", "100%", "sp ace", "")) {
            seen.clear()
            find(MigrationAction.DeclareQueue("q", vhost = v))
            allowedGetsOnly()
            seen.forEach { assertEquals(3, it.path.removePrefix("/api/").split('/').size, "vhost [$v] split the path: ${it.path}") }
        }
    }

    @Test
    fun `a huge body and a deeply nested body give a finding and no crash`() {
        val huge = """{"type":"quorum","durable":true,"auto_delete":false,"arguments":{"pad":"${"x".repeat(2_000_000)}"}}"""
        val deep = "[".repeat(5000) + "]".repeat(5000)
        val manyBindings = "[" + (1..20000).joinToString(",") { """{"routing_key":"k$it","arguments":{}}""" } + "]"
        for (body in listOf(huge, deep, "{\"a\":".repeat(5000))) {
            val found = find(MigrationAction.DeclareQueue("q"), M508a.answering(200, body))
            assertFalse(found == BrokerFinding.Absent, "$found")
        }
        assertEquals(BrokerFinding.Same, find(MigrationAction.DeclareBinding("e", "q", "k20000"), M508a.answering(200, manyBindings)))
    }
}
