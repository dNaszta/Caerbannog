package dev.caerbannog.core.migrate3x

/** The numbers in the header line ("3 virtual hosts, 482 queues, ..."), counted from the held document, not from the findings. */
data class DocumentCounts(val virtualHosts: Int, val queues: Int, val exchanges: Int, val bindings: Int)

/** A severity chip: [count] is the number of findings of that severity in the whole report and ignores the filter. */
data class ChipView(val severity: Severity, val count: Int, val selected: Boolean)

/** One way out of the message-loss problem; exactly the new broker with a Shovel is [recommended] (never an in-place upgrade). */
data class FixView(val title: String, val text: String, val recommended: Boolean)

/** How many queue names the loss block shows before "Show all N". */
const val LOSS_PREVIEW_COUNT: Int = 10

/**
 * The block above the groups. Names are plain text (escaped like the text export). [preview] holds the first [LOSS_PREVIEW_COUNT] names,
 * [queueAt] any name (the expanded lazy list), [copyText] ALL names one per line.
 */
class LossBlockView(
    val title: String,
    val queueCount: Int,
    val preview: List<String>,
    val detection: String,
    val fixes: List<FixView>,
    val copyText: String,
    private val nameAt: (Int) -> String,
) {
    fun queueAt(index: Int): String = nameAt(index)
    override fun toString(): String = "LossBlockView($title, $queueCount queues)"
}

/** One group of findings: key = rule plus severity. Closed groups hold no rows ([ReportView.rows] is empty). */
data class GroupView(val key: String, val title: String, val severity: Severity?, val findingClass: FindingClass?, val count: Int, val open: Boolean)

/** [user] is set for a user row: tags, algorithm and the fixed text [MigrateFromThreeTexts.HASH_HIDDEN]; there is no field that could hold a hash. */
data class RowView(
    val kind: ItemKind,
    val name: String?,
    val message: String,
    val why: String,
    val proposal: String,
    val verified: Boolean,
    val user: UserRowView?,
    /** M8-06b STUB default: the review item of a row that needs a decision. */
    val item: ReviewItem? = null,
    /** M8-06b STUB default: the decision pill of such a row. */
    val decision: DecisionPill? = null,
)

data class UserRowView(val tags: List<String>, val algorithm: String?, val hashText: String)

/** Rows of one open group, built on demand: [at] builds one [RowView]. */
interface RowSource {
    val size: Int
    fun at(index: Int): RowView

    companion object {
        val EMPTY: RowSource = object : RowSource {
            override val size: Int = 0
            override fun at(index: Int): RowView = throw IndexOutOfBoundsException("no rows")
        }
    }
}

/** What the screen shows for a report. [chips] ignore the filter, [lossBlock] ignores it too. */
class ReportView(
    val fileLabel: String,
    /** M8-06c: the source card (kind, label, lock, "Analyse again"); [fileLabel] stays and equals the label for a file. */
    val source: SourceCardView,
    val sourceVersion: String?,
    val singleVirtualHost: String?,
    val summary: ReportSummary,
    val summaryLine: String,
    val counts: DocumentCounts,
    /** The header line under the file name, for example "3 virtual hosts, 482 queues, 61 exchanges, 1,204 bindings". */
    val countsLine: String,
    val lossBlock: LossBlockView?,
    val accessNote: String?,
    val chips: List<ChipView>,
    val groups: List<GroupView>,
    private val rowsOf: (String) -> RowSource,
) {
    /** The rows of the group with [groupKey]; empty for a closed or unknown group. */
    fun rows(groupKey: String): RowSource = rowsOf(groupKey)

    override fun toString(): String = "ReportView($fileLabel, ${groups.size} groups)"
}
