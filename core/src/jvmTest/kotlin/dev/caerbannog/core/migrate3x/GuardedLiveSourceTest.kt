package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import dev.caerbannog.core.migrate3x.fakes.FakeThreeXReads
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** M8-02a (rulings 8c): once the connection is superseded or closed nothing reaches the broker, also in the middle of a fetch. */
class GuardedLiveSourceTest {

    private val reads = FakeThreeXReads()
    private var current = true

    private fun export() = DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("orders")))

    @Test
    fun `a superseded definitions source answers Unreachable and reads nothing`() = runTest {
        current = false
        val source = GuardedDefinitionsSource(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { }, { current })
        assertEquals(FetchResult.Unreachable, source.fetch())
        assertEquals(emptyList(), reads.calls)
        assertEquals("legacy-eu", source.connectionName)
    }

    @Test
    fun `a current definitions source passes the fetch through`() = runTest {
        reads.definitions = ReadOutcome.Ok(export())
        reads.storageVersionAppearsAtRead("/", "orders", 1)
        val source = GuardedDefinitionsSource(LiveDefinitionsSource("legacy-eu", "3.13.7", reads) { }, { current })
        assertIs<FetchResult.Fetched>(source.fetch())
    }

    @Test
    fun `superseded reads answer Unreachable for every method and reach nothing`() = runTest {
        val guarded = GuardedThreeXReads(reads, { current })
        reads.definitions = ReadOutcome.Ok(export())
        assertIs<ReadOutcome.Ok<*>>(guarded.definitions())
        current = false
        assertEquals(ReadOutcome.Unreachable, guarded.definitions())
        assertEquals(ReadOutcome.Unreachable, guarded.storageVersions("/"))
        assertEquals(ReadOutcome.Unreachable, guarded.deprecatedFeaturesInUse())
        assertEquals(listOf("definitions"), reads.calls, "only the read made while current reached the fake")
    }

    @Test
    fun `a supersede in the middle of a fetch stops the reads and ends the scan as Unknown`() = runTest {
        reads.definitions = ReadOutcome.Ok(export())
        reads.storageVersionNeverAppears("/", "orders")
        val source = LiveDefinitionsSource("legacy-eu", "3.13.7", GuardedThreeXReads(reads, { current })) { current = false } // the pause supersedes
        val result = assertIs<FetchResult.Fetched>(source.fetch(), "a failing storage read never fails the fetch")
        assertEquals(ClassicIndexVersion.Unknown, result.facts.classicIndexVersions[QueueRef("/", "orders")])
        assertEquals(1, reads.storageReads("/"), "no storage read after the supersede")
        assertEquals(1, reads.definitionsReads)
    }

    @Test
    fun `a supersede between the export and the storage scan reads no storage version`() = runTest {
        reads.definitions = ReadOutcome.Ok(export())
        reads.storageVersionNeverAppears("/", "orders")
        val inner = object : ThreeXReads by reads {
            override suspend fun definitions(): ReadOutcome<String> = reads.definitions().also { current = false }
        }
        val result = LiveDefinitionsSource("legacy-eu", "3.13.7", GuardedThreeXReads(inner, { current })) { }.fetch()
        assertIs<FetchResult.Fetched>(result)
        assertEquals(0, reads.storageReads("/"))
    }
}
