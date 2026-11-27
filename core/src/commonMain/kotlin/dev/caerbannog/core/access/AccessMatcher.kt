package dev.caerbannog.core.access

/**
 * Pure: patterns in, answer out; no gateway, no request, no `java.*`. The match preview is a prediction, the broker decides.
 * Check order (brief item 2): (a) permissions not available; (b) the vhost row; (c) per needed cell `hidden` FIRST (the word "hidden" is a valid regular
 * expression); (d) the empty pattern is "no permission" (NoMatch, nothing allowed); (e) length limits, engine compile, [KnownDivergences]; (f) unanchored,
 * case-sensitive search. WouldMatch only when every needed cell matched; an unreadable cell anywhere wins over a NoMatch of another cell (no half answers).
 */
class AccessMatcher(
    private val engine: PatternEngine = defaultPatternEngine(),
    private val divergences: KnownDivergences = KnownDivergences.Measured,
) {
    fun check(source: PermissionsSource, question: AccessQuestion): AccessAnswer = when (source) {
        is PermissionsSource.NotAvailable -> AccessAnswer.PermissionsNotReadable(source.reason)
        is PermissionsSource.Available -> check(source.permissions, question)
    }

    fun check(permissions: UserPermissions, question: AccessQuestion): AccessAnswer {
        val needs = needsOf(question)
        val row = permissions.rows.firstOrNull { it.vhost == question.vhost }
            ?: return noRowAnswer(needs.first())
        val outcomes = needs.map { need -> evaluate(need, cellOf(row, permissions.topicRows, question.vhost, need)) }
        outcomes.firstOrNull { it is Outcome.Unreadable }?.let { return AccessAnswer.UnreadablePattern((it as Outcome.Unreadable).kind) }
        outcomes.firstOrNull { it is Outcome.Refused }?.let { return AccessAnswer.NoMatch((it as Outcome.Refused).hit) }
        return AccessAnswer.WouldMatch(outcomes.filterIsInstance<Outcome.Allowed>().map { it.hit })
    }

    private fun noRowAnswer(first: Need): AccessAnswer.NoMatch =
        AccessAnswer.NoMatch(Hit(first.kind, DisplayPattern(PermissionsTexts.NO_PERMISSION), first.exchange, first.name), noRowForVhost = true)

    /** One cell the question needs: which [kind] of pattern, the [name] it is applied to, and the topic row's [exchange] (null for the vhost kinds). */
    private data class Need(val kind: PatternKind, val name: String, val exchange: String? = null)

    private sealed interface Outcome {
        data class Allowed(val hit: Hit) : Outcome

        data class Refused(val hit: Hit) : Outcome

        data class Unreadable(val kind: PatternKind) : Outcome

        /** A topic need of an exchange with no topic row: nothing limits it. */
        data object NotNeeded : Outcome
    }

    /**
     * The action-to-pattern table (data, one line per rule; rows of `s-i-pattern-rows.tsv`, S-I, and the M10-04 rows of `AccessMatchCases`).
     * The topic cells are added only when the user has a topic row for that exchange; with no row nothing is limited (measured).
     */
    private fun needsOf(question: AccessQuestion): List<Need> {
        val name = question.name
        val key = question.routingKey.orEmpty()
        return when (val action = question.action) {
            AccessAction.PublishToExchange -> { // S-I "basic.publish to exchange": write on the exchange; the default exchange is checked as amq.default
                val exchange = exchangeName(name)
                listOf(Need(PatternKind.Write, exchange), Need(PatternKind.TopicWrite, key, exchange))
            }
            AccessAction.ReadFromQueue -> listOf(Need(PatternKind.Read, name)) // S-I basic.get / basic.consume: read on the queue; no topic check on consume (M10-04)
            AccessAction.Configure -> listOf(Need(PatternKind.Configure, name)) // S-I queue.declare / exchange.declare: configure
            is AccessAction.BindQueue -> { // S-I queue.bind: write on the queue AND read on the exchange; topic read on the key (M10-04)
                val exchange = exchangeName(action.exchange)
                listOf(Need(PatternKind.Write, name), Need(PatternKind.Read, exchange), Need(PatternKind.TopicRead, key, exchange))
            }
            AccessAction.PublishToStream -> listOf(Need(PatternKind.Write, name)) // S-I stream publisher: write on the stream name
            AccessAction.ReadFromStream -> listOf(Need(PatternKind.Read, name)) // S-I stream subscribe: read on the stream name
        }
    }

    private fun exchangeName(asked: String): String = if (asked.isEmpty()) DEFAULT_EXCHANGE else asked

    /** The cell for [need], or null for a topic need with no topic row (nothing is limited then). */
    private fun cellOf(row: PermissionRow, topicRows: List<TopicPermissionRow>, vhost: String, need: Need): PatternCell? = when (need.kind) {
        PatternKind.Configure -> row.configure
        PatternKind.Write -> row.write
        PatternKind.Read -> row.read
        PatternKind.TopicWrite -> topicRows.firstOrNull { it.vhost == vhost && it.exchange == need.exchange }?.write
        PatternKind.TopicRead -> topicRows.firstOrNull { it.vhost == vhost && it.exchange == need.exchange }?.read
    }

    private fun evaluate(need: Need, cell: PatternCell?): Outcome {
        if (cell == null) return Outcome.NotNeeded
        val shown = DisplayPattern(if (cell.reading is PatternReading.NoPermission) PermissionsTexts.NO_PERMISSION else cell.text)
        val hit = Hit(need.kind, shown, need.exchange, need.name)
        return when {
            cell.hidden -> Outcome.Unreadable(need.kind)
            cell.reading is PatternReading.NoPermission -> Outcome.Refused(hit)
            else -> matchOf(need, cell.text, hit)
        }
    }

    private fun matchOf(need: Need, pattern: String, hit: Hit): Outcome {
        if (pattern.length > AccessLimits.MAX_PATTERN || need.name.encodeToByteArray().size > AccessLimits.MAX_NAME) return Outcome.Unreadable(need.kind)
        val compiled = (engine.compile(pattern) as? PatternCompilation.Compiled)?.pattern ?: return Outcome.Unreadable(need.kind)
        if (divergences.entries.any { it.applies(pattern, need.name) }) return Outcome.Unreadable(need.kind)
        return if (compiled.containsMatchIn(need.name)) Outcome.Allowed(hit) else Outcome.Refused(hit)
    }

    private fun Divergence.applies(pattern: String, name: String): Boolean = pattern.contains(construct) && when (whenName) {
        DivergenceWhen.NameHasNonAscii -> name.any { it.code > 0x7F }
        DivergenceWhen.NameEndsWithLineFeed -> name.endsWith("\n")
    }

    private companion object {
        const val DEFAULT_EXCHANGE = "amq.default"
    }
}
