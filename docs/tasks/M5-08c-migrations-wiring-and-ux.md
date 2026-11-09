# M5-08c: desktop wiring and the Migrations screen states

Milestone: M5    Depends on: M5-08a, M5-08b    Size: M    Umbrella: `M5-08-real-migration-execution`

## Goal
The desktop app applies migrations for real: the Migrations screen uses the real executor, the folder-backed repository and the history on disk, the profile's own topology control level and virtual host, a native folder chooser and folder opener, and shows every new state clearly.

## Why
`MigrationsWindowContent` in `desktopApp/.../Main.kt` wires a no-op executor, in-memory repositories and a hard-coded `FULL_CONTROL`. Owner decisions 2026-11-09 (ask for the folder, failure rule A).

## Scope
In:
1. **Wiring (`desktopApp`).** Replace the no-op executor and the in-memory repositories in `MigrationsWindowContent`/the composition root with: the held connection's `migrationExecutor` (null leaves apply disabled with "No executor for this connection"), `FolderMigrationFileRepository` for the folder in the store, `JsonFileMigrationHistoryRepository`, `JsonFileAppSettings` as the `MigrationFolderStore`. The level comes from the connection profile's `topologyControlLevel` (remove `val controlLevel = ...FULL_CONTROL`), the virtual host from the connection, the configure permission from the permission check. The repositories and the view model are rebuilt when the folder changes.
2. **Adapters (`desktopApp`).** `SwingMigrationFolderChooser` (`javax.swing.JFileChooser`, `DIRECTORIES_ONLY`, run on the AWT event thread, start in the last folder or the user's home) and `DesktopFolderOpener` (`java.awt.Desktop.open`, only when `Desktop.isDesktopSupported()`); both behind the `core` ports; no new dependency (`java.desktop` is already part of the Compose runtime; check the packaged DMG once, open point).
3. **Screen (`ui`, `MigrationsScreen.kt`).** New states and texts: Choose folder (explanation, button "Choose folder", Import, Export and Apply disabled), Folder unavailable (path, fixed reason, "Try again", "Choose another folder", "Show folder" when the parent exists), Read-only folder (reason, Import and Export disabled), History unreadable (path, reason, "Try again", "Show folder", "Start with an empty history" with the confirmation naming the new file), Invalid file rows (file name, reason), Applying (per-action progress "Applying 2 of 3"), Failed ("Action 2 of 3 failed: <fixed reason>; the migration was not recorded"), Conflict (names the resource: "<name> exists with different settings; nothing was changed"), and "Change folder" in the screen menu. The existing banners and the detail line (M8-04) stay. All text is plain (no markup from file content); names are shown quoted.
4. **Mockup.** `docs/ux/Migrations.dc.html` gets these states; the architect draws them at step 1b of this task before the UI work (not drawn yet). Material 3, states: empty, choose folder, folder unavailable, read-only, list, applying, blocked (permission, level, other virtual host), applied, modified, failed, conflict, history unreadable, invalid file.
Out: executor and persistence internals (`M5-08a`, `M5-08b`), MCP (`M5-09`), 3.x detection (`M8-02`).

## Acceptance
- BDD (`@fake-broker`, the real view model with fakes behind the ports; the suite stops excluding `@m5-08c`): "The topology control level of the connection is honoured"; "A failed apply shows which action failed and why"; "A conflict names the resource"; "The folder can be changed at any time". Compose UI tests (`ui` `MigrationsStatesUiTest`) for each state of Scope 3 with a stable test tag per state and per button; the existing `MigrationsUiTest` and `MigrationsActionDetailUiTest` stay green.
- Unit tests: composition root test (`desktopApp`): the level and vhost passed to the view model are the profile's (a Read-only profile gives apply disabled; mutation: hard-code `FULL_CONTROL` again turns it red); the adapters are tiny and covered by a headless-safe test (`SwingMigrationFolderChooser` is skipped when `GraphicsEnvironment.isHeadless()`; `DesktopFolderOpener` returns a fixed "not supported" result when unsupported).
- Manual check recorded in the Status block: on macOS the packaged DMG opens the chooser, a chosen folder is remembered after restart, a real apply to a local 4.x broker works end to end (declare a queue, see it in the management UI).
- Mutation checks to record: (a) `FULL_CONTROL` hard-coded; (b) the folder is not rebuilt on change; (c) the conflict state shows the broker's text; (d) the confirmation of "Start with an empty history" is skipped; (e) "Show folder" offered for a missing parent. Each turns a named test red.
- `verifyTestDiscovery` green.

## Design notes
UI is state in, events out; no file or network code in `ui`; the screen reads `MigrationsScreenState` and calls the view model. The ports keep `core` free of `java.*`. A native dialog runs off the composition thread; a cancelled chooser changes nothing.

## UX
`docs/ux/Migrations.dc.html`, states listed above (new ones drawn first).

## MCP
None here.

## Open points
From `M5-08b` (final review, 2026-11-10): (1) `SaveConnectionResult` has a new case `HistoryNotUpdated(message)` and `ConnectionsState.errorMessage` carries "The migration history of this connection could not be updated, so the connection was left as it is": every `when` over `SaveConnectionResult` in the Connections form and the confirm-delete dialog must handle it and the screen must show the message (a UI test per path); (2) the apply result `MigrationApplyResult.NotRecorded(version, reason)` must be handled everywhere the result is shown ("The migration was applied but could not be recorded; running it again is safe"); (3) the composition root passes the history repository as `historyKeys` (pinned by a composition-root test); (4) `AtomicFileWriter.ownerOnly` has no default: the wiring creates the writers through the repositories, no new call site is needed here.
From `M5-08b` (review, 2026-11-10): (1) the composition root MUST pass the history repository (`JsonFileMigrationHistoryRepository`, which implements `ProfileHistoryKeys`) as `historyKeys` to `ConnectionsViewModel`, otherwise a rename or delete of a profile never reaches the history and a later profile with the same name inherits "applied" statuses (a composition-root test pins it; mutation: not passed); (2) show `FolderListing.Unavailable`, the read-only reason, the invalid-file rows and `HistoryResult.Unreadable` with the fixed texts from the view model; (3) `MigrationApplyResult.NotRecorded` shows "The migration was applied but could not be recorded; running it again is safe"; (4) the profile rename and delete errors ("The migration history of this connection could not be updated, so the connection was left as it is") appear on the Connections screen; (5) migration files in the user's folder get default permissions, the app's own data owner-only.
From `M5-08a` (final review, 2026-11-10): (1) the screen must show the failed action as "Action k of n failed: <reason>" using `ActionFailed.index` and `total` (the view model's current text "Action 'x' failed on version 'v': reason" is replaced here); (2) a connection whose `migrationExecutor` is null shows the fixed reason from `migrationUnavailableReason` ("This broker runs RabbitMQ 3.x: it is a migration source only" or "The broker version could not be read, so migrations are not offered") and Apply stays disabled; (3) the executor comes from `StreamSource.migrationExecutor` (guarded: it refuses once the connection is no longer current); (4) a binding delete that cannot be sent (a `.` or `..` routing key) fails with "this action cannot be sent to the broker": the screen shows it as any failed action; (5) `MigrationRunner` calls need the connection's virtual host (required).
1. `java.desktop` in the packaged runtime: verified once on the DMG here (unverified until then).
2. Windows and Linux chooser look and feel: best effort, not measured.

## Definition of done
Tests first and passing, the manual end-to-end check recorded, no unrelated change, no new dependency, mockup states drawn, mutation checks recorded, `verifyTestDiscovery` green.

## Status
- Step 1 (architect): DONE 2026-11-09. Not started. Step 1b (mockup states) is the architect's before the UI work.
