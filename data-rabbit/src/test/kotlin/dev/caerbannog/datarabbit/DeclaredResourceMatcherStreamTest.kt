package dev.caerbannog.datarabbit

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M9-14c step 2b: `DeclaredResourceMatcher.matches(DeclareStream, body)`, the ONE comparison the read-back, the executor's pre-read and the check share.
 * Before the task a stream matched on `type == "stream"` only. Now every DECLARED setting must be on the broker with the same typed value (a text `"7"` is
 * not the number `7`) and nothing more: what the broker added (`balanced`, `x-queue-type`) is ignored, and a step with no settings matches any stream.
 * The body is the `GET /api/queues/{vhost}/{name}` answer; a body that is not that shape does not match and never throws.
 * Measured: the broker shows `x-max-age` as the text as sent (7D) and the two byte keys as numbers (S-14c-1); retention set by a POLICY is NOT in `arguments`.
 */
class DeclaredResourceMatcherStreamTest {

    private fun stream(vararg arguments: Pair<String, ArgumentValue>) = MigrationAction.DeclareStream("audit", "/", arguments.toMap())
    private fun body(arguments: String, type: String = "stream", extra: String = "") =
        """{"name":"audit","vhost":"/","type":"$type","durable":true,"auto_delete":false,"arguments":$arguments$extra}"""

    private val age = "x-max-age" to ArgumentValue.Text("7D")
    private val length = "x-max-length-bytes" to ArgumentValue.Whole(1_000_000_000)
    private val locator = "x-queue-leader-locator" to ArgumentValue.Text("balanced")
    private val shownDefault = """{"x-queue-type":"stream","x-queue-leader-locator":"balanced"}"""

    private fun matches(action: MigrationAction, body: String) = DeclaredResourceMatcher.matches(action, body)

    // ---- no declared settings: any stream ----

    @Test
    fun `a step with no settings matches any stream, with or without settings on the broker`() {
        assertTrue(matches(stream(), body(shownDefault)))
        assertTrue(matches(stream(), body("""{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":5,"x-queue-leader-locator":"client-local"}""")))
        assertTrue(matches(stream(), body("{}")))
    }

    @Test
    fun `a step with no settings still needs a stream, a queue of another type is not one`() {
        for (type in listOf("quorum", "classic")) assertFalse(matches(stream(), body(shownDefault, type)), type)
    }

    // ---- each declared setting ----

    @Test
    fun `a declared retention matches only the same text`() {
        assertTrue(matches(stream(age), body("""{"x-queue-type":"stream","x-max-age":"7D","x-queue-leader-locator":"balanced"}""")))
        assertFalse(matches(stream(age), body("""{"x-queue-type":"stream","x-max-age":"1D"}""")))
        assertFalse(matches(stream(age), body("""{"x-queue-type":"stream","x-max-age":"604800s"}""")), "the matcher compares text: 7D is not 604800s")
        assertFalse(matches(stream(age), body("""{"x-queue-type":"stream","x-max-age":"7d"}""")))
        assertFalse(matches(stream(age), body(shownDefault)), "a declared setting that the broker does not hold is a difference")
    }

    @Test
    fun `each declared byte setting matches only the same number`() {
        for (key in listOf("x-max-length-bytes", "x-stream-max-segment-size-bytes")) {
            val declared = stream(key to ArgumentValue.Whole(1_000_000))
            assertTrue(matches(declared, body("""{"x-queue-type":"stream","$key":1000000}""")), key)
            assertFalse(matches(declared, body("""{"x-queue-type":"stream","$key":2000000}""")), key)
            assertFalse(matches(declared, body(shownDefault)), key)
        }
    }

    @Test
    fun `a declared leader locator matches only the same text, balanced included`() {
        assertTrue(matches(stream(locator), body(shownDefault)))
        assertTrue(matches(stream("x-queue-leader-locator" to ArgumentValue.Text("client-local")), body("""{"x-queue-type":"stream","x-queue-leader-locator":"client-local"}""")))
        assertFalse(matches(stream("x-queue-leader-locator" to ArgumentValue.Text("client-local")), body(shownDefault)))
        assertFalse(matches(stream(locator), body("""{"x-queue-type":"stream"}""")), "declared balanced, broker holds none: a difference, the matcher never assumes the default")
    }

    @Test
    fun `all declared settings must match, one wrong is enough to differ`() {
        val all = stream(age, length, locator)
        assertTrue(matches(all, body("""{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":1000000000,"x-queue-leader-locator":"balanced"}""")))
        assertFalse(matches(all, body("""{"x-queue-type":"stream","x-max-age":"7D","x-max-length-bytes":999,"x-queue-leader-locator":"balanced"}""")))
        assertFalse(matches(all, body("""{"x-queue-type":"stream","x-max-age":"7D","x-queue-leader-locator":"balanced"}""")))
    }

    // ---- types ----

    @Test
    fun `a text 7 is not the number 7 in either direction`() {
        val number = stream("x-max-length-bytes" to ArgumentValue.Whole(7))
        assertTrue(matches(number, body("""{"x-queue-type":"stream","x-max-length-bytes":7}""")))
        assertFalse(matches(number, body("""{"x-queue-type":"stream","x-max-length-bytes":"7"}""")))
        val text = stream("x-max-age" to ArgumentValue.Text("7"))
        assertTrue(matches(text, body("""{"x-queue-type":"stream","x-max-age":"7"}""")))
        assertFalse(matches(text, body("""{"x-queue-type":"stream","x-max-age":7}""")))
    }

    // ---- what the broker adds, and a policy ----

    @Test
    fun `settings the broker added are ignored, balanced on a stream whose step declares only a retention`() {
        assertTrue(matches(stream(age), body("""{"x-queue-type":"stream","x-max-age":"7D","x-queue-leader-locator":"balanced","x-max-length-bytes":5,"x-initial-cluster-size":3}""")))
        assertTrue(matches(stream(length), body("""{"x-queue-type":"stream","x-max-length-bytes":1000000000,"x-queue-leader-locator":"client-local"}""")))
    }

    @Test
    fun `retention set by a policy is not an argument, a declared retention against it is a difference`() {
        val viaPolicy = body(shownDefault, extra = ""","policy":"retain","effective_policy_definition":{"max-age":"7D"}""")
        assertFalse(matches(stream(age), viaPolicy), "the check and the executor never read effective_policy_definition")
        assertTrue(matches(stream(), viaPolicy), "a step with no settings still matches")
    }

    // ---- a body that is not the shape ----

    @Test
    fun `a body that is not a queue detail does not match and never throws`() {
        val declared = stream(age)
        for (bad in listOf("", "null", "[]", "[{\"type\":\"stream\"}]", "\"stream\"", "5", "{", "{\"type\":\"stream\",\"arguments\":[]}", "{\"type\":\"stream\",\"arguments\":null}", "{\"type\":\"stream\",\"arguments\":\"x\"}", "{\"type\":5,\"arguments\":{}}")) {
            assertFalse(matches(declared, bad), "for body: $bad")
        }
        assertFalse(matches(stream(), "[]"))
        assertFalse(matches(stream(), "{"))
    }

    @Test
    fun `arguments nested far too deep do not match and do not overflow the stack`() {
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertFalse(matches(stream(age), body("""{"x-max-age":$deep}""")))
    }
}
