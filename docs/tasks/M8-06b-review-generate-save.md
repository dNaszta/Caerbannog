# Migrate-from-3.x screen, part b: review, generate, save

Milestone: M8    Depends on: M8-06a, M8-05, M8-05b, M5-08 (merged); M8-06e (S, should land first, see "Order with M8-06e"); owner review of `docs/ux/Migrate3x.dc.html`    Size: M

## Goal
The user decides each item that needs a decision (accept with the ordering tick, choose an alternative, skip, or replace with own migration actions), sees "N items need a decision", generates the migration once every item is decided, and the files are saved into the migrations folder, where the Migrations screen lists them as pending for a connection on the matching virtual host.

## Why
Spec "Review and the generated migration" and decisions 2, P3, P15. The core (M8-05, M8-05b) is merged; this slice puts it on the screen.

## Scope
In:
1. Six small `core` additions, test first (the merged `ConversionReview` and `MigrationParser` have no way to feed the sheet). Exact signatures below, "Core additions". These are the only changes to merged core code, apart from the two named in item 2.
2. `MigrateFromThreeViewModel` extended: `MigrateFromThreeState.Report` gets `review: ReviewState`; `ReportView`, `GroupView` and `RowView` get the small additions listed in "View model API". `HeldAnalysis` gets the review.
3. Generate and save through `MigrationFolderPorts` (see "Generate and save").
4. `ui`: review sheet, replace sheet, decision bar, row pills, skipped group, result panel, folder-problem panel, second-generation confirmation, "unverified" marker with tooltip. Test tags in "UI tags".
Out: access items (M8-06d); the live source (M8-06c); any apply (the Migrations screen applies); any Shovel; a delete or an overwrite of any saved file; a "close" or "remove" of a saved migration.

## Core additions (exact; test first, `ConversionReviewViewApiTest` and `ActionsParserTest`)
All in `core/commonMain`; none touches `java.*`. Behaviour of `decide`, `generate`, `pending`, `items` stays as merged (the 19 `@m8-05` scenarios stay unedited and green).

In `dev.caerbannog.core.migrate3x.ConversionReview`:
```kotlin
/** Every finding of [item] that can change its declaration (any severity, any `appliesWhen`), report order. Empty for an unknown item. */
fun findingsOf(item: ReviewItem): List<Finding>

/**
 * What `generate` would write for [item] if it were decided with [accept] (default: the default proposal of every finding, no tick).
 * Pure: no state change, no refusal check (a manual-only item returns its declaration as the file holds it), never a delete.
 * Empty when the item would not be written (name that cannot be written, a policy that holds only dropped keys, unknown item).
 * Throws IllegalArgumentException for a chosen index out of range (the UI offers only valid ones).
 * Same code path as `generate` (extract `MigrationGenerator.actionsFor(document, findingsByItem, item, accept)`; a test asserts
 * `previewActions(item, accept)` equals the item's actions inside `generate` after `decide(item, accept)`).
 */
fun previewActions(item: ReviewItem, accept: ItemDecision.Accept = ItemDecision.Accept()): List<MigrationAction>

/**
 * Pure check, no state change: the refusal `decide(item, decision)` would give, or null when it would store it.
 * `decide` is rewritten to call this, so the rule lives once. Needed so the sheet knows "Accept enabled?" without deciding.
 */
fun refusalOf(item: ReviewItem, decision: ItemDecision): DecisionRefusal?

/** Removes the decision of [item] (back to undecided). True for a review item (also when it had no decision); false for anything else. */
fun undecide(item: ReviewItem): Boolean
```
Decided (architect): `undecide` returns `Boolean`, not `DecisionResult` (the brief of round 0 said `Decided`; "Decided" for a removal reads wrong and `Refused` has no real reason to carry). `refusalOf` is the sixth addition and is new (the earlier text said the view model "asks `decide`", which stores the decision on success and cannot be a check).

In `dev.caerbannog.core.migrations` (`MigrationParser.kt`):
```kotlin
sealed interface ActionsParseResult {
    data class Parsed(val actions: List<MigrationAction>) : ActionsParseResult
    /** [reason] never holds input text; per-action reasons are the parser's own. */
    data class Invalid(val reason: String) : ActionsParseResult
}

object MigrationParser {
    /** [text] is a JSON array of migration actions (the `up` array). Empty list is Parsed (a replacement that writes nothing is allowed). */
    fun parseActions(text: String): ActionsParseResult
    /** The inverse for the prefill: a pretty JSON array of [actions], same fields and the same omission of `"vhost": "/"` as `exportToJson`. */
    fun actionsToJson(actions: List<MigrationAction>): String
}
```
Reasons: not JSON "the text is not valid JSON"; too deep "the text is nested too deep"; not an array "the text is not a list of actions"; an element that is not an object or has no known action, a codec refusal (a name like `..`), and the `RerunSafety` conflict: exactly the strings `parse` gives (extract the shared action loop of `parseFields` into an `internal fun readActions(array: JsonArray): ActionsParseResult`, so `parse` and `parseActions` cannot drift). Uses `parseUntrustedJson` with `MAX_FILE_DEPTH`. The unknown-action phrase keeps the `echoableName`-style rule (hostile action names are not echoed). `exportToJson` reuses the same writer as `actionsToJson`. Round trip test: `parseActions(actionsToJson(a)) == Parsed(a)` for every action type the codecs know.

## View model API (exact)
State and events extend the merged M8-06a view model (`MigrateFromThreeViewModel`, state `MigrateFromThreeState`). Nothing is renamed. Constructor gets one more port, `folders: MigrationFolderPorts` (the same instance the Migrations screen uses, wired in `desktopApp/MigrateFromThreeWiring.kt`; no connection is needed). All new types live in `core/.../migrate3x/` (`ReviewState.kt`, `ReviewViewBuilder`/`ItemSheetBuilder` internal pure builders, texts in `MigrateFromThreeTexts`).

```kotlin
data class Report(val view: ReportView, val notice: ExportNotice?, val review: ReviewState) : MigrateFromThreeState

data class ReviewState(
    val itemCount: Int,            // items that need a decision (ConversionReview.items.size)
    val pendingCount: Int,         // undecided items only; skipped counts as decided
    val acceptedCount: Int, val skippedCount: Int, val replacedCount: Int,
    val pendingLine: String,       // "3 items need a decision" | "1 item needs a decision" | "Every item is decided" | "No item needs a decision"
    val decidedLine: String?,      // "381 of 381: 340 accepted, 38 skipped (in the Skipped group), 3 replaced." when pendingCount == 0 and itemCount > 0
    val canGenerate: Boolean,      // pendingCount == 0 and generation is not Saving
    val sheet: ItemSheet?,
    val generation: GenerationState,
)

data class ItemSheet(
    val item: ReviewItem,
    val itemNumber: Int, val itemTotal: Int,      // "item 14 of 381": 1-based index in ConversionReview.items
    val findings: List<FindingChoice>,
    val ticks: List<TickView>,                    // only the ticks the chosen proposals require
    val manualSteps: List<ManualStepView>,        // Proposal.Manual of the chosen proposals, title and text
    val acceptBlock: DecisionRefusal?,            // from refusalOf: null, TickMissing or ManualNeeded (shown as data, never re-implemented)
    val canAccept: Boolean,                       // acceptBlock == null
    val replace: ReplaceEditor?,                  // non-null while the Replace sheet is shown
    val decided: DecidedAs?,                      // the stored decision when the item was decided (Change), else null
)
data class FindingChoice(val findingId: String, val severity: Severity, val message: String, val why: String,
    val verified: Boolean, val options: List<ProposalOption>, val chosen: Int)   // chosen = index in [proposal] + alternatives
data class ProposalOption(val index: Int, val title: String, val isDefault: Boolean)  // title = Proposal.describe(), escaped
data class TickView(val acknowledgement: Acknowledgement, val text: String, val ticked: Boolean)  // text = Acknowledgement.text
data class ManualStepView(val findingId: String, val title: String, val text: String)
data class ReplaceEditor(val text: String, val error: String?, val hint: String)
sealed interface DecidedAs { data class Accepted(val ticked: Boolean) ; data class Replaced(val actionCount: Int) ; data object Skipped }
```
Findings with `Severity.Info` or `appliesWhen != Always` appear in the sheet as information; message-loss findings are not review findings and never block `Accept`.

Group, row and pill additions (`ReportView.kt`, `ReportViewBuilder`):
- `const val SKIPPED_GROUP_KEY = "skipped"`. Keys of the other groups stay `<rule>:<severity lowercase>` (no colon in `skipped`, so no collision). `GroupView.severity` and `GroupView.findingClass` become nullable (null for the skipped group; the 06a UI hides the severity label when null).
- The skipped group exists only while at least one item has `decisionOf(item) == Skip`, is the last element of `ReportView.groups`, is closed by default, ignores `selected` (chips), and opens and closes with the existing `toggleGroup("skipped")`. `count` is the number of skipped items. Its rows: `RowView(kind, name, message = "Left out of the migration. Was: <Severity>, <message of the item's first qualifying finding>", proposal = "", why = "", verified, user = null, item, decision = DecisionPill.Skipped)`.
- A skipped item is removed from every rule group (rows and `count`); chips and `summary` still count the file's findings. `build` takes the skipped items from `held.review`, not from a new parameter, so `toggleSeverity`, `toggleGroup` and every decision event call the same `rebuild()`.
- `RowView` gets `item: ReviewItem?` (non-null when the finding needs a decision, so the row offers "Review") and `decision: DecisionPill?`; `sealed interface DecisionPill { Undecided; data class Accepted(val ticked: Boolean); data class Replaced(val actionCount: Int); Skipped }` rendered as "Needs decision", "Accepted" or "Accepted, ordering tick set", "Replaced", "Skipped". Null for rows that need no decision.
- `HeldAnalysis` gets `val review: ConversionReview = ConversionReview.start(document, report)` (built once on the analysis dispatcher in `showReport`; the held analysis survives a new window view model, so decisions survive too, owner decision 4). `AnalysisHolder` gets `val saved: StateFlow<Boolean>` and `fun recordSaved()` ("files were saved from this analysis"; reset by `hold` and `clear`). The review is single-threaded: only the view model's scope touches it.

Events (all no-ops outside `Report`; each decision event ends with the one `rebuild()`):
`openItem(item)`, `closeItem()`, `chooseAlternative(findingId, index)`, `setTick(ack, on)`, `accept()`, `skip()`, `unskip(item)`, `startReplace()`, `setReplaceText(text)` (stores the draft per item, in the view model, so Esc and reopening keep it), `submitReplace(text)`, `backToProposal()`, `showNextUndecided()` (opens the first of `review.pending`), `generate()`, `generateAgain()`, `cancelGenerateAgain()`, `chooseFolder()`, `retrySave()`, `exportFiles()`, `dismissResult()`. Existing `reset()` and `open(path)` also drop sheet, drafts and generation state.

Behaviour fixed:
- Sheet edits (chosen alternatives, ticks, Replace draft) are local to the sheet and are NOT a decision: `closeItem()` drops chosen/ticks and keeps only the Replace draft. A stored decision is never lost by closing a sheet. Opening a decided item pre-fills its stored choices (`decided` set).
- `accept()` sends `decide(item, Accept(chosen, ticks))`; `canAccept` and `acceptBlock` come from `refusalOf` with the same arguments. On `Decided` the sheet closes; if `decide` refuses anyway, the sheet stays and shows the refusal.
- `skip()` sends `Skip`; `unskip(item)` calls `undecide(item)`; opening from the Skipped group uses `openItem` (Review); a later `accept()` replaces the Skip.
- `startReplace()` shows the Replace sheet with `text = draft ?: MigrationParser.actionsToJson(review.previewActions(item, currentAccept))`. `submitReplace(text)`: `parseActions(text)`; `Invalid(reason)` shows the refusal text and keeps the text; `Parsed(actions)` sends `decide(item, Replace(actions))`; a `Refused` result shows its text; nothing is stored on any refusal. The view model never builds an action.
- No `expose(`, no hash, no password anywhere in these types (they hold finding texts and proposal sentences only; `Proposal.DeclareUser` belongs to access items, which are out). Every string taken from a file (names, messages, notes, paths) passes `escapeForText` (the M8-06a hardened function).

## Generate and save
`generate()`:
1. `review.pending` not empty: no state change except `pendingLine`; opens the next undecided item (as `showNextUndecided()`). `canGenerate` is false in that case so the button is disabled; the event still has to be safe.
2. `holder.saved` true and not confirmed: `generation = ConfirmAgain`. `generateAgain()` confirms and continues; `cancelGenerateAgain()` returns to `Idle` (owner decision 7).
3. Folder: `store.folder`, else `chooser.choose(null)`; `null` gives `Trouble(NoFolderChosen)`; a picked folder is stored in `store.folder` (shared with the Migrations screen).
4. `repositoryFor(folder).load()`: `Unavailable` gives `Trouble(Folder(path, problem.message))`; `Listed.readOnly` gives `Trouble(Folder(path, FOLDER_READ_ONLY_MESSAGE))`. Nothing is written in either case.
5. `existingVersions` = versions of `Listed.files` plus the digit-only stems of `Listed.invalid` file names (`0007.json` that is not valid still occupies 0007). `review.generate(GenerationRequest(existingVersions, source = file label))` on the analysis dispatcher. An `IllegalStateException` (version past 9999) is the one exception caught here: `Trouble(NoFreeVersion)`.
6. Each `GeneratedMigration.file.rawJson` goes through `repository.importFile` in order, in a `withContext(NonCancellable)` block (a new event never leaves half a save behind). `Valid` goes on; `Invalid(reason)` stops: `Trouble(FileRefused(version, reason), saved = the files already written, unsaved = the rest)`. Saved files are never deleted or rolled back. `retrySave()` saves the remaining files of the same result (same versions); if a version was taken meanwhile `importFile` refuses it and the trouble shows that reason; the user then uses `generateAgain()` (new versions).
7. All saved: `Done(report)`, `holder.recordSaved()`.
`chooseFolder()` (also from the trouble panel): `chooser.choose(store.folder)`; cancel changes nothing; a picked folder is stored and the flow restarts at step 3 (versions depend on the folder).
`exportFiles()` (offered in a folder trouble and in a partial save): saves the unsaved files, or when there are none (folder trouble, nothing generated yet) generates with `existingVersions = emptySet()`, through the existing `ReportFileSaver` (one save dialog per file, suggested name `<version>.json`); `Cancelled` stops quietly; `Failed(reason)` shows `The migration file could not be saved: <fixed reason>`. Result `Done(report)` with `how = Exported` ("N migrations exported", no "Open Migrations", the panel says the numbering started at 0001 because the folder could not be read when that was the cause). Never touches the migrations folder.

```kotlin
sealed interface GenerationState {
    data object Idle; data object ConfirmAgain; data object Saving
    data class Done(val report: GenerationReport) : GenerationState
    data class Trouble(val problem: SaveProblem, val saved: List<SavedFile>, val unsaved: List<SavedFile>) : GenerationState
}
sealed interface SaveProblem {
    data object NoFolderChosen; data class Folder(val path: String, val text: String)
    data class FileRefused(val version: String, val reason: String); data object NoFreeVersion
}
data class SavedFile(val version: String, val vhost: String, val description: String, val actionCount: Int, val path: String)
data class GenerationReport(val how: How, val headline: String, val files: List<SavedFile>, val leftOut: List<LeftOutRow>, val notes: List<String>, val vhostHint: String?)
data class LeftOutRow(val label: String, val text: String)
```
`SavedFile.path` is folder plus `<version>.json`, joined with the separator the folder string already uses (pure function `savedPath(folder, version)` in core, tested for `/` and `\`); for an unsaved file `path` is empty. `leftOut` = `skipped` (text "skipped by you" or "its name cannot be written into a migration") plus the notes whose `item.kind == Binding` (text as the core wrote it, escaped); `notes` = the other notes (message-loss recommendation, access note, single-vhost note), escaped. `headline` "N migrations saved" (1: "1 migration saved"), or "No migration was written: nothing is left to write" when `migrations` is empty. `vhostHint` is shown when the source has more than one vhost: "Each file applies only on a connection whose virtual host matches. A source with N virtual hosts needs one 4.x profile per virtual host."

## Fixed wording (decided; constants in `MigrateFromThreeTexts`)
| Where | Text |
| --- | --- |
| Count line | `N items need a decision` / `1 item needs a decision` / `Every item is decided` / `No item needs a decision` |
| Refusal `ReplaceHasDelete` | `A replacement must not delete anything. Nothing was stored; the item is still undecided.` |
| Refusal `ReplaceInvalid(reason)` | `The actions are refused: <reason>. Nothing was stored; the item is still undecided.` (reason as returned, escaped) |
| Refusal `parseActions` Invalid | same sentence as `ReplaceInvalid` |
| Accept blocked, `TickMissing` | `Tick "<Acknowledgement.text>" to accept` (one line per missing tick) |
| Accept blocked, `ManualNeeded` | `This item cannot be accepted as it is. Choose an automatic alternative, skip it, or replace it with your own actions.` |
| Replace hint | `Migration actions, the "up" list of a migration file. An action without "vhost" is for "/". Only actions for virtual host <vhost> are accepted.` (vhost escaped) |
| Second generation | `Migration files were already saved from this analysis. Generating again writes new files with new versions and leaves the old ones as they are. Generate again?` buttons `Generate again` / `Cancel` |
| No folder | `No folder was chosen. Nothing was saved.` |
| Folder problem | `<FolderProblem.message>` or `The folder is read-only`, then `No file was saved.`; the path shown under it; buttons `Choose another folder`, `Export the files` |
| Partial save | `<version>.json was not saved: <reason>. Saved: <versions>. Not saved: <versions>. The saved files stay in the folder.` buttons `Try again`, `Choose another folder`, `Export the files` left |
| No free version | `The migrations folder has no free version number left (the highest is 9999).` |
| Export failure | `The migration file could not be saved: <fixed reason>` |
| Result, saved | `N migrations saved` and `Nothing was sent to any broker. Apply them on a 4.x connection from the Migrations screen; each applies only on a connection whose virtual host matches.` |
| "unverified" marker | label `unverified`, tooltip `Not measured on a broker yet` (the mockup's text; closes open point 3) |
Row pills: `Needs decision`, `Accepted`, `Accepted, ordering tick set`, `Replaced`, `Skipped`. Option titles are the report's `Proposal.describe()` sentences; the mockup's second line under an option (for example "Writes declareQueue orders ...") is dropped, the mockup note is updated.

## Open points decided (architect, technical, no owner decision)
- Open point 3 (marker tooltip): decided above.
- Open point 4 (Replace restricted to the item's vhost in the UI): NO extra UI restriction. The Replace text is migration-format JSON the user may edit freely; the core already refuses another vhost and a missing `vhost` means `/`, and the sheet says so in the hint. A UI lock on a field that does not exist would be a second rule beside the core's. A scenario row already covers the refusal.
- `unskip` returns `Boolean` from the core, see above. Open point 2 (core signatures) is closed by "Core additions".
- Open point 5 (skipped group ignores the chips, chip counts include skipped) stays the architect default, no veto received.

## Order with M8-06e
M8-06e item 1 caches the grouping per held analysis; 06b changes the same builder (skipped group, skipped items removed from groups, `RowView.item` and `decision`). Collision rule: M8-06e lands first (it is S) and 06b filters the cached group lists by the skipped set (empty set: no copy, same lists). If 06b is built first, it keeps `groupFindings` as merged and M8-06e then moves the skipped filter on top of its cache; the toggle test of 06e (no regrouping) must then also hold with a skipped item present. M8-06e item 2 (the virtual host field) and 3 and 4 touch the view model's prompt, notice and `reportText`: no overlap with the events above. Whoever merges second rebases the `ReportViewBuilder` and `MigrateFromThreeViewModel` conflicts; the shared tests are `MigrateFromThreeViewModelTest` and the 06a UI tests, which stay unedited except the nullable `GroupView` fields.

## Acceptance
- BDD (`16`, tag `@m8-06b @fake-broker`, same suite `RunMigrate3xScreenAcceptanceTest`; 16 scenarios, 19 runs with the outline): "The screen counts the items that need a decision"; "Accept the default proposal of an item with its tick" (@smoke); "Choose an alternative for a finding"; "An item with only a manual fix cannot be accepted as it is"; "Skip an item and change the decision later"; "The group of skipped items is last, closed, not filtered by the chips and absent when nothing is skipped"; "Replace an item with my own actions"; "A replacement the migration format refuses is shown as an error and not stored" (4 examples); "Generate is blocked while items are undecided and says how many"; "Generate the migrations and save them into the migrations folder" (@smoke); "Generating asks for the migrations folder when none is chosen"; "A folder that cannot be written is reported and nothing is saved"; "A file that cannot be saved after another one was saved says which was saved" (new); "Generating again after files were saved asks first and writes new versions" (new, owner decision 7); "Generated files show up on the Migrations screen as pending for a matching connection"; "The result lists what was skipped and left out, and the notes". The core rules stay proven by the 19 `@m8-05` scenarios, unedited.
- Unit tests: `ConversionReviewViewApiTest` (`findingsOf`, `previewActions` incl. no delete and equal to `generate` for the same accept, `refusalOf` equals what `decide` does and changes nothing, `undecide`), `ActionsParserTest` (round trip for every action type, each reason, hostile names not echoed, `..`, deep nesting, 100,000-element array bounded), `ReviewStateTest` (counts, lines, next undecided, a decision changed later, closing a sheet loses no decision, message-loss never blocks Accept, Replace draft survives `closeItem`), `GenerateFlowTest` (versions skip existing ones including invalid file names, no folder, cancel, read-only, unavailable, second file fails after the first, `retrySave`, `exportFiles`, generate again asks, a version past 9999, a new event during saving does not cut the save), `ReviewSecretsTest` (sentinels absent from sheet state, result, saved files and `toString`; hostile name in a note and a path with a control character are escaped; no `expose(`), `SavedPathTest`.
- Compose UI tests: sheets and states 6 to 11 of the mockup; keyboard (tab order through options, tick, Accept; Esc closes the sheet and keeps the typed Replace text; Enter does not submit by accident); Accept disabled until the tick; the Replace error stays visible with the text kept; the "unverified" tooltip; 60,000 pending items and 60,000 skipped items do not compose all rows; the result with 60,000 left-out rows is lazy; narrow window (400 dp) keeps the bar and the sheet buttons reachable.
- Opt-in screenshots for the sheets (states 6, 7, 8, 9, 10, 11).
- Mutation checks: (a) Accept enabled without the tick; (b) Replace stored after a refusal; (c) `generate` allowed while items are pending; (d) a saved file overwritten (existing version reused); (e) a decision lost when the sheet closes; (f) the skipped group filtered by the chips; (g) the second generation not asking; (h) a folder problem that still writes.

## Design notes
The view model never builds an action itself: it shows `previewActions`, sends `decide` and `generate`, and saves what `generate` returns. The saved migration is an ordinary file; applying it is the Migrations screen's business (level, permission, virtual host rule, conflict rule, read-back). 3.x is never contacted (no gateway). The one-vhost-per-connection rule is stated in the result panel. Sheet building (`ItemSheetBuilder`) and the group/row additions are pure internal functions in `core` so the composables only draw; internal helpers (`ProposalChoice`, `Proposal.acknowledgement`) may be used there because they sit in the same module. The 60,000-item cases: `review.items.filter` runs per event (acceptable, one pass), rows stay lazy through `RowSource`.

## Skipped items (owner decision 6 = B, 2026-11-10)
Rules are in "View model API" (group, rows, counts). Generate is unchanged core behaviour: skipped items are left out; the result lists them in "Left out" with their bindings. The Skipped group stays visible after generating. After a generation, un-skipping and generating again follows decision 7.

## UX
`docs/ux/Migrate3x.dc.html` states 6 (accept with tick), 7 (manual fix, keep classic default), 8 (replace and its error), 9 (decided rows, Skipped group, bar), 10 (result), 11 (folder problems; the second-generation question and the partial-save panel use the state 11 card), 13 (entry). Mockup edits made by this step: option second lines dropped, partial-save and second-generation cards described in the state 10/11 notes.

## UI tags
New and fixed (existing 06a tags stay): `migrate3x-review-<groupKey>-<index>` (the Review or Change button of a row; replaces the old `migrate3x-item-<index>`, which would collide across groups), `migrate3x-pill-<groupKey>-<index>`, `migrate3x-unskip-skipped-<index>`, `migrate3x-review-sheet`, `migrate3x-sheet-close`, `migrate3x-option-<findingIndex>-<optionIndex>`, `migrate3x-tick-<Acknowledgement name>`, `migrate3x-accept`, `migrate3x-accept-block`, `migrate3x-skip`, `migrate3x-replace`, `migrate3x-replace-text`, `migrate3x-replace-error`, `migrate3x-replace-save`, `migrate3x-replace-back`, `migrate3x-unverified`, `migrate3x-pending-count`, `migrate3x-next-undecided`, `migrate3x-generate`, `migrate3x-generate-confirm`, `migrate3x-generate-cancel`, `migrate3x-result`, `migrate3x-result-open-migrations`, `migrate3x-result-left-out`, `migrate3x-trouble`, `migrate3x-trouble-choose-folder`, `migrate3x-trouble-export`, `migrate3x-trouble-retry`.

## MCP
None (UI). Reason: a human decision per item; generation writes files from a person's choices. A read-only migration MCP surface already exists in M5-09 for the generated files. No MCP tool is added for decisions, generation or saving, and none may create a Shovel.

## Open points
1. Owner decisions 6, 7, 8 of the umbrella brief are DECIDED (owner, 2026-11-10): skipped items B, generate again A, Replace editor A.
2. Everything else from round 0 is decided above. No question for the owner.
3. Unverified, kept open: how a hand-edited `Replace` behaves when it declares another queue name than the item's (the core allows it; the generator records "replaced by actions that do not declare it" for bindings). Covered by the merged `@m8-05` scenarios; the sheet shows no extra warning.

## Definition of done
Tests first and passing, the 19 `@m8-05` scenarios and `RealBrokerM7GateTest`, `RealBrokerM8GateTest` unaffected, `./gradlew test` green, screenshots looked at, spec updated, no new dependency.

## Status
- Step 1 (architect, confirm the brief, round 0, branch `m8-06b-review-generate-save`, base 3b5976f): DONE 2026-11-10. Changed: this brief (exact signatures, state and event API, wording, decisions, order with M8-06e), `tests/bdd/features/16_migrate_from_3x.feature` (rename of one scenario, two scenarios added, wording of one outline row), `tests/bdd/README.md`, `docs/tasks/README.md`, `docs/tasks/M8-06-report-and-review-ui.md` (Status, scenario count), the mockup notes. Contradictions found against the merged code: (1) the brief had the view model "ask `decide`", but `decide` stores on success: added `refusalOf`; (2) `findingsOf` and `previewActions` were not enough to prefill the Replace sheet: added `MigrationParser.actionsToJson`; (3) `ReportViewBuilder` keeps a group's `severity` and `findingClass` non-null: made nullable for the skipped group; (4) the old tag `migrate3x-item-<index>` collides across groups: rows are tagged with the group key, as M8-06a ruling 3; (5) core `GenerationNote` texts hold raw names (not escaped): the view model escapes them; (6) the scenario "nothing is half-saved" disagreed with the brief's "a partial failure says which file was saved": split into a read-only scenario (nothing written) and a partial-save scenario (saved files stay, never deleted); (7) the mockup's "Export the files" had no event: `exportFiles()` added, reusing `ReportFileSaver`; (8) the umbrella and brief said 13 `@m8-06b` scenarios, the file had 14: now 16. M8-06e collision: see "Order with M8-06e".
- Step 2 (test developer, tests first, round 0, branch `m8-06b-review-generate-save`, from 7afaedc): DONE 2026-11-10, RED by assertion on compile stubs marked `M8-06b STUB`. Added: core tests `ActionsParserTest` (12), `ConversionReviewViewApiTest` (19), `ReviewStateTest` (36), `GenerateFlowTest` (35), `ReviewSecretsTest` (4), `SavedPathTest` (6), support `ReviewRig`; core-testing `FakeMigrationFolder` and `FakeMigrationFolders` (honest folder: refuses an existing version, read-only, unavailable, failing Nth write, write-attempt log); acceptance: the 16 `@m8-06b` scenarios (19 runs) bound in `RunMigrate3xScreenAcceptanceTest` (filter `(@m8-06a or @m8-06b) and @fake-broker`) with steps in `Migrate3xReviewSteps` (the M8-05 Givens "the report proposes ...", "... the classic queue ... with ...", "... the non-durable queue ..." are reused, a document is put on the screen through the holder); ui: `MigrateFromThreeReviewUiTest` (39), `MigrateFromThreeReviewScaleUiTest` (6), `MigrateFromThreeReviewScreenshotTest` (10, opt-in), kit `MigrateFromThreeReviewKit` (a real view model behind the real screen). Stubs (replace, do not keep): `ConversionReview.findingsOf/previewActions/refusalOf/undecide`, `MigrationParser.parseActions/actionsToJson`, `ReviewState.kt` (types, `savedPath`, `ReviewState.NONE`, `How`), `Report.review` (stub default, the brief makes it required), `HeldAnalysis.review`, `AnalysisHolder.saved/recordSaved`, the events of the view model, `ReviewEvents` and the `review` parameter of `MigrateFromThreeScreen`. Existing tests edited only where a signature changed: the view model constructor (`folders` after `holder`) in four places and `GroupView.severity` nullable (one `!!`). Open questions for the architect: see the step 2 report. RED (seen 2026-11-10, all by assertion, none by compile error): core jvmTest 1932 run, 108 red, all in the six new classes (4 of their 112 pass on the stub by design: the parser premise, "a reason never holds input", the source guard, "not a review item opens no sheet"); acceptance `@m8-06b` 19 of 19 runs red and every other acceptance suite green; ui jvmTest 673 run, 44 red, all in the two new classes (the one that passes is the guard "rows without a decision show no pill"); the 10 screenshot tests were not run. Mutations to run in step 4: (a) `canAccept` true without the tick (`ReviewStateTest` ticks, `MigrateFromThreeReviewUiTest`), (b) Replace stored after a refusal (`ReviewStateTest` refused replacement x2), (c) `generate` with pending items (`GenerateFlowTest` first test), (d) saved file overwritten or existing version reused (`FakeMigrationFolder` refuses and `updateFile` throws; `GenerateFlowTest` versions, retry), (e) decision lost when the sheet closes (`ReviewStateTest` closing), (f) Skipped group filtered by the chips (`ReviewStateTest`, UI chips test), (g) second generation not asking (`GenerateFlowTest`), (h) folder problem that still writes (`GenerateFlowTest` attempts empty).
