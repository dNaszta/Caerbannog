package dev.caerbannog.core.migrate3x

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M8-01: the measured RabbitMQ 3.13.7 export (S-A), entity by entity. */
class DefinitionsParserFixtureTest {
    private val doc = fullFixture()

    private fun queue(name: String) = doc.queues.single { it.name == name }

    @Test
    fun `counts and origin of the full export`() {
        assertEquals(DefinitionsOrigin.FullExport, doc.origin)
        assertEquals("3.13.7", doc.sourceVersion)
        assertEquals(2, doc.virtualHosts.size)
        assertEquals(13, doc.queues.size)
        assertEquals(4, doc.exchanges.size)
        assertEquals(3, doc.bindings.size)
        assertEquals(3, doc.policies.size)
        assertEquals(1, doc.operatorPolicies.size)
        assertEquals(4, doc.users.size)
        assertEquals(4, doc.permissions.size)
        assertEquals(1, doc.topicPermissions.size)
    }

    @Test
    fun `default virtual host has its description and no tags`() {
        val root = doc.virtualHosts.single { it.name == "/" }
        assertEquals("Default virtual host", root.description)
        assertEquals(emptyList<String>(), root.tags)
        assertNull(root.defaultQueueType)
    }

    @Test
    fun `classic-ttl arguments are typed`() {
        val args = queue("classic-ttl").arguments
        assertEquals(ArgumentValue.Whole(3600000), args["x-expires"])
        assertEquals(ArgumentValue.Whole(60000), args["x-message-ttl"])
        assertEquals(ArgumentValue.Whole(1000), args["x-max-length"])
        assertEquals(ArgumentValue.Text("reject-publish"), args["x-overflow"])
    }

    @Test
    fun `classic-sac single active consumer is a flag`() {
        assertEquals(ArgumentValue.Flag(true), queue("classic-sac").arguments["x-single-active-consumer"])
    }

    @Test
    fun `every queue is classic except quorum-q`() {
        doc.queues.forEach { q ->
            val expected = if (q.name == "quorum-q") QueueKind.Quorum else QueueKind.Classic
            assertEquals(expected, q.type, "type of queue ${q.name}")
        }
        assertEquals(ArgumentValue.Whole(5), queue("classic-prio").arguments["x-max-priority"])
        assertFalse(queue("classic-transient").durable)
        assertTrue(queue("classic-autodel").autoDelete)
        assertEquals("v1", queue("v1-q").vhost)
    }

    @Test
    fun `exchanges keep durability, auto-delete and arguments`() {
        val delayed = doc.exchanges.single { it.name == "ex-delayed" }
        assertFalse(delayed.durable)
        assertTrue(delayed.autoDelete)
        assertEquals("topic", delayed.type)
        assertEquals(ArgumentValue.Text("dlx"), doc.exchanges.single { it.name == "ex-ae" }.arguments["alternate-exchange"])
    }

    @Test
    fun `exchange to exchange binding is kept`() {
        val e2e = doc.bindings.single { it.source == "ex-e2e" }
        assertEquals("dlx", e2e.destination)
        assertEquals(BindingTarget.Exchange, e2e.destinationKind)
        assertEquals("e2e", e2e.routingKey)
        val toQueue = doc.bindings.single { it.source == "ex-ae" }
        assertEquals(BindingTarget.Queue, toQueue.destinationKind)
        assertEquals(ArgumentValue.Text("y"), toQueue.arguments["x"])
    }

    @Test
    fun `policy definition is typed`() {
        val p = doc.policies.single { it.name == "ha-exactly" }
        assertEquals(ArgumentValue.Whole(2), p.definition["ha-params"])
        assertEquals("^classic-plain$", p.pattern)
        assertEquals("queues", p.applyTo)
        assertEquals(2, p.priority)
    }

    @Test
    fun `operator policy comes from parameters and not from other parameters`() {
        val op = doc.operatorPolicies.single()
        assertEquals("op-limit", op.name)
        assertEquals("/", op.vhost)
        assertEquals(".*", op.pattern)
        assertEquals("queues", op.applyTo)
        assertEquals(ArgumentValue.Whole(100000), op.definition["max-length"])
        assertTrue(doc.otherParameters.none { it.name == "op-limit" || it.component == "operator_policy" })
    }

    @Test
    fun `federation upstream and global parameters are listed by component and name only`() {
        assertEquals(UnparsedParameter("/", "federation-upstream", "up1"), doc.otherParameters.single { it.name == "up1" })
        assertEquals(UnparsedParameter(null, "global", "cluster_name"), doc.otherParameters.single { it.name == "cluster_name" })
        assertNotNull(doc.otherParameters.singleOrNull { it.name == "internal_cluster_id" && it.component == "global" && it.vhost == null })
        assertEquals(3, doc.otherParameters.size)
    }

    @Test
    fun `users keep tags, algorithm and a hash that is not readable by accident`() {
        val guest = doc.users.single { it.name == "guest" }
        assertEquals(listOf("administrator"), guest.tags)
        val svc = doc.users.single { it.name == "svc" }
        assertEquals(listOf("management", "policymaker"), svc.tags)
        assertEquals("rabbit_password_hashing_sha256", svc.hashingAlgorithm)
        assertNotNull(svc.passwordHash)
        assertEquals(emptyMap<String, ArgumentValue>(), svc.limits)
    }

    @Test
    fun `permissions and topic permissions are read`() {
        assertEquals(PermissionDefinition("/", "svc", "^svc-.*", ".*", ".*"), doc.permissions.first { it.user == "svc" && it.vhost == "/" })
        assertEquals(TopicPermissionDefinition("/", "svc", "ex-delayed", "^a.*", "^b.*"), doc.topicPermissions.single())
    }

    @Test
    fun `two parses give independent secret instances with equal content`() {
        val a = doc.users.single { it.name == "svc" }.passwordHash!!
        val b = fullFixture().users.single { it.name == "svc" }.passwordHash!!
        assertTrue(a.matches(b))
        assertFalse(a == b)
    }
}
