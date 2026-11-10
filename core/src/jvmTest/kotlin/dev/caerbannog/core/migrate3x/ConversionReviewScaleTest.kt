package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** M8-05: 60,000 classic queues review and generate within the default heap; decisions are one map, not one object per finding. No timing assertion. */
class ConversionReviewScaleTest {
    @Test
    fun `60000 classic queues review and generate`() {
        val queues = (1..60_000).map { D.classicQueue("queue-%05d".format(it)) }
        val s = started(D.document(queues = queues))
        assertEquals(60_000, s.review.items.size)
        assertEquals(emptyList<ReviewItem>(), s.acceptEveryDefault())
        assertEquals(emptyList<ReviewItem>(), s.review.pending)
        val result = s.generated()
        val actions = result.actions()
        assertEquals(60_000, actions.size)
        assertEquals("queue-00001", (actions.first() as MigrationAction.DeclareQueue).name)
        assertEquals("quorum", (actions.last() as MigrationAction.DeclareQueue).type)
    }
}
