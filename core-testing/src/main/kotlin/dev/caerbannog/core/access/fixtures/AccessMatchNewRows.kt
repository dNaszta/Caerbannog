package dev.caerbannog.core.access.fixtures

import dev.caerbannog.core.access.AccessAction
import dev.caerbannog.core.access.AccessQuestion
import dev.caerbannog.core.access.fixtures.AccessMatchCases.VHOST
import dev.caerbannog.core.access.fixtures.BrokerAnswer.Allowed
import dev.caerbannog.core.access.fixtures.BrokerAnswer.CannotBeSet
import dev.caerbannog.core.access.fixtures.BrokerAnswer.Refused
import dev.caerbannog.core.access.fixtures.MatcherExpectation.Unreadable

/**
 * M10-04 part A: rows for the constructs and shapes the spike M10-00 did not measure (brief "New rows to measure first"). The broker answer of each row is
 * a MEASURED fact (`RealBrokerAccessMatchTest` compares it with the broker on every run and fails when the broker says otherwise). `expect` is what the
 * matcher must say; it follows the broker unless the row says `unreadable(...)`: the engine (RE2/J) rejects the pattern, or accepts it but reads it differently
 * from Erlang `re` (then the row is the proof for `KnownDivergences`, and the broker answer stays in the row for a later engine upgrade).
 */
internal object AccessMatchNewRows {
    private val rows = mutableListOf<AccessMatchCase>()

    private fun add(
        pattern: String, name: String, broker: BrokerAnswer, action: AccessAction = AccessAction.PublishToExchange, op: CaseOperation = CaseOperation.PublishToExchange,
        rk: String? = null, cell: Cell = Cell.Write, topic: CaseTopicRow? = null, others: Triple<String, String, String> = Triple(".*", ".*", ".*"),
        expect: MatcherExpectation? = null, reason: String = "", note: String = "", bindTo: String? = null,
    ) {
        val (c0, w0, r0) = others
        val question = AccessQuestion(if (bindTo != null) AccessAction.BindQueue(bindTo) else action, VHOST, name, rk)
        val expected = expect ?: when (broker) {
            Allowed -> MatcherExpectation.WouldMatch
            Refused -> MatcherExpectation.NoMatch
            CannotBeSet -> Unreadable
        }
        rows += AccessMatchCase(
            id = "NEW-%03d".format(rows.size + 1), source = "M10-04",
            configure = if (cell == Cell.Configure) pattern else c0, write = if (cell == Cell.Write) pattern else w0, read = if (cell == Cell.Read) pattern else r0,
            topic = topic, question = question, operation = op, broker = broker, expect = expected, reason = reason, note = note,
        )
    }

    private const val DOLLAR = "KnownDivergences: $ with a name that ends in a line feed"
    private const val NON_ASCII = "KnownDivergences: a construct that counts characters, with a non-ASCII name"

    private enum class Cell { Write, Read, Configure, None }

    private fun pub(pattern: String, name: String, broker: BrokerAnswer, expect: MatcherExpectation? = null, reason: String = "", note: String = "") =
        add(pattern, name, broker, expect = expect, reason = reason, note = note)

    /** Publish to an exchange with a topic permission pattern on [exchange] (the vhost write is `.*` unless [vhostWrite] says otherwise). */
    private fun topicPub(
        pattern: String, rk: String, broker: BrokerAnswer, exchange: String = "amq.topic", asked: String = exchange, vhostWrite: String = ".*", expect: MatcherExpectation? = null,
        reason: String = "", note: String = "",
    ) = add(vhostWrite, asked, broker, rk = rk, topic = CaseTopicRow(exchange, write = pattern), cell = Cell.None, others = Triple(".*", vhostWrite, ".*"), expect = expect, reason = reason, note = note)

    private fun get(pattern: String, name: String, broker: BrokerAnswer, expect: MatcherExpectation? = null, reason: String = "") =
        add(pattern, name, broker, AccessAction.ReadFromQueue, CaseOperation.BasicGet, cell = Cell.Read, expect = expect, reason = reason)

    private fun invalid(cell: Cell, pattern: String, reason: String) = when (cell) {
        Cell.Write -> add(pattern, "ex", CannotBeSet, reason = reason)
        Cell.Read -> add(pattern, "queue", CannotBeSet, AccessAction.ReadFromQueue, CaseOperation.BasicGet, cell = Cell.Read, reason = reason)
        Cell.Configure -> add(pattern, "new", CannotBeSet, AccessAction.Configure, CaseOperation.QueueDeclare, cell = Cell.Configure, reason = reason)
        Cell.None -> error("no cell")
    }

    fun rows(): List<AccessMatchCase> {
        if (rows.isNotEmpty()) return rows.toList()
        // --- character classes ([a-z] and [[:alpha:]]: a class in PCRE; Java reads [[:alpha:]] as a union of characters, which is why the engine is not kotlin.text.Regex)
        pub("^[a-z]+$", "abc", Allowed); pub("^[a-z]+$", "ABC", Refused)
        pub("^[^a]", "bx", Allowed); pub("^[^a]", "ax", Refused)
        pub("^[[:alpha:]]+$", "abc", Allowed); pub("^[[:alpha:]]+$", "ab1", Refused)
        // --- shorthand classes
        pub("^\\d+$", "123", Allowed); pub("^\\d+$", "12a", Refused)
        pub("^\\w+$", "ab_1", Allowed); pub("^\\w+$", "ab-1", Refused)
        pub("^a\\sb$", "a b", Allowed); pub("^a\\sb$", "a-b", Refused)
        // --- lookaround, backreference, possessive quantifier: PCRE reads them, RE2 does not (linear time), so the engine rejects them
        pub("^(?=ex).*", "ex.orders", Allowed, Unreadable, "RE2/J rejects lookahead"); pub("^(?=ex).*", "my-ex", Refused, Unreadable, "RE2/J rejects lookahead")
        pub("ex(?!\\.)", "my-ex", Allowed, Unreadable, "RE2/J rejects negative lookahead"); pub("ex(?!\\.)", "ex.orders", Refused, Unreadable, "RE2/J rejects negative lookahead")
        pub("(?<=my-)ex", "my-ex", Allowed, Unreadable, "RE2/J rejects lookbehind")
        pub("^(a)\\1$", "aa", Allowed, Unreadable, "RE2/J rejects a backreference"); pub("^(a)\\1$", "ab", Refused, Unreadable, "RE2/J rejects a backreference")
        pub("^a++b", "aab", Allowed, Unreadable, "RE2/J rejects a possessive quantifier"); pub("^a++b", "aa", Refused, Unreadable, "RE2/J rejects a possessive quantifier")
        // --- named groups
        pub("(?<n>ex)", "ex.orders", Allowed); pub("(?<n>ex)", "my-queue", Refused)
        pub("(?P<n>ex)", "ex.orders", Allowed); pub("(?P<n>ex)", "my-queue", Refused)
        // --- escapes and Unicode properties
        pub("^\\x41$", "A", Allowed); pub("^\\x41$", "a", Refused)
        pub("^\\p{L}+$", "abc", Allowed); pub("^\\p{L}+$", "ab1", Refused)
        // --- flags
        pub("(?s)^a.b$", "a\nb", Allowed); pub("^a.b$", "a\nb", Refused)
        pub("(?m)^b$", "a\nb", Allowed); pub("^b$", "a\nb", Refused)
        pub("(?x) ex \\. orders", "ex.orders", Allowed, Unreadable, "RE2/J has no (?x) flag")
        pub("(?i)^EX$", "ex", Allowed)
        // --- quantifiers
        pub("^a.*?b", "axb", Allowed); pub("^a.*?b", "xb", Refused)
        pub("^a{2}$", "aa", Allowed); pub("^a{2}$", "a", Refused)
        pub("^a{1,3}$", "aaa", Allowed); pub("^a{1,3}$", "aaaa", Refused)
        // --- alternation binds weaker than the anchors: ^a|b$ is (^a)|(b$)
        pub("^a|b$", "ax", Allowed); pub("^a|b$", "xb", Allowed); pub("^a|b$", "xax", Refused)
        pub("^(ex|my-ex)$", "my-ex", Allowed); pub("^(ex|my-ex)$", "ex.orders", Refused)
        // --- end of line and end of text
        pub("^ex$", "ex\n", Allowed, Unreadable, DOLLAR, "PCRE: $ also matches before a final newline; RE2/J does not")
        pub("ex$", "ex\n", Allowed, Unreadable, DOLLAR, "PCRE: $ also matches before a final newline; RE2/J does not")
        pub("\\Aex\\z", "ex", Allowed); pub("\\Aex\\z", "ex\n", Refused)
        pub("\\Aex\\Z", "ex\n", Allowed, Unreadable, "RE2/J has no \\Z")
        // --- bytes versus characters: Erlang re reads bytes (no UTF mode); é is two bytes, RE2/J reads one character. Measured: every row below where the construct counts or classes characters.
        pub("^.$", "é", Refused, Unreadable, NON_ASCII, "broker counts 2 bytes, RE2/J 1 character"); pub("^..$", "é", Allowed, Unreadable, NON_ASCII)
        pub("^é$", "é", Allowed, note = "a literal non-ASCII pattern agrees: both compare the same bytes"); pub("^é$", "e", Refused)
        pub("^.{2}$", "é", Allowed, Unreadable, NON_ASCII); pub("^.*$", "é", Allowed, Unreadable, NON_ASCII, "agrees, but the construct . is on KnownDivergences by shape, so the matcher says unreadable (safe direction)")
        pub("^[^a]$", "é", Refused, Unreadable, NON_ASCII); pub("^[é]$", "é", Refused, Unreadable, NON_ASCII); pub("^\\W$", "é", Refused, Unreadable, NON_ASCII); pub("^\\S$", "é", Refused, Unreadable, NON_ASCII)
        pub("^\\p{L}$", "é", Refused, Unreadable, NON_ASCII); pub("^\\p{L}+$", "é", Refused, Unreadable, NON_ASCII); pub("(?i)^É$", "é", Refused, Unreadable, NON_ASCII, "RE2/J folds Unicode case, Erlang re (no UTF mode) only ASCII")
        pub("^(a+)+$", "aaaa", Allowed, note = "nested quantifier: linear in RE2/J, so no rule and no budget"); pub("^(a+)+$", "aaab", Refused)
        // --- a long name
        pub("^a+$", "a".repeat(255), Allowed); pub("^a{255}$", "a".repeat(255), Allowed); pub("^a{254}$", "a".repeat(255), Refused)
        // --- a pattern that is the word the guard uses for a secret: not hidden unless the guard hid it (then the matcher never gets here)
        pub("hidden", "hidden-ex", Allowed); pub("hidden", "ex", Refused)
        // --- patterns the broker cannot store
        invalid(Cell.Write, "[", "unterminated class"); invalid(Cell.Write, "(", "unterminated group"); invalid(Cell.Write, "*a", "nothing to repeat")
        invalid(Cell.Write, "a{2,1}", "quantifier out of order"); invalid(Cell.Write, "[z-a]", "range out of order"); invalid(Cell.Write, "ab\\", "backslash at end")
        invalid(Cell.Read, "(", "unterminated group"); invalid(Cell.Configure, "[", "unterminated class")
        // --- topic permissions: an empty routing key, a custom topic exchange, other exchanges, the vhost write failing in turn
        topicPub("^a\\..*", "", Refused); topicPub(".*", "", Allowed); topicPub("^$", "", Allowed)
        topicPub("^a\\..*", "a.b", Allowed, exchange = "x.topic"); topicPub("^a\\..*", "b.c", Refused, exchange = "x.topic")
        topicPub("^a\\..*", "b.c", Allowed, exchange = "amq.topic", asked = "x.topic", note = "a topic row of another exchange limits nothing")
        topicPub("^a\\..*", "b.c", Allowed, exchange = "amq.topic", asked = "ex", note = "a direct exchange has no topic check")
        topicPub(".*", "a.b", Refused, vhostWrite = "^$"); topicPub(".*", "a.b", Refused, vhostWrite = "ex", note = "write ex does not match amq.topic")
        topicPub("^$", "a.b", Refused); topicPub("", "a.b", Refused, note = "an empty topic pattern allows nothing"); topicPub("^b", "a.b", Refused, vhostWrite = "topic")
        topicPub("^a", "a.b", Allowed, vhostWrite = "topic")
        // --- configure on a queue and on an exchange
        add("^q\\.", "q.new", Allowed, AccessAction.Configure, CaseOperation.QueueDeclare, cell = Cell.Configure)
        add("^q\\.", "other", Refused, AccessAction.Configure, CaseOperation.QueueDeclare, cell = Cell.Configure)
        add("^x\\.", "x.new", Allowed, AccessAction.Configure, CaseOperation.ExchangeDeclare, cell = Cell.Configure)
        add("^x\\.", "y.new", Refused, AccessAction.Configure, CaseOperation.ExchangeDeclare, cell = Cell.Configure)
        // --- topic READ permission: queue.bind to a topic exchange with a binding key, and consume from a queue bound to one
        add("^a\\..*", "queue", Allowed, op = CaseOperation.QueueBind, rk = "a.b", cell = Cell.None, bindTo = "amq.topic", topic = CaseTopicRow("amq.topic", ".*", "^a\\..*"), note = "bind key a.b")
        add("^a\\..*", "queue", Refused, op = CaseOperation.QueueBind, rk = "b.c", cell = Cell.None, bindTo = "amq.topic", topic = CaseTopicRow("amq.topic", ".*", "^a\\..*"), note = "bind key b.c")
        add("^$", "queue", Allowed, AccessAction.ReadFromQueue, CaseOperation.ConsumeBoundQueue, cell = Cell.None, topic = CaseTopicRow("amq.topic", ".*", "^$"), note = "queue is bound to amq.topic by key a.b")
        return rows.toList()
    }
}
