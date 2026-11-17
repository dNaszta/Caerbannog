package dev.caerbannog.core.streams

import dev.caerbannog.core.json.ArgumentValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M9-08 slice 1 (docs/tasks/M9-08-policy-step.md, "Slice 1"), written first: the ONE pure rule that says which retention a stream has when it comes
 * from its own arguments and from the policy that applies to it. Contract (package `dev.caerbannog.core.streams`, commonMain):
 *   object RetentionSources {
 *       val policyWinsOverArgument: Set<String>     // the one named constant; today setOf("max-length-bytes")
 *       fun merge(arguments: Retention, policyDefinition: Map<String, ArgumentValue>): Retention
 *   }
 * Measured: S-F1d (a policy's retention is in `effective_policy_definition`, not in `arguments`) and S-14c-4 (for `max-length-bytes` the policy decides in
 * both directions and the argument keeps showing its declared value). NOT measured: `max-age` and `stream-max-segment-size-bytes` when both are present;
 * the argument stays (today's behaviour), kept in the one constant and stated by the row below, so the day a live row measures them it is a one-line change.
 */
class RetentionSourcesTest {

    private fun text(v: String) = ArgumentValue.Text(v)
    private fun whole(v: Long) = ArgumentValue.Whole(v)

    @Test
    fun `arguments only stay as they are`() {
        val own = Retention(maxAge = "1D", maxLengthBytes = 500, segmentSizeBytes = 100)
        assertEquals(own, RetentionSources.merge(own, emptyMap()))
    }

    @Test
    fun `a policy alone fills all three fields`() {
        val policy = mapOf("max-age" to text("7D"), "max-length-bytes" to whole(1_000_000), "stream-max-segment-size-bytes" to whole(50_000_000))
        assertEquals(Retention("7D", 1_000_000, 50_000_000), RetentionSources.merge(Retention(), policy))
    }

    @Test
    fun `neither arguments nor policy is no retention`() {
        assertEquals(Retention(), RetentionSources.merge(Retention(), emptyMap()))
    }

    @Test
    fun `when both hold max-length-bytes the policy wins`() {
        assertEquals(1_000_000L, RetentionSources.merge(Retention(maxLengthBytes = 500), mapOf("max-length-bytes" to whole(1_000_000))).maxLengthBytes)
        // both directions (S-14c-4): a smaller policy value wins over a larger argument too
        assertEquals(500L, RetentionSources.merge(Retention(maxLengthBytes = 10_000_000_000), mapOf("max-length-bytes" to whole(500))).maxLengthBytes)
    }

    @Test
    fun `a policy value of zero is a value and wins`() {
        assertEquals(0L, RetentionSources.merge(Retention(maxLengthBytes = 500), mapOf("max-length-bytes" to whole(0))).maxLengthBytes)
    }

    @Test
    fun `when both hold max-age the argument stays (not measured, stated)`() {
        assertEquals("1D", RetentionSources.merge(Retention(maxAge = "1D"), mapOf("max-age" to text("7D"))).maxAge)
    }

    @Test
    fun `when both hold the segment size the argument stays (not measured, stated)`() {
        val merged = RetentionSources.merge(Retention(segmentSizeBytes = 100), mapOf("stream-max-segment-size-bytes" to whole(999)))
        assertEquals(100L, merged.segmentSizeBytes)
    }

    @Test
    fun `the precedence is one named constant, so a later measurement is a one-line change`() {
        assertEquals(setOf("max-length-bytes"), RetentionSources.policyWinsOverArgument)
    }

    @Test
    fun `each field takes its own source, a policy fills only what the arguments lack`() {
        val own = Retention(maxAge = "1D")
        val policy = mapOf("max-age" to text("7D"), "max-length-bytes" to whole(2_000))
        assertEquals(Retention(maxAge = "1D", maxLengthBytes = 2_000), RetentionSources.merge(own, policy))
    }

    @Test
    fun `a policy key that is not a retention key is ignored`() {
        val policy = mapOf("max-length" to whole(10), "message-ttl" to whole(60_000), "queue-leader-locator" to text("balanced"), "x-max-age" to text("7D"))
        assertEquals(Retention(), RetentionSources.merge(Retention(), policy))
    }

    @Test
    fun `a value of the wrong type is ignored, never guessed`() {
        val own = Retention(maxAge = "1D", maxLengthBytes = 5, segmentSizeBytes = 6)
        val wrong = mapOf(
            "max-age" to whole(7), // a JSON number is no age
            "max-length-bytes" to text("1000"), // a number as text is no size
            "stream-max-segment-size-bytes" to ArgumentValue.Flag(true),
        )
        assertEquals(own, RetentionSources.merge(own, wrong), "wrong-typed policy values leave the arguments as they are")
        assertEquals(Retention(), RetentionSources.merge(Retention(), wrong), "and fill nothing")
        assertEquals(Retention(), RetentionSources.merge(Retention(), mapOf("max-length-bytes" to ArgumentValue.Decimal(1000.5), "max-age" to ArgumentValue.Nothing)))
        assertEquals(Retention(), RetentionSources.merge(Retention(), mapOf("max-age" to ArgumentValue.Table(mapOf("a" to text("b"))), "max-length-bytes" to ArgumentValue.Items(listOf(whole(1))))))
    }

    @Test
    fun `a wrong-typed policy size does not hide the argument for max-length-bytes`() {
        assertEquals(500L, RetentionSources.merge(Retention(maxLengthBytes = 500), mapOf("max-length-bytes" to text("big"))).maxLengthBytes)
    }
}
