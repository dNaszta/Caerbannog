package dev.caerbannog.core.access

import dev.caerbannog.core.access.fixtures.AccessMatchCases
import dev.caerbannog.core.access.fixtures.BrokerAnswer
import dev.caerbannog.core.access.fixtures.CaseOperation
import dev.caerbannog.core.access.fixtures.MatcherExpectation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M10-04: the case table itself (fast, no engine): its size, its identity and the rules that keep its three row classes honest. Green from the start; it guards the fixture. */
class AccessMatchCasesTest {
    @Test
    fun `the table holds the 137 spike rows and at least 190 rows in all`() {
        assertEquals(137, AccessMatchCases.spikeRows.size)
        assertTrue(AccessMatchCases.all.size >= 190, "rows: ${AccessMatchCases.all.size}")
    }

    @Test
    fun `row ids are unique`() {
        val ids = AccessMatchCases.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}")
    }

    @Test
    fun `a pattern the broker cannot store is expected unreadable, never matched`() {
        val unstorable = AccessMatchCases.all.filter { it.broker == BrokerAnswer.CannotBeSet }
        assertTrue(unstorable.size >= 8, "rows: ${unstorable.size}")
        assertTrue(unstorable.all { it.expect == MatcherExpectation.Unreadable })
    }

    @Test
    fun `a row whose matcher answer differs from the broker answer names its reason`() {
        for (case in AccessMatchCases.all) {
            val followsBroker = when (case.broker) {
                BrokerAnswer.Allowed -> case.expect == MatcherExpectation.WouldMatch
                BrokerAnswer.Refused -> case.expect == MatcherExpectation.NoMatch
                BrokerAnswer.CannotBeSet -> case.expect == MatcherExpectation.Unreadable
            }
            if (!followsBroker) assertTrue(case.reason.isNotBlank() && case.expect == MatcherExpectation.Unreadable, "${case.describe}: matcher differs from the broker without a reason")
        }
    }

    @Test
    fun `the table covers every action and the unmeasured constructs of the brief`() {
        val ops = AccessMatchCases.all.map { it.operation }.toSet()
        assertTrue(ops.containsAll(CaseOperation.entries), "operations without a row: ${CaseOperation.entries - ops}")
        val patterns = AccessMatchCases.newRows.flatMap { listOf(it.write, it.read, it.configure) + listOfNotNull(it.topic?.write, it.topic?.read) }
        for (construct in listOf("[a-z]", "[^a]", "[[:alpha:]]", "\\d", "\\w", "\\s", "(?=", "(?<n>", "(?P<n>", "\\x41", "\\p{L}", "(?s)", "(?m)", "(?x)", "\\1", "*?", "++", "{2}", "{1,3}", "^a|b$", "\\A", "\\Z", "\\z")) {
            assertTrue(patterns.any { construct in it }, "no new row for the construct $construct")
        }
        val names = AccessMatchCases.newRows.map { it.question.name }
        assertTrue(names.any { it.any { c -> c.code > 127 } }, "a non-ASCII name")
        assertTrue(names.any { it.endsWith("\n") }, "a name with a trailing newline")
        assertTrue(names.any { it.length == 255 }, "a very long name")
        assertTrue(AccessMatchCases.newRows.any { it.question.routingKey == "" && it.topic != null }, "an empty routing key against a topic pattern")
        assertTrue(AccessMatchCases.newRows.any { it.topic?.exchange.let { ex -> ex != null && ex != "amq.topic" } }, "a topic row for a custom exchange")
    }
}
