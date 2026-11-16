package dev.caerbannog.core.planner.fakes

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.MigrationValidationResult
import dev.caerbannog.core.planner.DraftLoad
import dev.caerbannog.core.planner.DraftMoveResult
import dev.caerbannog.core.planner.PlanDraftStore
import dev.caerbannog.core.planner.TopologyPlan

/**
 * M9-02: the draft file of this machine, in memory, honest like `FilePlanDraftStore`: the text is the one a real file would hold
 * (`MigrationParser.exportToJson("draft", description, actions)`), it is read back with `MigrationParser.parse`, a text that does not parse
 * is [DraftLoad.Unreadable] (a test shows the app never wrote over it with [savedTexts] and [rawText]), [moveAside] renames and never
 * deletes or overwrites, and a write can be refused. Everything the app did is recorded: [savedTexts], [clears], [moveAsides].
 *
 * [path] stands for `~/.caerbannog/planner-draft.json`: the app's own data, never the migrations folder.
 */
class FakePlanDraftStore(
    val path: String = "/home/test/.caerbannog/planner-draft.json",
    private val asideName: String = "planner-draft.json.unreadable-20261116-100000",
) : PlanDraftStore {
    /** The bytes of the draft file as text; null = no file. */
    @Volatile
    var rawText: String? = null
        private set

    /** Every text [save] wrote, in order. A refused write is not in it. */
    val savedTexts: MutableList<String> = mutableListOf()
    var clears: Int = 0
        private set
    var moveAsides: Int = 0
        private set
    var loads: Int = 0
        private set

    /** Files moved aside: new name to the original text. */
    val asideFiles: MutableMap<String, String> = linkedMapOf()

    /** A write ([save]) answers false and changes nothing. */
    var refuseWrites: Boolean = false

    /** [moveAside] answers [DraftMoveResult.Failed] and changes nothing. */
    var refuseMoveAside: Boolean = false

    /** Puts [text] in the draft file as it is, valid or not (what was on disk before the app started). */
    fun putRaw(text: String) {
        rawText = text
    }

    /** A draft file that is not a plan. */
    fun putUnreadable(text: String = "{ this is not a plan") = putRaw(text)

    /** A valid draft file for [description] and [actions], as a previous run would have left it. */
    fun putDraft(description: String, actions: List<MigrationAction>) {
        rawText = MigrationParser.exportToJson(DRAFT_VERSION, description, actions)
    }

    val hasDraft: Boolean get() = rawText != null

    override suspend fun load(): DraftLoad {
        loads++
        val text = rawText ?: return DraftLoad.None
        val parsed = MigrationParser.parse(text) as? MigrationValidationResult.Valid ?: return DraftLoad.Unreadable(path, UNREADABLE_REASON)
        // A plan never holds a broker-level action (user, virtual host, permissions): such a file is reported and kept, not shown or re-saved.
        if (parsed.migration.up.any { it.isBrokerLevel }) return DraftLoad.Unreadable(path, ACCESS_REASON)
        return DraftLoad.Loaded(TopologyPlan.of(parsed.migration.description, parsed.migration.up, defaultVhost = "/"))
    }

    override suspend fun save(plan: TopologyPlan): Boolean {
        if (refuseWrites) return false
        val text = MigrationParser.exportToJson(DRAFT_VERSION, plan.description, plan.steps.map { it.action })
        rawText = text
        savedTexts += text
        return true
    }

    override suspend fun clear() {
        clears++
        rawText = null
    }

    override fun asideFileName(): String = asideName

    override suspend fun moveAside(): DraftMoveResult {
        moveAsides++
        val text = rawText ?: return DraftMoveResult.Failed("There is no draft file to move aside")
        if (refuseMoveAside) return DraftMoveResult.Failed("The draft file could not be moved aside")
        check(asideName !in asideFiles) { "the fake never overwrites: $asideName exists" }
        asideFiles[asideName] = text
        rawText = null
        return DraftMoveResult.Moved(asideName)
    }

    companion object {
        const val DRAFT_VERSION = "draft"
        const val UNREADABLE_REASON = "The file is not a valid plan"
        const val ACCESS_REASON = "This file holds password hashes. Edit it outside the app."
    }
}
