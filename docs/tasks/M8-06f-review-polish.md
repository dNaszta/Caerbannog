# Migrate-from-3.x screen: review polish (follow-up of the M8-06b review)

Milestone: M8    Depends on: M8-06b    Size: S

## Goal
Four small things the M8-06b review found are fixed; none changes a rule, one changes a button label.

## Scope
1. `MigrateFromThreeViewModel` is 553 lines with four jobs (file and prompt, report view, review sheet, generate and save). Extract the generate, save and export part into an internal `GenerationFlow` class in `core` (owns `generation`, `plan`, `epoch`, `generationJob`, the folder ports and the report saver; the view model keeps one instance and calls it, and still ends every event with the one `rebuild()`). Pure move: no behaviour change, every existing test stays unedited and green, the NonCancellable block and the epoch rule move with it.
2. The Replace sheet button reads "Use these actions" but the mockup (state 8) and the scenario say "Save replacement": change the button text to "Save replacement" (tag `migrate3x-replace-save` stays).
3. `exportFiles()` from `NoFolderChosen` and `NoFreeVersion` numbers the files from 0001 without saying why; the note "numbered from 0001 because the folder could not be read" is added only for `SaveProblem.Folder`. Say it for all three problems (text for `NoFreeVersion`: the existing note plus nothing else).
4. `RowView.item` and `RowView.decision` still carry the comment "M8-06b STUB default" in `ReportView.kt`: rewrite the two comments (the defaults stay).
Out: anything about decisions or the core rules.

## Acceptance
- BDD: the 16 `@m8-06b` scenarios stay green and unedited.
- Unit tests: `GenerateFlowTest` unchanged and green (item 1); one new test for item 3.
- Compose test: the Replace button text (item 2).

## MCP
None (UI internals).

## Definition of done
Tests first and passing, `./gradlew test` green, no unrelated change.
