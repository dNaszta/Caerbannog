# M5-08c2: the Migrations screen states and the Connections banner

Milestone: M5    Depends on: M5-08c1    Size: M    Umbrella: `M5-08c-migrations-wiring-and-ux`

## Goal
The Migrations screen shows every state of the new behaviour as drawn in the approved mockup (11 states), with buttons for the actions that c1 only describes in a banner line, and the Connections screen shows the history-not-updated message.

## Why
After `M5-08c1` the user sees one banner line per problem and no buttons. Owner decisions 2026-11-10: progress A, folder row A, texts kept.

## Scope
In (`ui`, plus the small `core` clean-up below):
1. **`MigrationsScreen` states** as in `docs/ux/Migrations.dc.html` (section ids in brackets) with the test tags below. New callbacks: `onChooseFolder`, `onChangeFolder`, `onRetry`, `onShowFolder(path)`, `onStartWithEmptyHistory`, `onConfirmEmptyHistory`, `onCancelEmptyHistory`, `onDismissOutcome` (state in, events out; `Main.kt` forwards them to the view model and the `FolderOpener`).
2. **Core clean-up.** The view model stops composing `errorMessage` for apply outcomes and `bannerMessage` for the folder, history and unavailable states (the c1 interim strings): the structured fields are rendered instead. The existing permission banner and the Read-only level banner stay. The first-visit automatic chooser of c1 is replaced by the "Choose folder" button (the dialog opens only on a click).
3. **Connections screen.** Every `when` over `SaveConnectionResult` handles `HistoryNotUpdated`; `ConnectionsState.errorMessage` and the `HistoryNotUpdated` message are shown as the error banner above the action bar of the edit panel (same place as "A connection named ... already exists"), test tag `connections:historyNotUpdatedBanner`; the delete confirmation closes first, then the banner appears (`docs/ux/Main.dc.html`, last card).
Out: wiring and adapters (c1), MCP (`M5-09`), the 3.x pill (`M8-02`).

## The states, texts and test tags
| Mockup id | State | Texts | Test tags |
| --- | --- | --- | --- |
| `state-folder-row` | folder chosen | "Migration files in" + path (mono); "Show folder" (only when `canShowFolder`), "Change folder" | `migrations:folderRow`, `migrations:folderPath`, `migrations:showFolderBtn`, `migrations:changeFolderBtn` |
| `state-choose-folder` | `ChooseFolder` | panel "Choose the folder for your migration files", explanation, filled button "Choose folder"; Import, Export, Apply disabled | `migrations:chooseFolderPanel`, `migrations:chooseFolderBtn` |
| `state-folder-unavailable` | `FolderUnavailable` | "The folder does not exist" / "The path is not a folder" / "The folder cannot be read" + path; "Try again", "Choose another folder" | `migrations:folderUnavailableBanner`, `migrations:retryBtn`, `migrations:chooseAnotherFolderBtn` |
| `state-read-only` | `FolderReadOnly` | "The folder is read-only" + path; Import, Export, Edit file disabled; Apply enabled | `migrations:readOnlyBanner` |
| `state-invalid-files` | `invalidFiles` | file name (or "a file with an unusual name"), pill "Invalid", the fixed reason | `migrationInvalid:<fileName>` |
| `state-applying` | `applyProgress` | "Applying <version>: action k of n" and a progress bar; apply, import, export disabled | `migrations:applyingBanner`, `migrations:applyProgress` |
| `state-failed` | `applyOutcome` `Failed` | "Action k of n failed: <reason>" and "Migration <v> was not recorded. Running it again is safe: actions that already succeeded change nothing the second time."; Dismiss | `migrations:failedBanner`, `migrations:dismissOutcomeBtn` |
| `state-conflict` | `Conflict` | "Action k of n stopped: <reason>" and "The existing <name> was not changed. Migration <v> was not recorded. To go on, change the file or the broker so that they agree, then apply again." | `migrations:conflictBanner` |
| `state-not-recorded` | `NotRecorded` | "The migration was applied but could not be recorded; running it again is safe" | `migrations:notRecordedBanner` |
| `state-history-unreadable` | `historyProblem` | "The applied history cannot be read, so nothing can be applied", path, reason; "Try again", "Show folder" (the history file's folder), "Start with an empty history" | `migrations:historyBanner`, `migrations:historyRetryBtn`, `migrations:historyShowFolderBtn`, `migrations:emptyHistoryBtn` |
| (dialog) | `confirmation` | "Move migration-history.json to migration-history.json.unreadable-<timestamp> and start with an empty history? The old file is kept." + "Cancel", "Move file and start"; failure "The history file could not be moved aside; it was not changed" | `migrations:emptyHistoryDialog`, `migrations:emptyHistoryConfirmBtn`, `migrations:emptyHistoryCancelBtn` |
| `state-unavailable` | `unavailableReason` | "Migrations are not offered on this connection" + the fixed reason; Apply disabled, the rest works | `migrations:unavailableBanner` |
The unavailable banner wins over the permission banner; the existing permission, level, other-virtual-host and modified banners and the M8-04 detail line stay; names and reasons are plain text, never markup; Dismiss clears `applyOutcome` or `errorMessage`.

## Acceptance
- BDD (the suite filter drops `and not @m5-08c2`): the five `@m5-08c2` scenarios: "Applying shows its progress"; "The unreadable history can be moved aside from the screen"; "Deleting a profile whose history cannot be updated leaves it as it is"; "Renaming a profile whose history cannot be updated leaves it as it is"; "The folder can be changed at any time"; AND the five `@m5-08c1` scenarios re-bound to the Compose screen (the steps that read `errorMessage` or `bannerMessage` in c1 read the screen's banner texts through the Compose test rule; the scenarios are not edited). The Connections scenarios run the Connections screen over the real view model with a failing history fake.
- Unit tests, tests first (`ui`): `MigrationsStatesUiTest`, one test per row of the table with its tags, every button calls the right callback, Dismiss clears, the dialog Cancel and Esc change nothing, "Show folder" absent for a missing folder, apply buttons disabled in the choose-folder, applying, history-unreadable and unavailable states, the unavailable banner wins over the permission banner, a name with markup characters is shown as plain text; `ConnectionsScreenHistoryBannerTest` for Save (rename) and Delete; the existing `MigrationsUiTest`, `MigrationsActionDetailUiTest` and `MigrationsErrorBannerUiTest` stay green; `core` tests of the clean-up (outcome no longer in `errorMessage`; the c1 `bannerMessage` interim strings are gone).
- Mutation checks to record: (d) the empty-history confirmation skipped; (e) the screen draws "Show folder" for a missing folder; (j) the screen shows Apply enabled while `applyProgress` is set; (k) the Connections banner not drawn on delete failure; (l) the conflict banner drawn for a plain failure. Each turns a named test red.
- Manual check recorded: open both mockups next to the running app and compare each state; screenshot test only if the repo already has them for this screen.
- `verifyTestDiscovery` green.

## Design notes
State in, events out; no file or network code in `ui`; the screen reads `MigrationsScreenState` and calls callbacks; texts built from structured data (`index`, `total`, `reason`, `kind`) in one place so wording changes in one file. Material 3 components as the existing screens.

## UX
`docs/ux/Migrations.dc.html` (frame plus states 1 to 11) and `docs/ux/Main.dc.html` (last card), approved by the owner 2026-11-10.

## MCP
None.

## Open points
1. Windows and Linux look: best effort. 2. If a state is changed in the mockup later, the table above is updated with it.

## Definition of done
Tests first and passing, the screen matches the mockup, no unrelated change, mutation checks recorded, `verifyTestDiscovery` green.

## Status
- Step 1 (architect): DONE 2026-11-10. Not started (after `M5-08c1`).
