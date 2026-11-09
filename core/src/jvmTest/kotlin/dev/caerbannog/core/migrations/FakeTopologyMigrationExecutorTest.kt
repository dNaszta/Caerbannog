package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyCatalog
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-04: the fake executor holds what the new fields say, so scenarios can assert typed values. */
class FakeTopologyMigrationExecutorTest {

    private val catalog = FakeTopologyCatalog()
    private val executor = FakeTopologyMigrationExecutor(catalog, FakeStreamCatalog())

    private fun run(vararg actions: MigrationAction) = runBlocking { actions.forEach { assertEquals(ActionResult.Success, executor.execute(it)) } }

    @Test
    fun `exchange flags land in the fake catalog`() {
        run(MigrationAction.DeclareExchange("events.x", "topic", durable = false, autoDelete = true, internal = true))
        val row = (runBlocking { catalog.exchanges("/") } as CatalogResult.Success).value.single { it.name == "events.x" }
        assertEquals(Triple(false, true, true), Triple(row.durable, row.autoDelete, row.internal))
    }

    @Test
    fun `a binding keeps its destination type in the fake catalog`() {
        run(MigrationAction.DeclareBinding("ex-e2e", "dlx", "dead", destinationType = BindingDestinationType.EXCHANGE))
        val row = (runBlocking { catalog.bindings("/") } as CatalogResult.Success).value.single()
        assertEquals(BindingDestinationType.EXCHANGE, row.destinationType)
        assertEquals("dlx", row.destination)
    }

    @Test
    fun `declared queues, policies and operator policies are recorded by vhost and name with typed values`() {
        val queue = MigrationAction.DeclareQueue("orders", "quorum", vhost = "v1", arguments = mapOf("x-delivery-limit" to ArgumentValue.Whole(5)))
        val policy = MigrationAction.DeclarePolicy("limits", "^o", mapOf("max-length" to ArgumentValue.Whole(1000)), "v1", "quorum_queues", 7)
        val operator = MigrationAction.DeclareOperatorPolicy("op", ".*", mapOf("max-length" to ArgumentValue.Whole(100000)), "v1")
        run(queue, policy, operator)
        assertEquals(queue, executor.declaredQueues["v1" to "orders"])
        assertEquals(policy, executor.declaredPolicies["v1" to "limits"])
        assertEquals(operator, executor.declaredOperatorPolicies["v1" to "op"])
        assertNull(executor.declaredQueues["/" to "orders"], "keyed by vhost too")
        assertEquals(ArgumentValue.Whole(1000), executor.declaredPolicies.getValue("v1" to "limits").definition["max-length"])
        assertEquals(listOf<MigrationAction>(queue, policy, operator), executor.executedActions)
    }

    @Test
    fun `deleting a policy removes it and a failed action records nothing`() {
        run(MigrationAction.DeclarePolicy("p", ".*"))
        run(MigrationAction.DeletePolicy("p"))
        assertTrue(executor.declaredPolicies.isEmpty())
        executor.failingActionName = "declareOperatorPolicy"
        val result = runBlocking { executor.execute(MigrationAction.DeclareOperatorPolicy("op", ".*")) }
        assertTrue(result is ActionResult.Failed)
        assertTrue(executor.declaredOperatorPolicies.isEmpty())
    }

    @Test
    fun `clear empties the recorded declarations`() {
        run(MigrationAction.DeclareQueue("q"), MigrationAction.DeclarePolicy("p", ".*"), MigrationAction.DeclareOperatorPolicy("o", ".*"))
        executor.clear()
        assertTrue(executor.declaredQueues.isEmpty() && executor.declaredPolicies.isEmpty() && executor.declaredOperatorPolicies.isEmpty())
    }
}
