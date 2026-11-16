package dev.caerbannog.core.planner

import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M9-04 step 4 (test developer): rapid virtual host changes with answers arriving in any order. (A reader that throws is not tested here: the exception is uncaught in the view model scope and leaks into later runTest tests of the same JVM; see the step 4 report.) */
class PlannerViewModelKnownExploratoryTest {
    private val rigs = mutableListOf<PlannerRig>()

    @AfterEach
    fun close() = rigs.forEach { it.close() }

    private class ManualReader : KnownTopologyReader {
        val asked = mutableListOf<String>()
        val pending = mutableMapOf<String, CompletableDeferred<KnownTopology?>>()
        override suspend fun read(vhost: String): KnownTopology? {
            asked += vhost
            return CompletableDeferred<KnownTopology?>().also { pending[vhost] = it }.await()
        }
    }

    @Test
    fun `three quick virtual host changes keep only the answer of the last, whatever the order of the answers`() {
        val reader = ManualReader()
        val r = PlannerRig(reader = reader).also { rigs += it }
        r.vm.setVirtualHost("a")
        r.vm.setVirtualHost("b")
        r.vm.setVirtualHost("c")
        assertEquals(listOf("/", "a", "b", "c"), reader.asked)
        val b = KnownTopology(queues = setOf("from-b"))
        val c = KnownTopology(queues = setOf("from-c"))
        reader.pending.getValue("c").complete(c)
        reader.pending.getValue("b").complete(b)
        reader.pending.getValue("a").complete(KnownTopology(queues = setOf("from-a")))
        reader.pending.getValue("/").complete(KnownTopology(queues = setOf("from-root")))
        assertEquals(c, r.state.known)
        assertEquals(planPreview(r.state.plan, c), r.state.graph)
        assertEquals(listOf("/", "a", "b", "c"), reader.asked, "no answer starts another read")
    }

    @Test
    fun `going back to the first virtual host reads it again and the old answer of it is not reused as current`() {
        val reader = ManualReader()
        val r = PlannerRig(reader = reader).also { rigs += it }
        r.vm.setVirtualHost("a")
        r.vm.setVirtualHost("/")
        assertEquals(listOf("/", "a", "/"), reader.asked)
        reader.pending.getValue("a").complete(KnownTopology(queues = setOf("from-a")))
        assertNull(r.state.known, "the answer for a is late; the plan is on / and has no answer yet")
    }
}
