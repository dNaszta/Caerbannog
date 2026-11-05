package dev.caerbannog.core.streams

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * F-C step 2 (core, test first): a stable identity for a publisher row, so the LazyColumn of the Publishers and consumers tab can key
 * its items and a refresh that reorders or inserts rows keeps scroll position, focus and popup state with the row.
 * Contract (the format is free, like [ConsumerRowState.key]): `PublisherRowState.key: String`
 *  - unique among the publishers of one state, even when connection and reference repeat (two just-attached rows both read "unknown");
 *  - the same publisher keeps its key when its counters change on a refresh;
 *  - the key follows the publisher, not the position: a publisher inserted before it, or a changed broker order, does not change it.
 * Red today at compile level: `PublisherRowState` has no `key`.
 */
class StreamClientsViewModelPublisherKeyTest {

    @Test
    fun `publisher keys are unique in a state, also for equal references and for rows that have just attached`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(
            s.publisherRow("same", connection = "c1", id = 0),
            s.publisherRow("same", connection = "c1", id = 1),
            s.publisherRow("same", connection = "c2", id = 0),
            s.publisherRow(null, connection = "", id = 0),
            s.publisherRow(null, connection = "", id = 0),
        )
        s.open()

        val keys = s.state.publishers.map { it.key }
        assertEquals(5, s.state.publishers.size)
        assertEquals(5, keys.toSet().size, "keys must be unique: $keys")
    }

    @Test
    fun `a publisher keeps its key when its counters change on a refresh`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(s.publisherRow("a", published = 10, id = 0), s.publisherRow("b", published = 20, connection = "c2", id = 1))
        s.open()
        val before = s.state.publishers.map { it.key }

        s.publishers(s.publisherRow("a", published = 11, id = 0), s.publisherRow("b", published = 25, connection = "c2", id = 1))
        s.pass(5)

        assertEquals(listOf("11", "25"), s.state.publishers.map { it.published })
        assertEquals(before, s.state.publishers.map { it.key }, "the same row keeps its key")
    }

    @Test
    fun `a publisher inserted before, or a changed order, does not change the keys of the others`() = runTest {
        val s = ClientsHarness(this)
        val a = s.publisherRow("a", connection = "c1", id = 0)
        val b = s.publisherRow("b", connection = "c2", id = 1)
        s.publishers(a, b)
        s.open()
        val keyOf = s.state.publishers.associate { it.reference to it.key }

        s.publishers(s.publisherRow("new", connection = "c3", id = 2), b, a)
        s.pass(5)

        val after = s.state.publishers.associate { it.reference to it.key }
        assertEquals(keyOf["a"], after["a"], "a")
        assertEquals(keyOf["b"], after["b"], "b")
        assertNotEquals(keyOf["a"], after["new"])
        assertNotEquals(keyOf["b"], after["new"])
        assertEquals(3, after.values.toSet().size)
    }

    @Test
    fun `two rows that look the same keep distinct keys across a refresh`() = runTest {
        val s = ClientsHarness(this)
        s.publishers(s.publisherRow(null, connection = "", id = 0), s.publisherRow(null, connection = "", id = 0))
        s.open()
        val before = s.state.publishers.map { it.key }
        assertEquals(2, before.toSet().size, "keys: $before")

        s.pass(5)
        assertEquals(before, s.state.publishers.map { it.key })
    }
}
