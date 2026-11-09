package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** M8-01 owner decision P17: a per-virtual-host export is accepted as an unassigned draft and the user names the virtual host. */
class DefinitionsParserSingleVhostTest {

    private val vhostV1 = DefinitionsFixtures.read(DefinitionsFixtures.VHOST_V1)

    @Test
    fun `the per-vhost fixture needs a virtual host name and reports its counts`() {
        val draft = needsVhost(vhostV1)
        assertEquals("3.13.7", draft.sourceVersion)
        assertEquals(1, draft.queueCount)
        assertEquals(0, draft.exchangeCount)
        assertEquals(0, draft.bindingCount)
        assertEquals(0, draft.policyCount)
    }

    @Test
    fun `the same entries with vhost keys are a normal full export`() {
        val doc = parsedDocument("""{"rabbit_version":"3.13.7","queues":[{"name":"v1-q","vhost":"v1","arguments":{}}]}""")
        assertEquals(DefinitionsOrigin.FullExport, doc.origin)
    }

    @Test
    fun `a mix of entries with and without vhost is malformed`() {
        assertEquals(DefinitionsRejection.Malformed("queues"), rejection("""{"queues":[{"name":"a","vhost":"/"},{"name":"b"}]}"""))
        assertEquals(
            DefinitionsRejection.Malformed("queues"),
            rejection("""{"queues":[{"name":"a"}],"exchanges":[{"name":"e","type":"direct","vhost":"/"}]}"""),
        )
    }

    @Test
    fun `vhost-less entries plus users, vhosts, permissions or topic permissions are malformed`() {
        val q = """"queues":[{"name":"a"}]"""
        assertTrue(DefinitionsParser.parse("""{$q,"users":[{"name":"u"}]}""") is DefinitionsParseResult.Rejected)
        assertTrue(DefinitionsParser.parse("""{$q,"vhosts":[{"name":"/"}]}""") is DefinitionsParseResult.Rejected)
        assertTrue(
            DefinitionsParser.parse("""{$q,"permissions":[{"user":"u","vhost":"/","configure":".*","read":".*","write":".*"}]}""") is DefinitionsParseResult.Rejected,
        )
        assertTrue(
            DefinitionsParser.parse("""{$q,"topic_permissions":[{"user":"u","vhost":"/","exchange":"e","write":".*","read":".*"}]}""") is DefinitionsParseResult.Rejected,
        )
        assertTrue(rejection("""{$q,"users":[{"name":"u"}]}""") is DefinitionsRejection.Malformed)
    }

    @Test
    fun `all five sections empty is an empty full export`() {
        val doc = parsedDocument("""{"queues":[],"exchanges":[],"bindings":[],"policies":[],"parameters":[],"rabbit_version":"3.13.7"}""")
        assertEquals(DefinitionsOrigin.FullExport, doc.origin)
        assertEquals(0, doc.queues.size)
    }

    @Test
    fun `vhost-less parameters become operator policies and unparsed parameters in the assigned vhost`() {
        val draft = needsVhost(
            """{"parameters":[
                {"component":"operator_policy","name":"op","value":{"pattern":".*","apply-to":"queues","priority":1,"definition":{"max-length":7}}},
                {"component":"shovel","name":"move","value":{"src-uri":"amqp://svc:SENTINEL-PASSWORD@old"}}]}""",
        )
        val doc = assigned(draft, "orders")
        val op = doc.operatorPolicies.single()
        assertEquals("orders", op.vhost)
        assertEquals(ArgumentValue.Whole(7), op.definition["max-length"])
        assertEquals(listOf(UnparsedParameter("orders", "shovel", "move")), doc.otherParameters)
        assertFalse(doc.toString().contains("SENTINEL-PASSWORD"))
    }

    @Test
    fun `assigning puts the name on every entry and leaves the access part empty`() {
        val draft = needsVhost(
            """{"rabbit_version":"3.13.7",
               "queues":[{"name":"q"}],
               "exchanges":[{"name":"e","type":"direct"}],
               "bindings":[{"source":"e","destination":"q","destination_type":"queue"}],
               "policies":[{"name":"p","pattern":".*"}],
               "parameters":[{"component":"operator_policy","name":"op","value":{"pattern":".*"}}]}""",
        )
        val doc = assigned(draft, "orders")
        assertEquals(DefinitionsOrigin.SingleVirtualHost("orders"), doc.origin)
        assertEquals("3.13.7", doc.sourceVersion)
        assertEquals(listOf("orders"), doc.queues.map { it.vhost })
        assertEquals(listOf("orders"), doc.exchanges.map { it.vhost })
        assertEquals(listOf("orders"), doc.bindings.map { it.vhost })
        assertEquals(listOf("orders"), doc.policies.map { it.vhost })
        assertEquals(listOf("orders"), doc.operatorPolicies.map { it.vhost })
        assertEquals(0, doc.virtualHosts.size + doc.users.size + doc.permissions.size + doc.topicPermissions.size)
    }

    @Test
    fun `the per-vhost fixture assigned to orders holds v1-q in orders`() {
        val doc = assigned(needsVhost(vhostV1), "orders")
        assertEquals("orders", doc.queues.single { it.name == "v1-q" }.vhost)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "a\u0000b", "\t", "\n", "a\tb", "x\u007F"])
    fun `a name that cannot be used is refused and does not echo the name`(name: String) {
        val draft = needsVhost(vhostV1)
        val result = draft.assignVirtualHost(name)
        assertTrue(result is VirtualHostAssignment.InvalidName, "name ${name.length} chars gave $result")
        val reason = (result as VirtualHostAssignment.InvalidName).reason
        assertTrue(reason.isNotBlank())
        if (name.isNotBlank()) assertFalse(reason.contains(name))
        // the draft stays usable
        assertTrue(draft.assignVirtualHost("orders") is VirtualHostAssignment.Assigned)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/", "orders-eu", "my vhost", " padded ", "café-日本"])
    fun `names without control characters are accepted as they are`(name: String) {
        val doc = assigned(needsVhost(vhostV1), name)
        assertEquals(DefinitionsOrigin.SingleVirtualHost(name), doc.origin)
        assertEquals(name, doc.queues.single().vhost)
    }

    @Test
    fun `a 300 character name is accepted because no length rule is invented`() {
        val name = "v".repeat(300)
        assertEquals(name, assigned(needsVhost(vhostV1), name).queues.single().vhost)
    }

    @Test
    fun `the draft exposes no entity list`() {
        // Java reflection on the public getters (core has no kotlin-reflect).
        val getters = UnassignedDefinitions::class.java.methods.filter { it.name.startsWith("get") && it.parameterCount == 0 && it.declaringClass != Any::class.java }
        val bad = getters.filter { Collection::class.java.isAssignableFrom(it.returnType) || it.returnType.name.startsWith("dev.caerbannog.core.migrate3x") }
        assertTrue(bad.isEmpty(), "UnassignedDefinitions exposes ${bad.map { it.name }}")
        assertTrue(getters.isNotEmpty())
    }

    @Test
    fun `two assignments of one draft give independent documents with equal content`() {
        val draft = needsVhost(vhostV1)
        val a = assigned(draft, "orders")
        val b = assigned(draft, "orders")
        assertNotSame(a, b)
        assertEquals(a, b)
        assertEquals("other", assigned(draft, "other").queues.single().vhost)
        assertEquals("orders", a.queues.single().vhost)
    }
}
