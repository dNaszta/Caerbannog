# Migrate-from-3.x screen: review polish (follow-up of the M8-06b review)

Milestone: M8    Depends on: M8-06b, M8-06e (merged first)    Size: M

## Goal
Four things the M8-06b review found are fixed and `MigrateFromThreeViewModel` (632 lines after 06e) is cut to under 350 lines; none changes a rule, one changes a button label.

## Scope
1. Cut `MigrateFromThreeViewModel` (632 lines on main after 06e, 49 functions, four jobs: file and prompt, report view, review sheet, generate and save) into three classes. Pure move, no behaviour change. Two commits, so the work can stop after the first (architect default for the owner's "too large" remark: target under 350 lines after both; after the first alone about 420).
   a. `internal class GenerationFlow` (new file `GenerationFlow.kt`, `core/migrate3x`). Moves: the fields `generation`, `plan`, `epoch`, `generationJob`, the class `SavePlan`; the functions `generate`, `generateAgain`, `cancelGenerateAgain`, `begin`, `chooseFolder`, `retrySave`, `launchSave`, `folderFor`, `saveInto`, `trouble`, `versionOfFileName`, `generateFiles`, `write` (with its `NonCancellable` block and the `token != epoch` guard, kept as they are), `finish`, `exportFiles`, `dismissResult`. It gets in its constructor: the `MigrationFolderPorts`, the `ReportFileSaver` (the view model keeps its own reference for `exportReportJson`), the `AnalysisHolder` (for `saved` and `recordSaved`), the scope, the analysis dispatcher, and two callbacks `held: () -> HeldAnalysis?` (the held analysis while the state is a Report, the view model's `reportHeld`) and `changed: () -> Unit` (the view model's `rebuild`). It exposes `val state: GenerationState` (read by `rebuild()` and `canDecide()`), the event functions above (the view model's public events delegate in one line each; the public API of the view model does not change), `fun reset()` (what `dropReviewLocals` did to these fields: `generation = Idle`, `plan = null`, `epoch++`, cancel and null the job) and, for the "next undecided" case of `begin`, a third callback `showNextUndecided: () -> Unit`. `setNotice` for the export failure goes through a fourth callback `notice: (ExportNotice?) -> Unit`. No other new public surface. `epoch` stays private to the flow.
   b. `internal class ReviewSheetFlow` (new file `ReviewSheetFlow.kt`). Moves: the fields `edit` and `replaceDrafts`, and the sheet events `openItem`, `closeItem`, `chooseAlternative`, `setTick`, `accept`, `skip`, `unskip`, `startReplace`, `setReplaceText`, `submitReplace`, `backToProposal`, `showNextUndecided`, with `requiredTicks`. It gets the held analysis through `held: () -> HeldAnalysis?`, `canDecide: () -> Boolean` (from `GenerationFlow.state != Saving`) and `changed: () -> Unit`; it exposes `val edit: SheetEdit?` for `rebuild()` and `fun reset()` (drops `edit` and `replaceDrafts`). The view model delegates each public event in one line.
   What stays in the view model: `state`, `picker`, `chosenKind`, `job`, `pending`, `selected`, `openGroups`, the source events (`chooseSource`, `analyseLive`, `chooseFile`, `open`, `read`, `analyse`, `showReport`, `present`, `giveVirtualHost`, `cancelPrompt`, `cancelLoading`), `toggleSeverity`, `toggleGroup`, `exportReportJson`, `reportText`, `reportCopied`, `dismissNotice`, `reset`, `rebuild`, `dropReviewLocals` (calls both `reset()`s), `viewOf`, and the one-line delegations. Every event still ends in the one `rebuild()`. The new classes know no Compose, no state flow of the view model and no `java.*`; each is built by the view model with the constructor arguments above (no new public constructor parameter of the view model, so `MigrateFromThreeWiring` and every test rig stay unedited).
   Do not change: the texts, `GenerationState`, the rules of `canDecide`, the order of statements inside `launchSave`, `write` and `finish` (the epoch token checks are the safety net against a late save; move them as they are). Every test stays unedited and green: `GenerateFlowTest`, `ReviewStateTest`, `ReviewItemsTest`, `MigrateFromThreeViewModelTest`, `ReviewExploratoryTest`, the 16 `@m8-06b` scenarios and the 06a and 06c suites. New tests only for structure: none required; a test that `MigrateFromThreeViewModel.kt` has fewer than 350 lines is NOT wanted (the review counts lines).
2. The Replace sheet button reads "Use these actions" but the mockup (state 8) and the scenario say "Save replacement": change the button text to "Save replacement" (tag `migrate3x-replace-save` stays).
3. `exportFiles()` from `NoFolderChosen` and `NoFreeVersion` numbers the files from 0001 without saying why; the note "numbered from 0001 because the folder could not be read" is added only for `SaveProblem.Folder`. Say it for all three problems (text for `NoFreeVersion`: the existing note plus nothing else).
4. `RowView.item` and `RowView.decision` still carry the comment "M8-06b STUB default" in `ReportView.kt`: rewrite the two comments (the defaults stay).
Out: anything about decisions or the core rules.

## Acceptance
- BDD: the 16 `@m8-06b` scenarios stay green and unedited.
- Unit tests: every existing test unchanged and green (item 1: no test moves, the public events of the view model are the same); one new test for item 3.
- Compose test: the Replace button text (item 2).

## MCP
None (UI internals).

## Definition of done
Tests first and passing, `./gradlew test` green, no unrelated change.

## Status
- Step 1 (architect, 2026-11-11): made exact against main after M8-06e (632 lines). Ready to run right after 06e is merged, on a new branch `m8-06f-review-polish` from main. Items 2 to 4 are unchanged. Owner question: none (the target line count is the owner's remark made concrete). Note for item 3 and 06e: the notice text for a saved report (`reportSaved`) is already in the view model's `exportReportJson` and stays there.
