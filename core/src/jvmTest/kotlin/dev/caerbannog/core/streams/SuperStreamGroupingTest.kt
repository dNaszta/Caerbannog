package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamCatalog.Companion.stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * M2-07 step 2: the pure grouping function, no view model. Contract (test developer, flagged in the report):
 * `groupStreams(streams, layouts): List<StreamEntry>` returns one [StreamEntry.Single] per stream that is not a grouped partition and one
 * [StreamEntry.Group] per layout, partitions in the layout's order. The order of the returned entries is NOT specified (sorting is the
 * view model's job); these tests compare by name. This is the one place of the "entry, not stream" rule.
 */
class SuperStreamGroupingTest {

    private fun streams(vararg names: String) = names.map { stream(it) }

    private fun List<StreamEntry>.byName(): Map<String, StreamEntry> = associateBy { it.name }

    private fun StreamEntry.partitionNames(): List<String> = (this as StreamEntry.Group).partitions.map { it.name }

    @Test
    fun `without layouts every stream is its own entry`() {
        val entries = groupStreams(streams("a", "b"), emptyList())

        assertEquals(setOf("a", "b"), entries.map { it.name }.toSet())
        assertEquals(2, entries.size)
        entries.forEach { assertIs<StreamEntry.Single>(it) }
    }

    @Test
    fun `a layout groups its partitions, which stop being entries of their own`() {
        val entries = groupStreams(
            streams("orders-0", "orders-1", "orders-2", "audit-log"),
            listOf(SuperStreamLayout("orders", "/", listOf("orders-0", "orders-1", "orders-2"))),
        )

        assertEquals(setOf("audit-log", "orders"), entries.map { it.name }.toSet())
        assertEquals(2, entries.size)
        assertIs<StreamEntry.Single>(entries.byName().getValue("audit-log"))
        assertEquals(listOf("orders-0", "orders-1", "orders-2"), entries.byName().getValue("orders").partitionNames())
    }

    @Test
    fun `partitions keep the layout's order, not the order of the stream list`() {
        val entries = groupStreams(
            streams("regions-eu", "regions-us"),
            listOf(SuperStreamLayout("regions", "/", listOf("regions-us", "regions-eu"))),
        )

        assertEquals(listOf("regions-us", "regions-eu"), entries.single().partitionNames())
    }

    @Test
    fun `a partition that is not among the streams is skipped`() {
        val entries = groupStreams(
            streams("orders-0", "orders-2"),
            listOf(SuperStreamLayout("orders", "/", listOf("orders-0", "orders-1", "orders-2"))),
        )

        assertEquals(listOf("orders-0", "orders-2"), entries.single().partitionNames())
    }

    @Test
    fun `a stream that is no partition of any layout stays a plain entry even when its name looks like one`() {
        val entries = groupStreams(
            streams("orders-0", "orders-1", "orders-7"),
            listOf(SuperStreamLayout("orders", "/", listOf("orders-0", "orders-1"))),
        )

        assertEquals(setOf("orders", "orders-7"), entries.map { it.name }.toSet(), "grouping follows the bindings, never the names")
        assertIs<StreamEntry.Single>(entries.byName().getValue("orders-7"))
    }

    @Test
    fun `a partition in two layouts belongs to the first super stream by name and appears once`() {
        val entries = groupStreams(
            streams("shared", "only-b"),
            listOf(
                SuperStreamLayout("b-super", "/", listOf("only-b", "shared")),
                SuperStreamLayout("a-super", "/", listOf("shared")),
            ),
        )

        assertEquals(listOf("shared"), entries.byName().getValue("a-super").partitionNames())
        assertEquals(listOf("only-b"), entries.byName().getValue("b-super").partitionNames())
        assertEquals(2, entries.size)
    }

    @Test
    fun `a partition named twice in one layout appears once`() {
        val entries = groupStreams(streams("p"), listOf(SuperStreamLayout("s", "/", listOf("p", "p"))))

        assertEquals(listOf("p"), entries.single().partitionNames())
    }

    @Test
    fun `two super streams group their own partitions`() {
        val entries = groupStreams(
            streams("a-0", "a-1", "b-0"),
            listOf(SuperStreamLayout("a", "/", listOf("a-0", "a-1")), SuperStreamLayout("b", "/", listOf("b-0"))),
        )

        assertEquals(listOf("a-0", "a-1"), entries.byName().getValue("a").partitionNames())
        assertEquals(listOf("b-0"), entries.byName().getValue("b").partitionNames())
    }

    // Architect ruling 2: nothing is listed for a super stream none of whose partitions was loaded.
    @Test
    fun `a layout with no partition among the streams gives no entry`() {
        val entries = groupStreams(streams("audit-log"), listOf(SuperStreamLayout("orders", "/", listOf("orders-0", "orders-1"))))

        assertEquals(listOf("audit-log"), entries.map { it.name })
        assertIs<StreamEntry.Single>(entries.single())
    }

    @Test
    fun `a layout with no partitions at all gives no entry`() {
        assertEquals(emptyList(), groupStreams(emptyList(), listOf(SuperStreamLayout("orders", "/", emptyList()))))
    }

    @Test
    fun `no streams and no layouts give no entries`() {
        assertEquals(emptyList(), groupStreams(emptyList(), emptyList()))
    }
}
