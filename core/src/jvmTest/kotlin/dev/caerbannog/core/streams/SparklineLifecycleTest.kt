package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M7-05 lifecycle of the rolling history: bounded, pruned when an entity vanishes, dropped when the connection changes. */
class SparklineLifecycleTest {

    @Test
    fun `retainKeys drops the history of keys that are gone and clear drops everything`() {
        val h = RollingMetricHistory()
        for (t in 0..1) { h.record("a", 10L * (t + 1), t * 1000L); h.record("b", 5L * (t + 1), t * 1000L) }
        assertEquals(setOf("a", "b"), h.snapshot(listOf("a", "b"), SparklineSemantic.Velocity).keys)

        h.retainKeys(setOf("a"))
        assertEquals(setOf("a"), h.snapshot(listOf("a", "b"), SparklineSemantic.Velocity).keys)

        h.clear()
        assertTrue(h.snapshot(listOf("a", "b"), SparklineSemantic.Velocity).isEmpty())
    }

    @Test
    fun `a null value is not recorded and does not create a key`() {
        val h = RollingMetricHistory()
        h.record("a", null, 0L)
        h.record("a", null, 1000L)
        assertTrue(h.snapshot(listOf("a"), SparklineSemantic.Velocity).isEmpty())
    }

    @Test
    fun `the ring buffer keeps only the newest samples`() {
        val h = RollingMetricHistory(maxSamples = 3)
        for (t in 1..6) h.record("a", t * 10L, t * 1000L)
        assertEquals(listOf(40L, 50L, 60L), h.snapshot(listOf("a"), SparklineSemantic.Velocity).getValue("a").samples)
    }

    @Test
    fun `an unchanged value is a steady trend and a lower one is draining`() {
        val h = RollingMetricHistory()
        h.record("a", 100L, 0L); h.record("a", 100L, 1000L)
        assertEquals(TrendDirection.Steady, h.snapshot(listOf("a"), SparklineSemantic.ConsumerLag).getValue("a").direction)
        h.record("a", 40L, 2000L)
        assertEquals(TrendDirection.Draining, h.snapshot(listOf("a"), SparklineSemantic.ConsumerLag).getValue("a").direction)
    }

    @Test
    fun `a stream that vanishes from the broker loses its sparkline, the others keep theirs`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("a", messages = 100L)
        s.addStream("b", messages = 100L)
        s.open()
        s.addStream("a", messages = 150L)
        s.addStream("b", messages = 130L)
        s.pass(5)
        assertNotNull(s.vm.state.value.messageSparklines["a"])
        assertNotNull(s.vm.state.value.messageSparklines["b"])

        s.catalog.deleteStream("b")
        s.pass(5)

        assertNotNull(s.vm.state.value.messageSparklines["a"])
        assertNull(s.vm.state.value.messageSparklines["b"])
    }

    @Test
    fun `connecting to another profile drops the trends, they are not carried across brokers`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("telemetry", messages = 1_000L)
        s.open()
        s.addStream("telemetry", messages = 1_250L)
        s.pass(5)
        assertNotNull(s.vm.state.value.messageSparklines["telemetry"])

        s.prodCatalog.addStream(FakeStreamCatalog.stream("telemetry", messages = 9L))
        s.h.active.connect(s.h.prod)
        s.settle()

        assertTrue(s.vm.state.value.messageSparklines.isEmpty(), "one sample on the new broker is not a trend")
    }

    @Test
    fun `publisher and lag trends of a client that disconnected are pruned`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeStreamCatalog.publisher("ingest-1", published = 1_000L, connectionName = "10.0.0.1:5000", publisherId = 0))
        s.consumers(s.consumerAt("worker-1", offset = 47_500L, lag = 800L, id = 0))
        s.open()
        s.publishers(FakeStreamCatalog.publisher("ingest-1", published = 1_500L, connectionName = "10.0.0.1:5000", publisherId = 0))
        s.consumers(s.consumerAt("worker-1", offset = 47_900L, lag = 400L, id = 0))
        s.pass(5)
        assertEquals(1, s.vm.state.value.publisherSparklines.size)
        assertEquals(1, s.vm.state.value.consumerLagSparklines.size)

        s.publishers()
        s.consumers()
        s.pass(5)

        assertTrue(s.vm.state.value.publisherSparklines.isEmpty())
        assertTrue(s.vm.state.value.consumerLagSparklines.isEmpty())
    }
}
