package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * M2-12 (docs/tasks/M2-12-local-dev-environment.md, C9): argument parsing of the seed tool, tests first.
 * Contract proposed by the test developer (the architect may change it before the code is written):
 *   SeedArgs.parse(args: List<String>): SeedArgs, flags of the form `--name value`, throws SeedArgsException whose
 *   message names the offending flag. Only `--password` is required (a default password in code would be a secret).
 *   Defaults: host localhost, stream port 5551, management port 15671, user admin, vhost "/", messages 1000,
 *   queues 0 (the many-queues set is only generated when asked: `--queues 1000 --vhost many`), no CA path (JVM
 *   trust), no retention change, a non-empty default stream list.
 *   Flags: --host --stream-port --management-port --user --password --ca --vhost --streams a,b --messages N
 *   --queues N --max-age <n><Y|M|D|h|m|s> (retention change, RabbitMQ's notation such as 7D).
 * The password never appears in toString() (C8: the tool does not log it).
 */
class SeedArgsTest {
    private fun parse(vararg args: String) = SeedArgs.parse(args.toList())

    @Test
    fun `only the password is needed and everything else has a default`() {
        val a = parse("--password", "dev-pw")
        assertEquals("localhost", a.host)
        assertEquals(5551, a.streamPort)
        assertEquals(15671, a.managementPort)
        assertEquals("admin", a.user)
        assertEquals("dev-pw", a.password)
        assertNull(a.caPath)
        assertEquals("/", a.vhost)
        assertEquals(1000, a.messages)
        assertEquals(0, a.queues)
        assertNull(a.retentionMaxAge)
        assertTrue(a.streams.isNotEmpty(), "default stream list must not be empty")
    }

    @Test
    fun `every flag is read`() {
        val a = parse(
            "--host", "broker.test", "--stream-port", "6551", "--management-port", "16671", "--user", "alice",
            "--password", "pw", "--ca", "/tmp/ca.pem", "--vhost", "many", "--streams", "s1,s2,s3",
            "--messages", "25", "--queues", "1000", "--max-age", "7D",
        )
        assertEquals("broker.test", a.host)
        assertEquals(6551, a.streamPort)
        assertEquals(16671, a.managementPort)
        assertEquals("alice", a.user)
        assertEquals("pw", a.password)
        assertEquals("/tmp/ca.pem", a.caPath)
        assertEquals("many", a.vhost)
        assertEquals(listOf("s1", "s2", "s3"), a.streams)
        assertEquals(25, a.messages)
        assertEquals(1000, a.queues)
        assertEquals("7D", a.retentionMaxAge)
    }

    @Test
    fun `flag order does not matter`() {
        assertEquals(
            parse("--password", "pw", "--messages", "5", "--vhost", "v"),
            parse("--vhost", "v", "--messages", "5", "--password", "pw"),
        )
    }

    @Test
    fun `zero messages and zero queues are allowed`() {
        val a = parse("--password", "pw", "--messages", "0", "--queues", "0")
        assertEquals(0, a.messages)
        assertEquals(0, a.queues)
    }

    @Test
    fun `a single stream name gives a one element list and blanks around names are trimmed`() {
        assertEquals(listOf("only"), parse("--password", "pw", "--streams", "only").streams)
        assertEquals(listOf("a", "b"), parse("--password", "pw", "--streams", "a, b").streams)
    }

    @Test
    fun `the password is required`() {
        val e = assertFailsWith<SeedArgsException> { parse("--user", "admin") }
        assertTrue(e.message!!.contains("--password"), "message should name the flag: ${e.message}")
    }

    @Test
    fun `an unknown flag is refused and named`() {
        val e = assertFailsWith<SeedArgsException> { parse("--password", "pw", "--bogus", "1") }
        assertTrue(e.message!!.contains("--bogus"), "message should name the flag: ${e.message}")
    }

    @Test
    fun `a flag without a value is refused and named`() {
        val e = assertFailsWith<SeedArgsException> { parse("--password", "pw", "--messages") }
        assertTrue(e.message!!.contains("--messages"), "message should name the flag: ${e.message}")
    }

    @Test
    fun `a value that is not a number is refused and named`() {
        for (flag in listOf("--messages", "--queues", "--stream-port", "--management-port")) {
            val e = assertFailsWith<SeedArgsException>(flag) { parse("--password", "pw", flag, "ten") }
            assertTrue(e.message!!.contains(flag), "message should name $flag: ${e.message}")
        }
    }

    @Test
    fun `negative counts are refused`() {
        for (flag in listOf("--messages", "--queues")) {
            val e = assertFailsWith<SeedArgsException>(flag) { parse("--password", "pw", flag, "-1") }
            assertTrue(e.message!!.contains(flag), "message should name $flag: ${e.message}")
        }
    }

    @Test
    fun `ports outside 1 to 65535 are refused`() {
        for (flag in listOf("--stream-port", "--management-port")) {
            for (bad in listOf("0", "65536", "-5")) {
                assertFailsWith<SeedArgsException>("$flag $bad") { parse("--password", "pw", flag, bad) }
            }
        }
        assertEquals(65535, parse("--password", "pw", "--stream-port", "65535").streamPort)
        assertEquals(1, parse("--password", "pw", "--management-port", "1").managementPort)
    }

    @Test
    fun `an empty stream list or an empty stream name is refused`() {
        assertFailsWith<SeedArgsException> { parse("--password", "pw", "--streams", "") }
        assertFailsWith<SeedArgsException> { parse("--password", "pw", "--streams", "a,,b") }
    }

    @Test
    fun `max age accepts RabbitMQ notation only`() {
        for (ok in listOf("7D", "1h", "30m", "45s", "2M", "1Y")) {
            assertEquals(ok, parse("--password", "pw", "--max-age", ok).retentionMaxAge)
        }
        for (bad in listOf("7", "D", "7d days", "7x", "-1D", "")) {
            assertFailsWith<SeedArgsException>("--max-age '$bad'") { parse("--password", "pw", "--max-age", bad) }
        }
    }

    @Test
    fun `the password does not appear in toString`() {
        val a = parse("--password", "s3cret-dev-value")
        assertFalse(a.toString().contains("s3cret-dev-value"), "toString leaked the password: $a")
    }

    // M2-12b: --max-age is silently ignored with --queues N on another vhost, so the mix is rejected.

    @Test
    fun `max-age with queues on another vhost is rejected and names both flags`() {
        val e = assertFailsWith<SeedArgsException> {
            parse("--password", "pw", "--max-age", "7D", "--queues", "1000", "--vhost", "many")
        }
        val message = e.message.orEmpty()
        assertTrue("--max-age" in message, "message must name --max-age, was: $message")
        assertTrue("--queues" in message, "message must name --queues, was: $message")
    }

    @Test
    fun `max-age with queues on the default vhost is allowed`() {
        val a = parse("--password", "pw", "--max-age", "7D", "--queues", "10", "--vhost", "/")
        assertEquals("7D", a.retentionMaxAge)
        assertEquals(10, a.queues)
    }

    @Test
    fun `max-age with queues on the default vhost by default is allowed`() {
        assertEquals("7D", parse("--password", "pw", "--max-age", "7D", "--queues", "10").retentionMaxAge)
    }

    @Test
    fun `max-age without queues on another vhost is allowed`() {
        val a = parse("--password", "pw", "--max-age", "7D", "--vhost", "many")
        assertEquals("7D", a.retentionMaxAge)
        assertEquals("many", a.vhost)
    }

    @Test
    fun `max-age with queues zero on another vhost is allowed`() {
        assertEquals("7D", parse("--password", "pw", "--max-age", "7D", "--queues", "0", "--vhost", "many").retentionMaxAge)
    }

    @Test
    fun `queues on another vhost without max-age is still allowed`() {
        assertEquals(1000, parse("--password", "pw", "--queues", "1000", "--vhost", "many").queues)
    }
}
