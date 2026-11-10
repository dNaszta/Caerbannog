package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlinx.serialization.json.JsonPrimitive
import java.util.Spliterator
import java.util.function.Consumer

/**
 * M8-06e: a list that counts every way to walk it (iterator, listIterator, get, subList, forEach, spliterator and so stream, indexOf, contains). Wraps
 * `report.findings`; no production hook. [reset] after the held analysis is built; [total] is 0 when nothing walked the findings since.
 */
internal class CountingList<T>(private val delegate: List<T>) : List<T> by delegate {
    private val counts = LinkedHashMap<String, Int>()

    /** The findings without counting (for the expected values of a test). */
    val raw: List<T> get() = delegate

    val total: Int get() = counts.values.sum()
    fun reset() = counts.clear()
    fun describe(): String = if (counts.isEmpty()) "no pass" else counts.entries.joinToString { "${it.key} x${it.value}" }

    private fun hit(what: String) { counts[what] = (counts[what] ?: 0) + 1 }

    override fun iterator(): Iterator<T> { hit("iterator"); return delegate.iterator() }
    override fun listIterator(): ListIterator<T> { hit("listIterator"); return delegate.listIterator() }
    override fun listIterator(index: Int): ListIterator<T> { hit("listIterator"); return delegate.listIterator(index) }
    override fun get(index: Int): T { hit("get"); return delegate[index] }
    override fun subList(fromIndex: Int, toIndex: Int): List<T> { hit("subList"); return delegate.subList(fromIndex, toIndex) }
    override fun indexOf(element: T): Int { hit("indexOf"); return delegate.indexOf(element) }
    override fun lastIndexOf(element: T): Int { hit("lastIndexOf"); return delegate.lastIndexOf(element) }
    override fun contains(element: T): Boolean { hit("contains"); return delegate.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean { hit("containsAll"); return delegate.containsAll(elements) }
    override fun spliterator(): Spliterator<T> { hit("spliterator"); return delegate.spliterator() }
    override fun forEach(action: Consumer<in T>) { hit("forEach"); delegate.forEach(action) }
    override fun toString(): String = "CountingList(${delegate.size})"
}

/** M8-06e: reports for the grouping tests, built through the real parser and analyzer, then multiplied with new names and ids (never hand-made rules). */
internal object GroupingFixtures {
    /** [copies] copies of the findings of [base], each copy with its own names (suffix `-n`) and ids, so that every copy is its own review item. */
    fun multiply(base: HeldAnalysis, copies: Int): List<Finding> =
        (0 until copies).flatMap { n -> base.report.findings.map { it.copy(id = "${it.id}#$n", name = it.name?.let { name -> "$name-$n" }) } }

    private fun bigBase(): HeldAnalysis = ScreenFiles.held(DefinitionsFiles.classicQueues(2_000))

    /**
     * 240,000 findings of classic queues (each queue has three findings, message loss among them, plus a few global ones): about 80,000 queues, each its own
     * review item. The brief says "for example 120,000 queues with 2 findings each"; the analyzer gives a plain classic queue three. Not cached: a static
     * copy would stay in the heap of the whole test JVM (the default test heap is small).
     */
    fun bigFindings(): List<Finding> = bigBase().let { base -> multiply(base, 241_000 / base.report.findings.size + 1).take(240_000) }

    const val NOT_REPLICATED = "classic-not-replicated:warning"

    fun keyOf(f: Finding) = "${f.rule}:${f.severity.name.lowercase()}"

    /** A held analysis of the first [n] of the big findings, no counter. */
    fun bigPart(n: Int): HeldAnalysis = bigBase().let { HeldAnalysis("part.json", it.document, it.report.copy(findings = multiply(it, n / it.report.findings.size + 1).take(n))) }

    /** The 240,000 findings behind a [CountingList]; the counter is reset after the held analysis (review and index) is built. */
    fun bigCounted(): Pair<HeldAnalysis, CountingList<Finding>> {
        val base = bigBase()
        val counting = CountingList(multiply(base, 241_000 / base.report.findings.size + 1).take(240_000))
        val held = HeldAnalysis("big.json", base.document, base.report.copy(findings = counting))
        counting.reset()
        return held to counting
    }

    /** Several rules, all three severities, message loss (one name with a newline and a bidi override), policies, exchanges and review items; a few thousand findings. */
    fun mixed(copies: Int = 150): HeldAnalysis {
        val text = DefinitionsFiles.file(
            queues = listOf(
                DefinitionsFiles.queue("orders"),
                DefinitionsFiles.queue("jobs", arguments = mapOf("x-max-priority" to JsonPrimitive(5))),
                DefinitionsFiles.queue("temp", durable = false),
                DefinitionsFiles.queue("modern", type = "quorum"),
                DefinitionsFiles.queue("odd\nname‮"),
            ),
            exchanges = listOf(DefinitionsFiles.exchange("events"), DefinitionsFiles.exchange("audit", "fanout")),
            bindings = listOf(DefinitionsFiles.binding("events", "modern", "k")),
            policies = listOf(
                DefinitionsFiles.policy("ha-all", "^orders", mapOf("ha-mode" to JsonPrimitive("all"))),
                DefinitionsFiles.policy("lookahead", "^(?!amq\\.).*", mapOf("max-length" to JsonPrimitive(10))),
            ),
        )
        val base = ScreenFiles.held(text, "mixed.json")
        return HeldAnalysis("mixed.json", base.document, base.report.copy(findings = multiply(base, copies)))
    }
}

/**
 * M8-06e: a copy of the logic of `ReportViewBuilder` as it was before the group index (M8-06a to 06c): `groupFindings`, `lossBlock`, the Skipped group,
 * the pills. It is the reference the new builder must equal. Slow on purpose (it walks the findings); do not "improve" it.
 */
internal object ReferenceGrouping {
    private val chipOrder = listOf(Severity.Blocker, Severity.Warning, Severity.Info)

    class Expected(
        val groups: List<GroupView>,
        /** Every row of every group of [groups], open or not, by key. */
        val rows: Map<String, List<RowView>>,
        val lossNames: List<String>?,
        val chips: List<ChipView>,
    )

    /** The rows are marked with the finding id as name so that the order is checked, not only the count. */
    val marker: (Finding) -> RowView = { f -> RowView(f.kind, f.id, f.message, "", "", true, null) }

    private fun skippedItems(review: ConversionReview): List<ReviewItem> = review.items.filter { review.decisionOf(it) == ItemDecision.Skip }

    private fun keyOf(finding: Finding): String = "${finding.rule}:${finding.severity.name.lowercase()}"

    private fun groupFindings(findings: List<Finding>, skipped: List<ReviewItem>): Map<String, List<Finding>> {
        val groups = HashMap<String, MutableList<Finding>>()
        val skippedSet = skipped.toHashSet()
        for (finding in findings) {
            if (skippedSet.isNotEmpty() && ReviewRules.isReviewFinding(finding) && ReviewItem(finding.kind, finding.vhost, finding.name) in skippedSet) continue
            groups.getOrPut(keyOf(finding)) { mutableListOf() } += finding
        }
        val order = compareBy<Map.Entry<String, List<Finding>>>(
            { if (it.value.first().findingClass == FindingClass.MessageLoss) 0 else 1 },
            { it.value.first().severity.ordinal },
            { it.key },
        )
        return groups.entries.sortedWith(order).associate { it.key to it.value }
    }

    private fun decorate(row: RowView, finding: Finding, review: ConversionReview): RowView {
        if (!ReviewRules.isReviewFinding(finding) || !ReviewRules.needsDecision(finding)) return row
        val item = ReviewItem(finding.kind, finding.vhost, finding.name)
        if (review.refusalOf(item, ItemDecision.Skip) != null) return row
        return row.copy(item = item, decision = ReviewViewBuilder.pillOf(review.decisionOf(item)))
    }

    private fun skippedRow(review: ConversionReview, item: ReviewItem): RowView {
        val finding = review.findingsOf(item).firstOrNull(ReviewRules::needsDecision)
        val was = finding?.let { "${it.severity.name}, ${escapeForText(it.message)}" }
        return RowView(
            kind = item.kind,
            name = item.name?.let { escapeForText(it) },
            message = "Left out of the migration." + (was?.let { " Was: $it" } ?: ""),
            why = "",
            proposal = "",
            verified = finding?.verified ?: true,
            user = null,
            item = item,
            decision = DecisionPill.Skipped,
        )
    }

    private fun count(summary: ReportSummary, severity: Severity): Int = when (severity) {
        Severity.Blocker -> summary.blockers
        Severity.Warning -> summary.warnings
        Severity.Info -> summary.infos
    }

    fun expected(held: HeldAnalysis, selected: Set<Severity>, open: Set<String>): Expected {
        val review = held.review
        val skipped = skippedItems(review)
        val visible = groupFindings(held.report.findings, skipped).filterValues { it.first().severity in selected }
        val groups = visible.map { (key, findings) ->
            val first = findings.first()
            GroupView(key, MigrateFromThreeTexts.ruleTitle(first.rule), first.severity, first.findingClass, findings.size, key in open)
        }.toMutableList()
        val rows = visible.mapValues { (_, findings) -> findings.map { decorate(marker(it), it, review) } }.toMutableMap()
        if (skipped.isNotEmpty()) {
            groups += GroupView(SKIPPED_GROUP_KEY, MigrateFromThreeTexts.SKIPPED_GROUP_TITLE, null, null, skipped.size, SKIPPED_GROUP_KEY in open)
            rows[SKIPPED_GROUP_KEY] = skipped.map { skippedRow(review, it) }
        }
        val names = held.report.findings.filter { it.findingClass == FindingClass.MessageLoss }.map { escapeForText(it.name.orEmpty()) }
        return Expected(
            groups, rows, names.takeIf { it.isNotEmpty() },
            chipOrder.map { ChipView(it, count(held.report.summary, it), it in selected) },
        )
    }
}
