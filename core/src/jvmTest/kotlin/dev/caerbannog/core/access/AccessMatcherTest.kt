package dev.caerbannog.core.access

import dev.caerbannog.core.access.fixtures.AccessMatchCases
import dev.caerbannog.core.access.fixtures.MatcherExpectation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M10-04 part A (test developer): the contract of [AccessMatcher] (brief items 1 to 3, "Check order, fixed"). Pure, no broker. Two halves:
 *  - the REAL engine over every row of `AccessMatchCases` (the 137 S-I rows measured on a real broker plus the rows of M10-04), which is the same table the live test
 *    `RealBrokerAccessMatchTest` compares with the broker;
 *  - a fake engine (`RecordingEngine`) to pin the ORDER of the checks without a regex: what is looked at first, and what the engine is never asked.
 * Red until step 3: [defaultPatternEngine] and [AccessMatcher.check] are stubs that throw `NotImplementedError`.
 */
class AccessMatcherTest {
    private fun matcher(engine: PatternEngine = RecordingEngine(), divergences: KnownDivergences = KnownDivergences(emptyList())) = AccessMatcher(engine, divergences)

    // ------------------------------------------------------------ the table, real engine

    @Test
    fun `every row of the case table gets the answer the broker gave, or unreadable where the table says so`() {
        val matcher = AccessMatcher(defaultPatternEngine(), KnownDivergences.Measured)
        val rows = AccessMatchCases.all.filter { it.matcherApplies }
        val wrong = rows.mapNotNull { case ->
            val permissions = UserPermissions(
                "svc-orders", listOf(PermissionRow("/", cell(case.configure), cell(case.write), cell(case.read))),
                listOfNotNull(case.topic?.let { TopicPermissionRow("/", it.exchange, cell(it.write), cell(it.read)) }),
            )
            val actual = when (matcher.check(permissions, case.question)) {
                is AccessAnswer.WouldMatch -> MatcherExpectation.WouldMatch
                is AccessAnswer.NoMatch -> MatcherExpectation.NoMatch
                is AccessAnswer.UnreadablePattern -> MatcherExpectation.Unreadable
                is AccessAnswer.PermissionsNotReadable -> null
            }
            if (actual == case.expect) null else "${case.describe}: expected ${case.expect}, got $actual (${case.reason})"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${rows.size} rows wrong:\n" + wrong.take(40).joinToString("\n"))
    }

    // ------------------------------------------------------------ the action table

    @Test
    fun `publish to an exchange is the write pattern on the exchange name`() {
        val answer = matcher().check(permissions(write = cell("^ex\\..*")), publish("ex.orders"))
        assertEquals(AccessAnswer.WouldMatch(listOf(Hit(PatternKind.Write, DisplayPattern("^ex\\..*"), null, "ex.orders"))), answer)
        val refused = matcher().check(permissions(write = cell("^ex\\..*")), publish("amq.topic"))
        assertEquals(AccessAnswer.NoMatch(Hit(PatternKind.Write, DisplayPattern("^ex\\..*"), null, "amq.topic")), refused)
    }

    @Test
    fun `read from a queue is the read pattern and configure is the configure pattern`() {
        val read = matcher().check(permissions(read = cell("^queue\\..*")), readQueue("queue.orders"))
        assertEquals(AccessAnswer.WouldMatch(listOf(Hit(PatternKind.Read, DisplayPattern("^queue\\..*"), null, "queue.orders"))), read)
        val configure = matcher().check(permissions(configure = cell("^queue\\..*")), configure("other"))
        assertEquals(AccessAnswer.NoMatch(Hit(PatternKind.Configure, DisplayPattern("^queue\\..*"), null, "other")), configure)
    }

    @Test
    fun `the stream forms use the write and the read pattern on the stream name`() {
        val p = permissions(write = cell("^st\\.perm$"), read = cell("^nope$"))
        assertTrue(matcher().check(p, AccessQuestion(AccessAction.PublishToStream, "/orders", "st.perm")) is AccessAnswer.WouldMatch)
        val read = matcher().check(p, AccessQuestion(AccessAction.ReadFromStream, "/orders", "st.perm"))
        assertEquals(PatternKind.Read, (read as AccessAnswer.NoMatch).deciding.kind)
    }

    @Test
    fun `the default exchange is asked as amq-default, never as the target queue and never as the empty name`() {
        for (asked in listOf("", "amq.default")) {
            val answer = matcher().check(permissions(write = cell("^amq\\.default$")), publish(asked, routingKey = "queue"))
            assertEquals(AccessAnswer.WouldMatch(listOf(Hit(PatternKind.Write, DisplayPattern("^amq\\.default$"), null, "amq.default"))), answer, "asked as [$asked]")
        }
        assertTrue(matcher().check(permissions(write = cell("^queue$")), publish("", routingKey = "queue")) is AccessAnswer.NoMatch, "a pattern that fits the target queue name does not allow the default exchange")
        assertTrue(matcher().check(permissions(write = cell("^$")), publish("")) is AccessAnswer.NoMatch, "the default exchange is not the empty name")
    }

    @Test
    fun `binding needs write on the queue then read on the exchange, in that order`() {
        val bind = AccessQuestion(AccessAction.BindQueue("ex.orders"), "/orders", "queue.orders", "k")
        val ok = matcher().check(permissions(write = cell("^queue\\..*"), read = cell("^ex\\..*")), bind)
        assertEquals(
            AccessAnswer.WouldMatch(listOf(Hit(PatternKind.Write, DisplayPattern("^queue\\..*"), null, "queue.orders"), Hit(PatternKind.Read, DisplayPattern("^ex\\..*"), null, "ex.orders"))), ok,
        )
        val both = matcher().check(permissions(write = cell("^no$"), read = cell("^no$")), bind)
        assertEquals(PatternKind.Write, (both as AccessAnswer.NoMatch).deciding.kind, "the first failing cell decides")
        val onlyRead = matcher().check(permissions(write = cell(".*"), read = cell("^no$")), bind)
        assertEquals(PatternKind.Read, (onlyRead as AccessAnswer.NoMatch).deciding.kind)
    }

    @Test
    fun `binding to an exchange with a topic row also checks the topic read pattern on the binding key, consuming does not`() {
        val p = permissions(topic = listOf(topicRow("amq.topic", read = cell("^a\\..*"))))
        val bind = { key: String -> AccessQuestion(AccessAction.BindQueue("amq.topic"), "/orders", "queue", key) }
        val ok = matcher().check(p, bind("a.b")) as AccessAnswer.WouldMatch
        assertEquals(listOf(PatternKind.Write, PatternKind.Read, PatternKind.TopicRead), ok.hits.map { it.kind })
        assertEquals(Hit(PatternKind.TopicRead, DisplayPattern("^a\\..*"), "amq.topic", "a.b"), ok.hits.last())
        assertEquals(PatternKind.TopicRead, (matcher().check(p, bind("b.c")) as AccessAnswer.NoMatch).deciding.kind)
        val consume = matcher().check(permissions(topic = listOf(topicRow("amq.topic", read = cell("^$")))), readQueue("queue"))
        assertEquals(listOf(PatternKind.Read), (consume as AccessAnswer.WouldMatch).hits.map { it.kind }, "measured: basic.consume ignores the topic read pattern")
    }

    // ------------------------------------------------------------ topic permissions

    @Test
    fun `publish to an exchange with a topic row checks write and then topic write on the routing key`() {
        val p = permissions(topic = listOf(topicRow("amq.topic", write = cell("^sensors\\..*"))))
        val ok = matcher().check(p, publish("amq.topic", routingKey = "sensors.temp"))
        assertEquals(
            AccessAnswer.WouldMatch(listOf(Hit(PatternKind.Write, DisplayPattern(".*"), null, "amq.topic"), Hit(PatternKind.TopicWrite, DisplayPattern("^sensors\\..*"), "amq.topic", "sensors.temp"))), ok,
        )
        val no = matcher().check(p, publish("amq.topic", routingKey = "alarms.fire"))
        assertEquals(AccessAnswer.NoMatch(Hit(PatternKind.TopicWrite, DisplayPattern("^sensors\\..*"), "amq.topic", "alarms.fire")), no)
    }

    @Test
    fun `no routing key is the empty key`() {
        val p = permissions(topic = listOf(topicRow("amq.topic", write = cell("^sensors\\..*"))))
        val none = matcher().check(p, publish("amq.topic", routingKey = null))
        val empty = matcher().check(p, publish("amq.topic", routingKey = ""))
        assertEquals(empty, none)
        assertEquals(Hit(PatternKind.TopicWrite, DisplayPattern("^sensors\\..*"), "amq.topic", ""), (none as AccessAnswer.NoMatch).deciding)
    }

    @Test
    fun `a topic row of another exchange, or none, limits nothing`() {
        val p = permissions(topic = listOf(topicRow("amq.topic", write = cell("^$"))))
        assertEquals(listOf(PatternKind.Write), (matcher().check(p, publish("ex.orders", routingKey = "b.c")) as AccessAnswer.WouldMatch).hits.map { it.kind })
        assertEquals(listOf(PatternKind.Write), (matcher().check(permissions(), publish("amq.topic", routingKey = "b.c")) as AccessAnswer.WouldMatch).hits.map { it.kind })
    }

    @Test
    fun `the vhost write and the topic write each can refuse, the first failing cell decides`() {
        val topicNo = permissions(write = cell("topic"), topic = listOf(topicRow("amq.topic", write = cell("^b"))))
        assertEquals(PatternKind.TopicWrite, (matcher().check(topicNo, publish("amq.topic", routingKey = "a.b")) as AccessAnswer.NoMatch).deciding.kind)
        val writeNo = permissions(write = cell("^ex$"), topic = listOf(topicRow("amq.topic", write = cell("^b"))))
        assertEquals(PatternKind.Write, (matcher().check(writeNo, publish("amq.topic", routingKey = "a.b")) as AccessAnswer.NoMatch).deciding.kind)
    }

    // ------------------------------------------------------------ the check order of the brief

    @Test
    fun `permissions that are not available answer first, before the engine is asked`() {
        for (reason in PermissionsUnavailable.entries) {
            val engine = RecordingEngine()
            val answer = matcher(engine).check(PermissionsSource.NotAvailable(reason), publish("ex"))
            assertEquals(AccessAnswer.PermissionsNotReadable(reason), answer)
            assertTrue(engine.compiled.isEmpty())
        }
    }

    @Test
    fun `a virtual host without a row is no match with the pattern shown as no permission, and the engine is not asked`() {
        val engine = RecordingEngine()
        val answer = matcher(engine).check(permissions(vhost = "/orders"), publish("ex.orders", vhost = "/billing"))
        assertEquals(AccessAnswer.NoMatch(Hit(PatternKind.Write, DisplayPattern(PermissionsTexts.NO_PERMISSION, noPermission = true), null, "ex.orders"), noRowForVhost = true), answer)
        assertTrue(engine.compiled.isEmpty())
        val readNo = matcher(engine).check(permissions(vhost = "/orders"), readQueue("q", vhost = "/billing")) as AccessAnswer.NoMatch
        assertEquals(PatternKind.Read, readNo.deciding.kind)
    }

    @Test
    fun `a hidden cell is unreadable before any regex, even though the word hidden is a valid regular expression`() {
        val engine = RecordingEngine()
        val answer = matcher(engine).check(permissions(write = cell("", hidden = true)), publish("hidden-exchange"))
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), answer)
        assertTrue(engine.compiled.isEmpty(), "the engine must never be given the word hidden: ${engine.compiled}")
    }

    @Test
    fun `a hidden topic cell is unreadable, a hidden cell that the question does not need is ignored`() {
        val topicHidden = permissions(topic = listOf(topicRow("amq.topic", write = cell("", hidden = true))))
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.TopicWrite), matcher().check(topicHidden, publish("amq.topic", routingKey = "a.b")))
        val unneeded = permissions(configure = cell("", hidden = true), read = cell("", hidden = true))
        assertTrue(matcher().check(unneeded, publish("ex")) is AccessAnswer.WouldMatch)
    }

    @Test
    fun `the empty pattern is no permission, a no match that allows nothing, and needs no engine`() {
        val engine = RecordingEngine()
        val answer = matcher(engine).check(permissions(write = cell("")), publish("ex"))
        assertEquals(AccessAnswer.NoMatch(Hit(PatternKind.Write, DisplayPattern(PermissionsTexts.NO_PERMISSION, noPermission = true), null, "ex")), answer)
        assertTrue(engine.compiled.isEmpty())
        assertTrue(matcher().check(permissions(write = cell("^$")), publish("")) is AccessAnswer.NoMatch, "^$ is a pattern, not no permission; the empty name is not matched either")
    }

    @Test
    fun `an empty topic pattern is flagged no permission too`() {
        val answer = matcher().check(permissions(topic = listOf(topicRow("amq.topic", write = cell("")))), publish("amq.topic", routingKey = "a.b")) as AccessAnswer.NoMatch
        assertEquals(PatternKind.TopicWrite, answer.deciding.kind)
        assertTrue(answer.deciding.pattern.noPermission, "the empty topic cell must carry the flag")
    }

    @Test
    fun `a real pattern spelled no permission is a pattern, not the empty cell (M10-04e)`() {
        val spelled = cell(PermissionsTexts.NO_PERMISSION)
        val no = matcher().check(permissions(write = spelled), publish("ex.orders")) as AccessAnswer.NoMatch
        assertEquals(DisplayPattern("no permission", noPermission = false), no.deciding.pattern)
        val yes = matcher().check(permissions(write = spelled), publish("no permission queue")) as AccessAnswer.WouldMatch
        assertEquals(DisplayPattern("no permission", noPermission = false), yes.hits.single { it.kind == PatternKind.Write }.pattern)
    }

    @Test
    fun `an unreadable cell anywhere wins over a no match of another cell`() {
        val bind = AccessQuestion(AccessAction.BindQueue("ex"), "/orders", "queue", "k")
        val p = permissions(write = cell("^no$"), read = cell("(unclosed"))
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Read), matcher(RecordingEngine(rejects = setOf("(unclosed"))).check(p, bind), "no half answer: write does not match, read cannot be read")
        val topic = permissions(write = cell("^no$"), topic = listOf(topicRow("amq.topic", write = cell("(unclosed"))))
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.TopicWrite), matcher(RecordingEngine(rejects = setOf("(unclosed"))).check(topic, publish("amq.topic", routingKey = "a.b")))
    }

    @Test
    fun `a pattern the engine rejects is unreadable`() {
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), matcher(RecordingEngine(rejects = setOf("(?=ex)ex"))).check(permissions(write = cell("(?=ex)ex")), publish("ex")))
    }

    @Test
    fun `search is unanchored and case sensitive, the anchors are the pattern's own`() {
        val engine = RecordingEngine()
        assertTrue(matcher(engine).check(permissions(write = cell("ex")), publish("my-ex.orders")) is AccessAnswer.WouldMatch)
        assertTrue(matcher(engine).check(permissions(write = cell("ex")), publish("EX")) is AccessAnswer.NoMatch)
        assertTrue(matcher(engine).check(permissions(write = cell("^ex$")), publish("ex.orders")) is AccessAnswer.NoMatch)
    }

    // ------------------------------------------------------------ limits and KnownDivergences

    @Test
    fun `a pattern over 256 characters, or a name or routing key over 255, is unreadable before the engine is asked`() {
        val limit = "a?".repeat(127) + "a*" // 256 characters, matches the name "a"
        assertEquals(AccessLimits.MAX_PATTERN, limit.length)
        assertTrue(matcher().check(permissions(write = cell(limit)), publish("a")) is AccessAnswer.WouldMatch, "256 characters are read")
        val engine = RecordingEngine()
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), matcher(engine).check(permissions(write = cell(limit + "a")), publish("a")))
        assertTrue(engine.compiled.isEmpty())
        val name = "a".repeat(AccessLimits.MAX_NAME)
        assertTrue(matcher().check(permissions(), publish(name)) is AccessAnswer.WouldMatch, "255 characters are read")
        assertTrue(matcher().check(permissions(), publish(name + "a")) is AccessAnswer.UnreadablePattern)
        val topic = permissions(topic = listOf(topicRow("amq.topic")))
        assertTrue(matcher().check(topic, publish("amq.topic", routingKey = name + "a")) is AccessAnswer.UnreadablePattern)
    }

    @Test
    fun `a synthetic KnownDivergences entry is unreadable only when the name meets the condition`() {
        val divergences = KnownDivergences(listOf(Divergence("$", DivergenceWhen.NameEndsWithLineFeed, "row-a"), Divergence(".", DivergenceWhen.NameHasNonAscii, "row-b")))
        val m = matcher(divergences = divergences)
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), m.check(permissions(write = cell("^ex$")), publish("ex\n")))
        assertTrue(m.check(permissions(write = cell("^ex$")), publish("ex")) is AccessAnswer.WouldMatch, "no line feed, no divergence")
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), m.check(permissions(write = cell("^.$")), publish("é")))
        assertTrue(m.check(permissions(write = cell("^.$")), publish("e")) is AccessAnswer.WouldMatch, "ASCII name, no divergence")
        assertEquals(AccessAnswer.UnreadablePattern(PatternKind.TopicWrite), m.check(permissions(topic = listOf(topicRow("amq.topic", write = cell("^.$")))), publish("amq.topic", routingKey = "é")), "the routing key is a name too")
    }

    @Test
    fun `every inline flag group with i is the measured case-folding divergence on a non-ASCII name, other flags are not`() {
        val m = matcher(divergences = KnownDivergences.Measured)
        for (pattern in listOf("(?i)Á", "(?si)Á", "(?is)Á", "(?im)Á", "(?i:Á)")) {
            assertEquals(AccessAnswer.UnreadablePattern(PatternKind.Write), m.check(permissions(write = cell(pattern)), publish("あ")), pattern)
        }
        assertTrue(m.check(permissions(write = cell("(?s)Á")), publish("あ")) is AccessAnswer.NoMatch, "no i flag, no divergence")
        assertTrue(m.check(permissions(write = cell("(?i)ex")), publish("EX")) is AccessAnswer.WouldMatch, "ASCII name, no divergence")
    }

    @Test
    fun `the measured divergences name their live row and every entry is reachable`() {
        val entries = KnownDivergences.Measured.entries
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.row.startsWith("NEW-") && it.construct.isNotEmpty() }, "each entry names the live row (NEW-nnn) that proved it")
        val ids = AccessMatchCases.all.map { it.id }.toSet()
        for (entry in entries) for (id in Regex("NEW-\\d{3}").findAll(entry.row).map { it.value }) assertTrue(id in ids, "$id of ${entry.construct} is not a row of the table")
    }
}
