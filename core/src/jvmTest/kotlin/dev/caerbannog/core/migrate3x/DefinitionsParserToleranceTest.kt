package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.json.ArgumentValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefinitionsParserToleranceTest {

    private fun argument(valueJson: String): ArgumentValue =
        parsedDocument("""{"queues":[{"name":"q","vhost":"/","arguments":{"x":$valueJson}}]}""").queues.single().arguments.getValue("x")

    @Test
    fun `unknown keys at every level are ignored`() {
        val doc = parsedDocument(
            """{"product_name":"RabbitMQ","future_section":[1,2],"rabbit_version":"3.13.7",
               "vhosts":[{"name":"/","metadata":{"description":"d","tags":[],"future":1},"extra":true}],
               "queues":[{"name":"q","vhost":"/","brand_new":{"a":1}}]}""",
        )
        assertEquals(1, doc.queues.size)
        assertEquals("d", doc.virtualHosts.single().description)
    }

    @Test
    fun `missing optional keys take the documented defaults`() {
        val doc = parsedDocument(
            """{"queues":[{"name":"q","vhost":"/"}],
               "exchanges":[{"name":"e","vhost":"/","type":"direct"}],
               "bindings":[{"source":"e","destination":"q","destination_type":"queue","vhost":"/"}],
               "policies":[{"name":"p","pattern":".*","vhost":"/"}],
               "vhosts":[{"name":"/"}],
               "users":[{"name":"u"}]}""",
        )
        val q = doc.queues.single()
        assertTrue(q.durable)
        assertEquals(false, q.autoDelete)
        assertEquals(emptyMap<String, ArgumentValue>(), q.arguments)
        val e = doc.exchanges.single()
        assertEquals(true, e.durable)
        assertEquals(false, e.autoDelete)
        assertEquals(false, e.internal)
        assertEquals("", doc.bindings.single().routingKey)
        val p = doc.policies.single()
        assertEquals("all", p.applyTo)
        assertEquals(0, p.priority)
        assertEquals(emptyMap<String, ArgumentValue>(), p.definition)
        val v = doc.virtualHosts.single()
        assertEquals("", v.description)
        assertEquals(emptyList<String>(), v.tags)
        assertNull(v.defaultQueueType)
        val u = doc.users.single()
        assertNull(u.passwordHash)
        assertNull(u.hashingAlgorithm)
        assertEquals(emptyList<String>(), u.tags)
        assertEquals(emptyMap<String, ArgumentValue>(), u.limits)
    }

    @Test
    fun `missing null and empty sections give empty lists`() {
        val doc = parsedDocument("""{"queues":[], "exchanges":null}""")
        assertEquals(0, doc.queues.size + doc.exchanges.size + doc.bindings.size + doc.policies.size + doc.operatorPolicies.size)
        assertEquals(0, doc.virtualHosts.size + doc.users.size + doc.permissions.size + doc.topicPermissions.size + doc.otherParameters.size)
        assertEquals(DefinitionsOrigin.FullExport, doc.origin)
        assertNull(doc.sourceVersion)
    }

    @Test
    fun `a file with only a version is parsed and empty`() {
        val doc = parsedDocument("""{"rabbit_version":"3.13.7"}""")
        assertEquals("3.13.7", doc.sourceVersion)
        assertEquals(0, doc.queues.size)
    }

    @Test
    fun `source version prefers rabbitmq_version then rabbit_version then product_version`() {
        assertEquals("1", parsedDocument("""{"rabbitmq_version":"1","rabbit_version":"2","product_version":"3"}""").sourceVersion)
        assertEquals("2", parsedDocument("""{"rabbit_version":"2","product_version":"3"}""").sourceVersion)
        assertEquals("3", parsedDocument("""{"product_version":"3","queues":[]}""").sourceVersion)
    }

    @Test
    fun `virtual host metadata wins over top level keys and default queue type is read`() {
        val doc = parsedDocument(
            """{"vhosts":[{"name":"a","description":"top","tags":["t"],"default_queue_type":"classic",
                "metadata":{"description":"meta","tags":["m"],"default_queue_type":"quorum"}},
                {"name":"b","description":"only top","tags":["x","y"],"default_queue_type":"stream"}]}""",
        )
        val a = doc.virtualHosts.single { it.name == "a" }
        assertEquals("meta", a.description)
        assertEquals(listOf("m"), a.tags)
        assertEquals("quorum", a.defaultQueueType)
        val b = doc.virtualHosts.single { it.name == "b" }
        assertEquals("only top", b.description)
        assertEquals(listOf("x", "y"), b.tags)
        assertEquals("stream", b.defaultQueueType)
    }

    @Test
    fun `user tags as a comma separated string are split`() {
        val u = parsedDocument("""{"users":[{"name":"u","tags":"management,policymaker"}]}""").users.single()
        assertEquals(listOf("management", "policymaker"), u.tags)
    }

    @Test
    fun `user hash null or absent is null and any algorithm name is kept`() {
        val users = parsedDocument(
            """{"users":[{"name":"a","password_hash":null,"hashing_algorithm":"my_custom_algo"},{"name":"b","password_hash":"x"}]}""",
        ).users
        assertNull(users.single { it.name == "a" }.passwordHash)
        assertEquals("my_custom_algo", users.single { it.name == "a" }.hashingAlgorithm)
        assertTrue(users.single { it.name == "b" }.passwordHash!!.matches(SecretValue.of("x")))
    }

    @Test
    fun `user limits are typed`() {
        val u = parsedDocument("""{"users":[{"name":"u","limits":{"max-connections":10}}]}""").users.single()
        assertEquals(ArgumentValue.Whole(10), u.limits["max-connections"])
    }

    @Test
    fun `json null is Nothing`() = assertEquals(ArgumentValue.Nothing, argument("null"))

    @Test
    fun `number typing`() {
        assertEquals(ArgumentValue.Whole(5), argument("5"))
        assertEquals(ArgumentValue.Decimal(5.5), argument("5.5"))
        assertEquals(ArgumentValue.Decimal(1000.0), argument("1e3"))
        assertEquals(ArgumentValue.Decimal(5.0), argument("5.0"))
        assertEquals(ArgumentValue.Whole(Long.MAX_VALUE), argument("9223372036854775807"))
        assertEquals(ArgumentValue.Whole(-3), argument("-3"))
        assertEquals(ArgumentValue.Decimal(9.223372036854775808E18), argument("9223372036854775808"))
    }

    @Test
    fun `text and flag`() {
        assertEquals(ArgumentValue.Text("5"), argument("\"5\""))
        assertEquals(ArgumentValue.Flag(false), argument("false"))
    }

    @Test
    fun `nested list and table`() {
        assertEquals(
            ArgumentValue.Items(listOf(ArgumentValue.Whole(1), ArgumentValue.Text("a"), ArgumentValue.Table(mapOf("k" to ArgumentValue.Nothing)))),
            argument("""[1,"a",{"k":null}]"""),
        )
        assertEquals(
            ArgumentValue.Table(mapOf("in" to ArgumentValue.Table(mapOf("l" to ArgumentValue.Items(emptyList()))))),
            argument("""{"in":{"l":[]}}"""),
        )
    }

    @Test
    fun `duplicates are kept as they come`() {
        val doc = parsedDocument("""{"queues":[{"name":"q","vhost":"/"},{"name":"q","vhost":"/"}]}""")
        assertEquals(2, doc.queues.size)
    }

    @Test
    fun `operator policy values are read only through the typed fields`() {
        val doc = parsedDocument(
            """{"parameters":[{"component":"operator_policy","name":"o","vhost":"/","value":{"pattern":"^a","apply-to":"quorum_queues","priority":3,"definition":{"max-length":5},"secret":"SENTINEL-X"}}]}""",
        )
        val op = doc.operatorPolicies.single()
        assertEquals("^a", op.pattern)
        assertEquals("quorum_queues", op.applyTo)
        assertEquals(3, op.priority)
        assertTrue(!doc.toString().contains("SENTINEL-X"))
    }

    @Test
    fun `a 4x export is accepted without a version gate`() {
        val doc = parsedDocument("""{"rabbitmq_version":"4.3.0","queues":[{"name":"q","vhost":"/","type":"quorum"}]}""")
        assertEquals("4.3.0", doc.sourceVersion)
    }
}
