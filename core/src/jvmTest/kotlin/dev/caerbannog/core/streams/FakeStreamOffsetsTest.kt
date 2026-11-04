package dev.caerbannog.core.streams

import dev.caerbannog.core.connections.fakes.FakeStreamOffsets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M2-03 part 2: the fake offsets source has the three outcomes of the real one (spec "Stream client facts").
 * Contract: `interface StreamOffsets { suspend fun firstOffset(vhost: String, stream: String): FirstOffset }`,
 * `sealed interface FirstOffset { Value(offset: Long), Empty, Unavailable }` (`Empty` and `Unavailable` are objects).
 */
class FakeStreamOffsetsTest {

    @Test
    fun `a stream with data has a value, offset 0 included`() = runBlocking<Unit> {
        val offsets = FakeStreamOffsets().also { it.setFirst("/", "a", 880_000); it.setFirst("/", "b", 0) }
        assertEquals(FirstOffset.Value(880_000), offsets.firstOffset("/", "a"))
        assertEquals(FirstOffset.Value(0), offsets.firstOffset("/", "b"))
    }

    @Test
    fun `an empty stream is Empty, not Unavailable`() = runBlocking<Unit> {
        val offsets = FakeStreamOffsets().also { it.setEmpty("/", "e") }
        assertEquals(FirstOffset.Empty, offsets.firstOffset("/", "e"))
    }

    @Test
    fun `a denied or unknown stream is Unavailable`() = runBlocking<Unit> {
        val offsets = FakeStreamOffsets().also { it.deny("/", "denied") }
        assertEquals(FirstOffset.Unavailable, offsets.firstOffset("/", "denied"))
        assertEquals(FirstOffset.Unavailable, offsets.firstOffset("/", "never-created"))
    }

    @Test
    fun `the vhost is part of the key`() = runBlocking<Unit> {
        val offsets = FakeStreamOffsets().also { it.setFirst("/", "s", 5) }
        assertEquals(FirstOffset.Unavailable, offsets.firstOffset("other", "s"))
    }

    @Test
    fun `a silent stream port makes every stream Unavailable`() = runBlocking<Unit> {
        val offsets = FakeStreamOffsets().also { it.setFirst("/", "s", 5); it.silent = true }
        assertEquals(FirstOffset.Unavailable, offsets.firstOffset("/", "s"))
    }
}
