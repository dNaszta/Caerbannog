package dev.caerbannog.core.access

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M10-02 step 2 (test developer): the contract of `SecretGuard` (spec "Access view (M10)", "Redaction rules" 1 to 6; spike M10-00 S-N). Pure, no
 * broker, no network. The shapes come from the redaction fixtures of S-N (`fixtures/m10-00/s-n-*`): a JWT-shaped `connection_name` and
 * `user_provided_name`, a URI with credentials, a client property `password`. The real JWT of the fixture is a placeholder (`<JWT>`), so the tests
 * build a JWT-shaped text themselves.
 *
 * Not pinned on purpose: a held secret shorter than [SecretGuard.MIN_HELD_SEGMENT] characters (the spec says "longer than a few characters"; whether a
 * 4-character password hides every value that contains it is a question for the architect, see the test report).
 */
class SecretGuardTest {
    private val jwt = "eyJhbGciOiJSUzI1NiIsImtpZCI6Imsx.eyJzdWIiOiJ1c2VyIiwic2NvcGUiOlsicmFiYml0bXEucmVhZDoqLyoiXX0.c2lnbmF0dXJlLWJ5dGVzLWhlcmU"
    private val held = "S3cret-PW-4711-xyz"

    private fun hiddenText(value: String, secret: String? = null) = SecretGuard.redact(value, secret)

    // ---------------------------------------------------------------- shapes

    @Test
    fun `a JWT-shaped value is hidden whole`() {
        val r = hiddenText(jwt)
        assertTrue(r.hidden, "a JWT must be hidden")
        assertEquals(SecretGuard.HIDDEN, r.text)
    }

    @Test
    fun `a value that contains a JWT among other text is hidden whole, not only the match`() {
        val value = "client-of-team-a $jwt (renewed)"
        val r = hiddenText(value)
        assertEquals(SecretGuard.HIDDEN, r.text, "the whole value is replaced, not the match")
        assertTrue(r.hidden)
    }

    @Test
    fun `the Bearer prefix hides the value, in either case`() {
        assertEquals(SecretGuard.HIDDEN, hiddenText("Bearer abc123").text)
        assertEquals(SecretGuard.HIDDEN, hiddenText("bearer abc123").text)
        assertEquals("Bearer-service", hiddenText("Bearer-service").text, "no space: it is a name, not a header value")
    }

    @Test
    fun `a URI with a user and a password is hidden, a URI without credentials is kept`() {
        assertEquals(SecretGuard.HIDDEN, hiddenText("amqps://spike-user:spike-pw@broker.example:5671/vhost").text)
        assertEquals(SecretGuard.HIDDEN, hiddenText("see https://u:p@host/x for details").text)
        assertEquals("amqps://broker.example:5671/vhost", hiddenText("amqps://broker.example:5671/vhost").text)
    }

    @Test
    fun `password equals, secret equals and token equals hide the value, in any case`() {
        for (shape in listOf("password=hunter2", "PASSWORD=hunter2", "x;secret=abc", "Secret=abc", "a&token=abc", "TOKEN=abc")) {
            assertEquals(SecretGuard.HIDDEN, hiddenText(shape).text, "\"$shape\" must be hidden")
        }
    }

    @Test
    fun `ordinary values are kept as they are, including near misses`() {
        val kept = listOf(
            "orders-service", "192.168.65.1:25971 -> 172.17.0.3:5672", "/orders", "RabbitMQ", "Java", "5.37.0", "",
            "plain-name-with-password-property", "my password", "client-token", "token", "secret santa",
            "eyJ", "a.b.c", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0",
        )
        for (value in kept) {
            val r = hiddenText(value)
            assertEquals(value, r.text, "\"$value\" is no secret shape and must stay")
            assertFalse(r.hidden, "\"$value\" must not count as hidden")
        }
    }

    // ---------------------------------------------------------------- held password

    @Test
    fun `a value that is the held password or contains it is hidden`() {
        assertEquals(SecretGuard.HIDDEN, hiddenText(held, held).text)
        assertEquals(SecretGuard.HIDDEN, hiddenText("conn-$held-1", held).text)
    }

    @Test
    fun `a value that contains a segment of the held password of 7 characters or more is hidden`() {
        val segment = held.substring(0, SecretGuard.MIN_HELD_SEGMENT)
        assertEquals(SecretGuard.HIDDEN, hiddenText("svc-$segment!", held).text)
        assertEquals(SecretGuard.HIDDEN, hiddenText(held.substring(5, 5 + SecretGuard.MIN_HELD_SEGMENT), held).text)
    }

    @Test
    fun `a held secret of 6 characters hides only a value equal to it, case-sensitive`() {
        val short = "Ab3-xZ"
        assertEquals(SecretGuard.HIDDEN, hiddenText(short, short).text)
        assertEquals("ab3-xz", hiddenText("ab3-xz", short).text, "case-sensitive")
        assertEquals("svc-Ab3-xZ-1", hiddenText("svc-Ab3-xZ-1", short).text, "inside longer text only the shape scan applies")
        assertEquals("Ab3-xZ ", hiddenText("Ab3-xZ ", short).text, "equal means equal, not trimmed")
    }

    @Test
    fun `a held secret of 7 characters hides a value that contains it`() {
        val seven = "Ab3-xZq"
        assertEquals(SecretGuard.HIDDEN, hiddenText(seven, seven).text)
        assertEquals(SecretGuard.HIDDEN, hiddenText("conn-Ab3-xZq-1", seven).text)
        assertEquals("conn-ab3-xzq-1", hiddenText("conn-ab3-xzq-1", seven).text, "case-sensitive")
    }

    @Test
    fun `without a held password, or with an empty one, nothing is hidden by the held scan`() {
        assertEquals("orders", hiddenText("orders", null).text)
        assertEquals("orders", hiddenText("orders", "").text, "an empty secret must not match every value")
        assertEquals("", hiddenText("", "").text)
    }

    @Test
    fun `the guard never stores the secret it is given`() {
        val instanceFields = SecretGuard::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        assertEquals(emptyList(), instanceFields.map { it.name }, "the guard must be stateless: the holder passes the secret in for each scan")
        // A static field could still hold it: after a scan, no static field may contain the secret.
        SecretGuard.redact("conn-$held", held)
        for (field in SecretGuard::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) }) {
            field.isAccessible = true
            assertFalse(field.get(null)?.toString().orEmpty().contains(held), "static field ${field.name} holds the secret")
        }
    }

    @Test
    fun `no output of the guard contains the held password or a segment of 7 characters or more of it (random values, fixed seed)`() {
        val random = Random(20261125)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._ /:"
        fun noise(): String = String(CharArray(random.nextInt(0, 12)) { alphabet[random.nextInt(alphabet.length)] })
        repeat(300) { round ->
            val secret = String(CharArray(random.nextInt(12, 33)) { alphabet[random.nextInt(alphabet.length - 5)] })
            val from = random.nextInt(0, secret.length - SecretGuard.MIN_HELD_SEGMENT)
            val to = random.nextInt(from + SecretGuard.MIN_HELD_SEGMENT, secret.length + 1)
            val value = noise() + secret.substring(from, to) + noise()

            val outputs = buildList {
                add(SecretGuard.redact(value, secret).text)
                addAll(SecretGuard.redactAll(listOf(value, noise()), secret).values)
                addAll(SecretGuard.guardConnection(buildJsonObject { put("user_provided_name", value); put("user", value) }, secret).fields.values)
            }
            for (out in outputs) {
                for (i in 0..secret.length - SecretGuard.MIN_HELD_SEGMENT) {
                    val piece = secret.substring(i, i + SecretGuard.MIN_HELD_SEGMENT)
                    assertFalse(out.contains(piece), "round $round: output \"$out\" still holds the segment \"$piece\" of the secret")
                }
            }
        }
    }

    // ---------------------------------------------------------------- counter

    @Test
    fun `a list is redacted value by value, order kept, and the counter says how many were hidden`() {
        val r = SecretGuard.redactAll(listOf("a", "Bearer x", "b", jwt, "password=1"))
        assertEquals(listOf("a", SecretGuard.HIDDEN, "b", SecretGuard.HIDDEN, SecretGuard.HIDDEN), r.values)
        assertEquals(3, r.hidden)
        assertEquals(0, SecretGuard.redactAll(listOf("a", "b")).hidden)
        assertEquals(GuardedTexts(emptyList(), 0), SecretGuard.redactAll(emptyList()))
    }

    // ---------------------------------------------------------------- allow-list (connections)

    private fun connectionRecord(
        connectionName: String = "orders-service",
        userProvidedName: String? = "orders-service",
        user: String = "svc-orders",
    ): JsonObject = buildJsonObject {
        put("auth_mechanism", "PLAIN")
        put("channel_max", 256)
        put("channels", 2)
        putJsonObject("client_properties") {
            putJsonObject("capabilities") { put("basic.nack", true) }
            put("connection_name", connectionName)
            put("copyright", "Copyright (c) 2007-2024 Broadcom Inc.")
            put("information", "Licensed under the MPL.")
            put("note", "free text of the client")
            put("password", "hunter2-from-the-client")
            put("platform", "Java")
            put("product", "RabbitMQ")
            put("version", "5.37.0")
        }
        put("connected_at", 1795598146143L)
        put("frame_max", 131072)
        putJsonObject("garbage_collection") { put("minor_gcs", 9) }
        put("host", "172.17.0.3")
        put("name", "192.168.65.1:25971 -> 172.17.0.3:5672")
        put("node", "rabbit@978a34e8b9df")
        put("peer_cert_issuer", JsonNull)
        put("peer_host", "192.168.65.1")
        put("peer_port", 25971)
        put("port", 5672)
        put("protocol", "AMQP 0-9-1")
        put("recv_oct", 539)
        put("ssl", false)
        put("ssl_cipher", JsonNull)
        put("state", "running")
        put("timeout", 60)
        put("type", "network")
        put("user", user)
        if (userProvidedName != null) put("user_provided_name", userProvidedName)
        put("user_who_performed_action", "guest")
        put("vhost", "/")
    }

    @Test
    fun `a connection keeps only the allow-listed fields, as text, and drops everything else (spike S-N record shape)`() {
        val r = SecretGuard.guardConnection(connectionRecord())
        val expected = mapOf(
            "name" to "192.168.65.1:25971 -> 172.17.0.3:5672",
            "user" to "svc-orders",
            "vhost" to "/",
            "node" to "rabbit@978a34e8b9df",
            "state" to "running",
            "protocol" to "AMQP 0-9-1",
            "peer_host" to "192.168.65.1",
            "peer_port" to "25971",
            "connected_at" to "1795598146143",
            "channels" to "2",
            "auth_mechanism" to "PLAIN",
            "ssl" to "false",
            "user_provided_name" to "orders-service",
            "client_properties.product" to "RabbitMQ",
            "client_properties.version" to "5.37.0",
            "client_properties.platform" to "Java",
            "client_properties.connection_name" to "orders-service",
        )
        assertEquals(expected, r.fields, "only the allow-list stays; nothing else, masked or not")
        assertEquals(0, r.hidden, "dropped fields are not counted as hidden")
    }

    @Test
    fun `a client property password and every unlisted client property are dropped, not masked`() {
        val r = SecretGuard.guardConnection(connectionRecord())
        assertEquals("RabbitMQ", r.fields["client_properties.product"], "the listed client properties stay")
        assertFalse(r.fields.keys.any { it.startsWith("client_properties.") && it !in ALLOWED_CLIENT_PROPERTIES }, "unlisted client property kept: ${r.fields.keys}")
        assertFalse(r.fields.values.any { it.contains("hunter2") }, "the client's password text came through")
        assertFalse("client_properties.password" in r.fields, "dropped means absent, not \"hidden\"")
    }

    @Test
    fun `connection_name and user_provided_name are scanned alike, each hidden whole and counted`() {
        val r = SecretGuard.guardConnection(connectionRecord(connectionName = jwt, userProvidedName = jwt))
        assertEquals(SecretGuard.HIDDEN, r.fields["client_properties.connection_name"])
        assertEquals(SecretGuard.HIDDEN, r.fields["user_provided_name"], "user_provided_name echoes client text verbatim (S-N): scanned like connection_name")
        assertEquals(2, r.hidden)

        val uri = SecretGuard.guardConnection(connectionRecord(connectionName = "amqps://spike-user:spike-pw@broker.example:5671/vhost", userProvidedName = null))
        assertEquals(SecretGuard.HIDDEN, uri.fields["client_properties.connection_name"])
        assertEquals(1, uri.hidden)
    }

    @Test
    fun `a clean record has no hidden value`() {
        assertEquals(0, SecretGuard.guardConnection(connectionRecord()).hidden)
    }

    @Test
    fun `the held password in any kept field hides that value`() {
        val r = SecretGuard.guardConnection(connectionRecord(user = held), held)
        assertEquals(SecretGuard.HIDDEN, r.fields["user"])
        assertEquals(1, r.hidden)
        assertFalse(r.fields.values.any { it.contains(held) })
    }

    @Test
    fun `a null or non-scalar value of an allow-listed field is dropped`() {
        val r = SecretGuard.guardConnection(
            buildJsonObject {
                put("name", "c1")
                put("user", JsonNull)
                putJsonObject("state") { put("x", "y") }
                put("peer_port", JsonPrimitive(7))
            },
        )
        assertEquals(mapOf("name" to "c1", "peer_port" to "7"), r.fields)
    }

    // ---------------------------------------------------------------- allow-list (channels)

    @Test
    fun `a channel keeps only its allow-listed fields and scans them`() {
        val record = buildJsonObject {
            put("name", "192.168.65.1:25971 -> 172.17.0.3:5672 (1)")
            put("user", "svc-orders")
            put("vhost", "/orders")
            put("node", "rabbit@n1")
            put("state", "running")
            put("number", 1)
            put("prefetch_count", 10)
            put("user_provided_name", "password=hunter2")
            putJsonObject("connection_details") {
                put("name", "192.168.65.1:25971 -> 172.17.0.3:5672")
                put("peer_host", "192.168.65.1")
                put("peer_port", 25971)
                put("user", "svc-orders")
            }
            putJsonObject("garbage_collection") { put("minor_gcs", 3) }
        }
        val r = SecretGuard.guardChannel(record)
        assertEquals("svc-orders", r.fields["user"])
        assertEquals("/orders", r.fields["vhost"])
        assertEquals("192.168.65.1", r.fields["connection_details.peer_host"])
        assertFalse("prefetch_count" in r.fields, "unlisted field kept")
        assertFalse("garbage_collection" in r.fields)
        assertFalse(r.fields.values.any { it.contains("hunter2") }, "a secret-shaped client text came through")
        val shown = r.fields["user_provided_name"]
        assertTrue(shown == null || shown == SecretGuard.HIDDEN, "a secret-shaped channel text must be dropped or hidden, was \"$shown\"")
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun `a known broker reason maps to a fixed text and an unmapped one to the generic text, never echoed`() {
        assertEquals(SecretGuard.REASON_NO_MANAGEMENT_TAG, SecretGuard.brokerErrorText("Not management user"))
        assertEquals(SecretGuard.REASON_REFUSED, SecretGuard.brokerErrorText("Not_Authorized"))
        val unmapped = "bad token $jwt for password=hunter2"
        val text = SecretGuard.brokerErrorText(unmapped)
        assertEquals(SecretGuard.GENERIC_ERROR, text)
        assertFalse(text.contains("hunter2") || text.contains("eyJ"), "the broker text was echoed")
        assertEquals(SecretGuard.GENERIC_ERROR, SecretGuard.brokerErrorText(null))
        assertEquals(SecretGuard.GENERIC_ERROR, SecretGuard.brokerErrorText(""))
    }

    // ---------------------------------------------------------------- never logs

    @Test
    fun `the guard prints and logs nothing, whatever it is given`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            SecretGuard.redact(jwt, held)
            SecretGuard.redact("conn-$held", held)
            SecretGuard.redactAll(listOf(jwt, "Bearer x", held), held)
            SecretGuard.guardConnection(connectionRecord(connectionName = jwt, userProvidedName = held), held)
            SecretGuard.guardChannel(connectionRecord(), held)
            SecretGuard.brokerErrorText("bad token $jwt")
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals("", out.toString(), "the guard wrote to standard output")
        assertEquals("", err.toString(), "the guard wrote to standard error (a logger would)")
    }

    private companion object {
        val ALLOWED_CLIENT_PROPERTIES = setOf(
            "client_properties.product", "client_properties.version", "client_properties.platform", "client_properties.connection_name",
        )
    }
}
