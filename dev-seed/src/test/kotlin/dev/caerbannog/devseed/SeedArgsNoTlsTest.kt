package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * M2-14b (docs/tasks/M2-14-plain-connections.md), tests first. Contract proposed by the test developer:
 *   `--no-tls` is a switch WITHOUT a value (the other flags are `--name value` pairs). SeedArgs.tls is true by default.
 *   With --no-tls: plain stream and management; the ports default to 5552 and 15672 when not given (explicit ports win);
 *   --ca is ignored (caPath null) because the Taskfile always passes it. Without it everything stays as before (TLS,
 *   5551 and 15671, --ca kept). The flag may appear anywhere, also last, and twice.
 */
class SeedArgsNoTlsTest {
    private fun parse(vararg args: String) = SeedArgs.parse(args.toList())

    @Test
    fun `TLS is the default and keeps the TLS ports and the CA`() {
        val a = parse("--password", "pw", "--ca", "/tmp/ca.pem")
        assertTrue(a.tls)
        assertEquals(5551, a.streamPort)
        assertEquals(15671, a.managementPort)
        assertEquals("/tmp/ca.pem", a.caPath)
    }

    @Test
    fun `no-tls switches TLS off and moves the default ports to the plain ones`() {
        val a = parse("--password", "pw", "--no-tls")
        assertFalse(a.tls)
        assertEquals(5552, a.streamPort)
        assertEquals(15672, a.managementPort)
    }

    @Test
    fun `no-tls is a switch without a value and may come first, last or in the middle`() {
        for (args in listOf(
            listOf("--no-tls", "--password", "pw", "--messages", "5"),
            listOf("--password", "pw", "--no-tls", "--messages", "5"),
            listOf("--password", "pw", "--messages", "5", "--no-tls"),
        )) {
            val a = SeedArgs.parse(args)
            assertFalse(a.tls, "tls for $args")
            assertEquals("pw", a.password)
            assertEquals(5, a.messages)
        }
    }

    @Test
    fun `no-tls given twice is fine`() {
        assertFalse(parse("--password", "pw", "--no-tls", "--no-tls").tls)
    }

    @Test
    fun `explicit ports win over the plain defaults`() {
        val a = parse("--password", "pw", "--no-tls", "--stream-port", "6552", "--management-port", "16672")
        assertEquals(6552, a.streamPort)
        assertEquals(16672, a.managementPort)
    }

    @Test
    fun `an explicit port equal to the TLS default is still honoured with no-tls`() {
        val a = parse("--password", "pw", "--no-tls", "--stream-port", "5551")
        assertEquals(5551, a.streamPort)
        assertEquals(15672, a.managementPort, "the other port still follows the switch")
    }

    @Test
    fun `only one explicit port leaves the other at the mode default`() {
        val a = parse("--password", "pw", "--management-port", "16671")
        assertEquals(5551, a.streamPort)
        assertEquals(16671, a.managementPort)
    }

    @Test
    fun `the CA is ignored with no-tls whatever the flag order`() {
        assertNull(parse("--password", "pw", "--ca", "data-rabbit/ca.pem", "--no-tls").caPath)
        assertNull(parse("--no-tls", "--password", "pw", "--ca", "data-rabbit/ca.pem").caPath)
    }

    @Test
    fun `the other flags are unchanged with no-tls`() {
        val a = parse("--password", "pw", "--no-tls", "--host", "h", "--user", "u", "--vhost", "v", "--streams", "a,b")
        assertEquals("h", a.host)
        assertEquals("u", a.user)
        assertEquals("v", a.vhost)
        assertEquals(listOf("a", "b"), a.streams)
    }

    @Test
    fun `the password is still required with no-tls`() {
        val e = assertFailsWith<SeedArgsException> { parse("--no-tls") }
        assertTrue(e.message!!.contains("--password"), e.message)
    }

    @Test
    fun `a value after no-tls is read as the next flag, not as the switch value`() {
        val e = assertFailsWith<SeedArgsException> { parse("--password", "pw", "--no-tls", "false") }
        assertTrue(e.message!!.contains("false"), "message should name the stray token: ${e.message}")
    }

    @Test
    fun `an invalid port is still refused with no-tls`() {
        assertFailsWith<SeedArgsException> { parse("--password", "pw", "--no-tls", "--stream-port", "0") }
    }

    @Test
    fun `no-tls does not put the password in toString and toString says plain`() {
        val a = parse("--password", "s3cret-dev-value", "--no-tls")
        assertFalse(a.toString().contains("s3cret-dev-value"), "toString leaked the password: $a")
        assertTrue(a.toString().contains("tls=false"), "toString should show tls=false: $a")
    }

    // Failure hint for a plain/TLS mismatch: a TLS client against the plain port gets a message-less failure, as a
    // wrong CA does. With TLS on the existing CA hint stays; with --no-tls the --ca hint would mislead.

    @Test
    fun `with no-tls an empty failure gets a hint that names the TLS mismatch, not the CA`() {
        val hint = caHint(null, tls = false)
        assertTrue(hint != null, "an empty failure over plain must still give a hint")
        assertTrue("--no-tls" in hint!!, "hint must name --no-tls so the developer can see the mode, was: $hint")
        assertFalse("--ca" in hint, "the CA is ignored with --no-tls, the hint must not send the developer to it: $hint")
        assertFalse("pw" in hint.split(" "), "no password in the hint")
    }

    @Test
    fun `with TLS on the CA hint is unchanged`() {
        assertEquals(caHint(null), caHint(null, tls = true))
        assertTrue("--ca" in caHint(null, tls = true)!!)
    }

    @Test
    fun `a real failure message gets no hint over plain either`() {
        assertNull(caHint("Connection refused", tls = false))
    }
}
