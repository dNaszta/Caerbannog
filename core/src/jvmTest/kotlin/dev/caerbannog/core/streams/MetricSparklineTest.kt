package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricSparklineTest {

    @Test
    fun `RollingMetricHistory requires at least two samples and bounds ring buffer to maxSamples`() {
        val history = RollingMetricHistory(maxSamples = 4)
        history.record("orders", 100L, 1_000L)
        assertTrue(history.snapshot(listOf("orders"), SparklineSemantic.Velocity).isEmpty())

        history.record("orders", 150L, 6_000L)
        val firstSnap = history.snapshot(listOf("orders"), SparklineSemantic.Velocity).getValue("orders")
        assertEquals(listOf(100L, 150L), firstSnap.samples)
        assertEquals(50L, firstSnap.deltaPerTick)
        assertEquals(10L, firstSnap.deltaPerSecond)
        assertEquals(TrendDirection.Growing, firstSnap.direction)
        assertEquals("+10/s", firstSnap.label)

        history.record("orders", 200L, 11_000L)
        history.record("orders", 250L, 16_000L)
        history.record("orders", 300L, 21_000L)
        val bounded = history.snapshot(listOf("orders"), SparklineSemantic.Velocity).getValue("orders")
        assertEquals(listOf(150L, 200L, 250L, 300L), bounded.samples)
        assertEquals(4, bounded.normalizedPoints.size)
        assertEquals(0f, bounded.normalizedPoints.first())
        assertEquals(1f, bounded.normalizedPoints.last())
    }

    @Test
    fun `consumer lag sparkline distinguishes growing, draining, and steady trends`() {
        val history = RollingMetricHistory()
        history.record("c1", 500L, 1_000L)
        history.record("c1", 380L, 6_000L)

        val draining = history.snapshot(listOf("c1"), SparklineSemantic.ConsumerLag).getValue("c1")
        assertEquals(TrendDirection.Draining, draining.direction)
        assertEquals(-120L, draining.deltaPerTick)
        assertEquals("↘ Draining (-120)", draining.label)

        history.record("c1", 465L, 11_000L)
        val growing = history.snapshot(listOf("c1"), SparklineSemantic.ConsumerLag).getValue("c1")
        assertEquals(TrendDirection.Growing, growing.direction)
        assertEquals(85L, growing.deltaPerTick)
        assertEquals("↗ Growing (+85)", growing.label)

        history.record("c1", 465L, 16_000L)
        val steady = history.snapshot(listOf("c1"), SparklineSemantic.ConsumerLag).getValue("c1")
        assertEquals(TrendDirection.Steady, steady.direction)
        assertEquals(0L, steady.deltaPerTick)
        assertEquals("→ Steady", steady.label)
    }

    @Test
    fun `StreamListViewModel populates messageSparklines after second refresh tick with zero extra broker calls`() = runTest {
        val s = StreamListHarness(this)
        s.addStream("telemetry", messages = 1_000L, first = 0L)
        s.open()
        assertNull(s.state.messageSparklines["telemetry"])

        s.addStream("telemetry", messages = 1_250L, first = 0L)
        s.pass(5)

        val sparkline = s.state.messageSparklines.getValue("telemetry")
        assertEquals(listOf(1_000L, 1_250L), sparkline.samples)
        assertEquals(250L, sparkline.deltaPerTick)
        assertEquals(50L, sparkline.deltaPerSecond)
        assertEquals("+50/s", sparkline.label)
    }

    @Test
    fun `StreamClientsViewModel populates publisher velocity and consumer lag trend sparklines across ticks`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(FakeStreamCatalog.publisher("ingest-1", published = 1_000L, connectionName = "10.0.0.1:5000", publisherId = 0))
        s.consumers(s.consumerAt("worker-1", offset = 47_500L, lag = 800L, id = 0))
        s.open()

        assertTrue(s.state.publisherSparklines.isEmpty())
        assertTrue(s.state.consumerLagSparklines.isEmpty())

        s.publishers(FakeStreamCatalog.publisher("ingest-1", published = 1_500L, connectionName = "10.0.0.1:5000", publisherId = 0))
        s.consumers(s.consumerAt("worker-1", offset = 47_900L, lag = 400L, id = 0))
        s.pass(5)

        val pubSpark = s.state.publisherSparklines.getValue("10.0.0.1:5000#0")
        assertEquals(500L, pubSpark.deltaPerTick)
        assertEquals("+100/s", pubSpark.label)

        val lagSpark = s.state.consumerLagSparklines.getValue("10.0.5.14:38812 -> 10.0.0.1:5552#0")
        assertEquals(TrendDirection.Draining, lagSpark.direction)
        assertEquals(-400L, lagSpark.deltaPerTick)
        assertEquals("↘ Draining (-400)", lagSpark.label)
    }
}
