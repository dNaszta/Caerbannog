package dev.caerbannog.core.forwarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8-07: the typed source password never leaves the credentials; the address has no password field; the scheme follows the profile switch only (D9a). */
class ShovelRedactionTest {
    private val s = Fwd.SENTINEL

    @Test
    fun `credentials print stars in toString and in a string template`() {
        val c = Fwd.creds()
        assertEquals("ShovelSourceCredentials(mover, ***)", c.toString())
        assertFalse("$c".contains(s))
        assertEquals(s, c.passwordForRequest(), "the one reader still gives the password to the request writer")
    }

    @Test
    fun `credentials have no copy, no component function and no content equality`() {
        val methods = ShovelSourceCredentials::class.java.methods.map { it.name }.toSet()
        assertFalse("copy" in methods, "a copy function can carry the password around")
        assertFalse(methods.any { it.startsWith("component") }, "a component function reads the password")
        assertEquals(Any::class.java, ShovelSourceCredentials::class.java.getMethod("equals", Any::class.java).declaringClass, "content equality would be a password oracle")
    }

    @Test
    fun `the address has no password field and prints no secret`() {
        val fields = ShovelAddress::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { "pass" in it || "secret" in it }, "fields: $fields")
        assertFalse(ShovelAddress::class.java.methods.any { "pass" in it.name.lowercase() }, "a password accessor on ShovelAddress")
    }

    @Test
    fun `the address of a typed user reads amqps user at host port and the encoded vhost`() {
        val address = ShovelAddress.forProfile(Fwd.profile(), credentials = Fwd.creds())
        assertEquals("amqps://mover@legacy.example:5671/%2F", address.toString())
        assertFalse(address.toString().contains(s))
    }

    @Test
    fun `request, source and copy of the request print stars and never the password`() {
        val request = Fwd.request(credentials = Fwd.creds())
        assertTrue(request.toString().contains("***"), request.toString())
        assertFalse(request.toString().contains(s))
        assertFalse(request.copy(destinationQueue = "other").toString().contains(s))
        assertFalse(request.source.copy(queue = "x").toString().contains(s))
        assertFalse("${request.source}".contains(s))
    }

    @Test
    fun `with TLS on the scheme is amqps and the default port 5671, with TLS off amqp and 5672, no fallback`() {
        val tls = ShovelAddress.forProfile(Fwd.profile(tls = true))
        assertEquals(listOf("amqps", 5671, true), listOf(tls.scheme, tls.port, tls.tls))
        val plain = ShovelAddress.forProfile(Fwd.profile(tls = false))
        assertEquals(listOf("amqp", 5672, false), listOf(plain.scheme, plain.port, plain.tls))
    }

    @Test
    fun `the dialog may change host and port, and the typed user replaces the profile user`() {
        val a = ShovelAddress.forProfile(Fwd.profile(), host = "amqp.legacy.example", port = 6000, credentials = Fwd.creds(user = "other"))
        assertEquals("amqps://other@amqp.legacy.example:6000/%2F", a.toString())
        assertEquals("profile-user", ShovelAddress.forProfile(Fwd.profile(), credentials = null).username, "the default is the profile user")
    }

    @Test
    fun `a vhost other than slash is percent-encoded as it is sent`() {
        assertEquals("amqps://u@h:5671/my%20vhost%2Fx", ShovelAddress(true, "u", "h", 5671, "my vhost/x").toString())
    }
}
