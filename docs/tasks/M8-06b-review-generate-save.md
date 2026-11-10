# Migrate-from-3.x screen, part b: review, generate, save

Milestone: M8    Depends on: M8-06a, M8-05, M8-05b, M5-08 (merged); owner review of `docs/ux/Migrate3x.dc.html`    Size: M

## Goal
The user decides each item that needs a decision (accept with the ordering tick, choose an alternative, skip, or replace with own migration actions), sees "N items need a decision", generates the migration once every item is decided, and the files are saved into the migrations folder, where the Migrations screen lists them as pending for a connection on the matching virtual host.

## Why
Spec "Review and the generated migration" and decisions 2, P3, P15. The core (M8-05, M8-05b) is merged; this slice puts it on the screen.

## Scope
In:
1. Small `core` additions (four with `undecide`, see "Skipped items"), test first (the merged `ConversionReview` has no way to feed the sheet): `ConversionReview.findingsOf(item): List<Finding>` (public; it exists as the private `findingsByItem`), `ConversionReview.previewActions(item): List<MigrationAction>` (what `generate` would write for the item with its default accept, pure, no state change, never a delete), and `MigrationParser.parseActions(text): ActionsParseResult` (parses the `up` array text of the Replace sheet with the same codecs, reason text as the parser's own, no echo of hostile names). These are the only changes to merged code; the architect confirms the shape in step 2.
2. `MigrateFromThreeViewModel` extended (state `Report.review`): `ReviewState(items, pendingCount, openItem: ItemSheet?, generation: GenerationState)`; `ItemSheet` shows the findings (message, why, `verified` marker), default proposal, alternatives as radio options, the required ticks (data: `Acknowledgement.text`), the manual steps for `Proposal.Manual`, and for Replace the prefilled text and the last error. Events: `openItem(item)`, `closeItem()`, `chooseAlternative(findingId, index)`, `setTick(ack, on)`, `accept()`, `skip()`, `startReplace()`, `submitReplace(text)`, `showNextUndecided()`, `generate()`, `generateAgain()`, `chooseFolder()`, `dismissResult()`. `accept()` enabled only when the core would accept (the view model asks `decide` and shows `TickMissing` and `ManualNeeded` as data, never re-implements the rule). Texts for refusals: `ReplaceHasDelete` "A replacement must not delete anything"; `ReplaceInvalid(reason)` shown as returned; `NotAReviewItem` cannot occur from the UI (the list only offers review items).
3. Generate: `ConversionReview.generate(GenerationRequest(existingVersions, source = file label))`; `Incomplete(pending)` shows "N items need a decision" and opens the next one; `Generated` saves each `GeneratedMigration.file.rawJson` with `MigrationFileRepository.importFile` of the folder (reuse `MigrationFolderPorts`: store, chooser, `repositoryFor`; no connection is needed), `existingVersions` from `load()` listing; a folder problem uses the Migrations screen texts; a partial failure says which file was saved. The result shows files (version, vhost, action count, path), `skipped`, `notes`, and "Open Migrations". A second generation asks first (owner decision 7).
4. `ui`: review sheet, replace sheet, decision bar, row pills, result panel; `testTag`s `migrate3x-item-<index>`, `migrate3x-review-sheet`, `migrate3x-tick-<ack>`, `migrate3x-accept`, `migrate3x-skip`, `migrate3x-replace`, `migrate3x-replace-text`, `migrate3x-replace-error`, `migrate3x-pending-count`, `migrate3x-generate`, `migrate3x-result`.
Out: access items (M8-06d); the live source (M8-06c); any apply (the Migrations screen applies); any Shovel; a delete of any saved file.

## Acceptance
- BDD (`16`, tag `@m8-06b @fake-broker`, same suite `RunMigrate3xScreenAcceptanceTest`): "The screen counts the items that need a decision"; "Accept the default proposal of an item with its tick" (@smoke); "Choose an alternative for a finding"; "An item with only a manual fix cannot be accepted as it is"; "Skip an item and change the decision later" (with un-skip); "The group of skipped items is last, closed, not filtered by the chips and absent when nothing is skipped"; "Replace an item with my own actions"; "A replacement the migration format refuses is shown as an error and not stored" (4 examples); "Generate is blocked while items are undecided and says how many"; "Generate the migrations and save them into the migrations folder" (@smoke); "Generating asks for the migrations folder when none is chosen"; "A folder that cannot be written is reported and nothing is half-saved"; "Generated files show up on the Migrations screen as pending for a matching connection" (reuses the M5-08 glue); "The result lists what was skipped and left out, and the notes". The core rules stay proven by the 19 `@m8-05` scenarios, unedited.
- Unit tests: `ConversionReviewViewApiTest` (`findingsOf`, `previewActions` incl. no delete, same result as `generate` for a default accept, `parseActions` reasons, hostile names not echoed), `ReviewStateTest` (counts, next undecided, a decision changed later), `GenerateFlowTest` (versions skip existing ones, no folder, cancel, read-only, second file fails after the first, a repeated generation), `ReviewSecretsTest` (sentinels absent from sheet state, result, saved files; no `expose(`).
- Compose UI tests: sheets and states 6 to 11 of the mockup; keyboard (tab order through options, tick, Accept; Esc closes the sheet and keeps the typed Replace text; Enter does not submit by accident); Accept disabled until the tick; the Replace error stays visible with the text kept; 60,000 pending items do not compose all rows.
- Opt-in screenshots for the sheets.
- Mutation checks: (a) Accept enabled without the tick; (b) Replace stored after a refusal; (c) `generate` allowed while items are pending; (d) a saved file overwritten (existing version reused); (e) a decision lost when the sheet closes.

## Design notes
The view model never builds an action itself: it shows `previewActions`, sends `decide` and `generate`, and saves what `generate` returns. The saved migration is an ordinary file; applying it is the Migrations screen's business (level, permission, virtual host rule, conflict rule, read-back). Message-loss findings are shown in the sheet as information and never block `Accept`. 3.x is never contacted (no gateway). The one-vhost-per-connection rule means a source with three vhosts needs one 4.x profile per vhost; the result panel says so.

## Skipped items (owner decision 6 = B, 2026-11-10)
- `ReportView.groups` gets one optional last group with the fixed key `skipped`, title "Skipped by you", shown only while at least one item is skipped (`ConversionReview.decisionOf(item) == Skip`), closed by default, never filtered by the severity chips, with its own count ("38 items"). Its rows are the skipped items (name, kind icon, "Left out of the migration. Was: <severity>, <first finding's message>") with "Un-skip" and "Review".
- A skipped item is not drawn in its rule group and not counted in that group's count. Chip counts and the summary header keep counting findings of the file (they describe the file; skipped does not change them). `pendingCount` ("N items need a decision") counts undecided items only, so skipping lowers it; un-skipping raises it by one.
- Events added: `unskip(item)` (the core has no "remove decision"; this adds `ConversionReview.undecide(item)`, a fourth small core addition: removes the decision of a review item, returns `Decided` or `Refused(NotAReviewItem)`, test first, architect confirms the signature in step 2), reusing `openItem` for Review.
- Generate: unchanged core behaviour. Skipped items are left out; the result lists them with reason "skipped by you" and lists their bindings as left out; the result panel's "Left out" list is the same data. The Skipped group stays visible after generating. After a generation, un-skipping and generating again follows decision 7 (new versions, asks first).
- Scenarios: "Skip an item and change the decision later" and "The group of skipped items is last, closed, not filtered by the chips and absent when nothing is skipped".

## UX
`docs/ux/Migrate3x.dc.html` states 6 (accept with tick), 7 (manual fix, keep classic default), 8 (replace and its error), 9 (decided rows, bar), 10 (result), 11 (folder problems), 13 (entry).

## MCP
None (UI). Reason: a human decision per item; generation writes files from a person's choices. A read-only migration MCP surface already exists in M5-09 for the generated files.

## Open points
1. Owner decisions 6, 7, 8 of the umbrella brief are DECIDED (owner, 2026-11-10): skipped items B (separate group), generate again A, Replace editor A.
5. Architect defaults (no owner decision needed, veto possible): the Skipped group ignores the severity chips; chip counts still include skipped items.
2. The four small core additions are a change to merged code: the architect confirms their exact signatures in step 2.
3. Tooltip and wording for the "unverified" marker (see M8-06a).
4. Whether the Replace sheet should be restricted to the item's own vhost in the UI as well as in the core (the core already refuses another vhost).

## Definition of done
Tests first and passing, the 19 `@m8-05` scenarios and `RealBrokerM7GateTest`, `RealBrokerM8GateTest` unaffected, `./gradlew test` green, screenshots looked at, spec updated, no new dependency.
