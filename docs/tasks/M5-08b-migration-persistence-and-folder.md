# M5-08b: migration files in the user's folder, history on disk

Milestone: M5    Depends on: M5-08a (models and runner changes), M1-13 pattern (not a code dependency)    Size: M    Umbrella: `M5-08-real-migration-execution`

## Goal
Migration files live in a folder the user chose (asked on first use, remembered), the applied history is kept on disk per machine and profile, and every folder or history problem is reported clearly without overwriting anything.

## Why
Owner decisions 2026-11-09: the app asks for the folder; history is machine state. Today files and history are in memory and lost on restart.

## Scope
In (`core` ports and view-model states, `data-rabbit` adapters):
1. **Ports (`core`):** `MigrationFolderStore { var folder: String? }`; `MigrationFolderChooser` (`suspend fun choose(startIn: String?): String?`); `FolderOpener` (shared with `M1-13`; the first task that lands creates it); `Clock`-style `fun interface NowProvider` if none exists (for the timestamp of the moved-aside history file). Fakes in `core-testing`: `FakeMigrationFolderChooser` (scripted answers), in-memory store.
2. **`JsonFileAppSettings`** gets the key `migrationsFolder` (read, write, unknown keys preserved: its write already merges) behind `MigrationFolderStore`; a corrupt file means "no folder".
3. **`FolderMigrationFileRepository`** (`data-rabbit`, implements `MigrationFileRepository`): the folder is a constructor parameter; one `<version>.json` holding exactly the raw text, atomic write (temp file plus atomic move), never rewritten unless imported or edited; `listFiles` returns valid migrations and the invalid ones as a separate list (`InvalidMigrationFile(fileName, reason)`: an interface addition, additive, with a default empty list on the in-memory repository); a file that is not valid is never changed or deleted; the version must match `[A-Za-z0-9_-]+` and a numeric version must be a whole number (`MigrationParser`/`ActionFields.versionText`: a number must be an integer: `toLongOrNull`; M8-04 verify finding) so a version cannot leave the folder; `importFile` refuses a version that already exists; `nextVersion` reads the folder; file names are never taken from input except the validated version; owner-only permission is not required here (no secrets until `M8-12`, which adds P13).
4. **`JsonFileMigrationHistoryRepository`** (`data-rabbit`, implements `MigrationHistoryRepository`): `~/.caerbannog/migration-history.json`, `{"schema":1,"profiles":{"<profile>":{"<version>":"<checksum>"}}}`, atomic write, read via `parseUntrustedJson` (limit 32); missing file is empty; corrupt or too deep is `HistoryUnreadable(path, reason)` (the interface gets a typed result: `suspend fun load(profile): HistoryResult`, additive; the old `getAppliedMigrations` stays as a wrapper for tests); never written while unreadable. The action "Start with an empty history" (`moveAside()`) renames the file to `migration-history.json.unreadable-<yyyyMMdd-HHmmss>` (`-2`, `-3` if that exists), atomically, after the view model's confirmation, and never deletes it.
5. **View model states** (`MigrationsViewModel` and its state): `ChooseFolder` (no folder yet), `FolderUnavailable(path, reason)` with the fixed reasons "The folder does not exist", "The path is not a folder", "The folder cannot be read", `FolderReadOnly` (listing works, import and export disabled with "The folder is read-only"), `HistoryUnreadable(path, reason)` (apply disabled), plus the existing list state. Actions: `chooseFolder()`, `changeFolder()`, `retry()`, `startWithEmptyHistory()` (asks first: "Move migration-history.json to migration-history.json.unreadable-<timestamp> and start with an empty history? The old file is kept."). Nothing is ever created in place of a missing folder.
6. **History key on profile rename:** `ConnectionsViewModel`'s rename also renames the history key (small; test first).
Out: desktop wiring, the real chooser and opener adapters, the screens and mockup (`M5-08c`); the executor (`M5-08a`); MCP (`M5-09`).

## Acceptance
- BDD (`@fake-broker`, real repositories on JUnit `@TempDir` folders, fake chooser, fake clock; the suite stops excluding `@m5-08b`): the 11 `@m5-08b` scenarios of `10` listed in the umbrella (with the outline of 5 version rows).
- Unit tests, tests first:
  - `FolderMigrationFileRepositoryTest`: raw text byte for byte, checksum stable across a new instance (restart), atomic write (a leftover temp file or a crash between temp and move leaves the old file), invalid files listed with a reason and untouched, 10,000-level file invalid with the M5-07 reason, a version with `../`, `a/b`, `.json`, `NaN`, `1e3` refused, a read-only folder (skip on platforms where it cannot be made read-only, recorded), a missing folder never created, a duplicate version refused, `nextVersion`.
  - `JsonFileMigrationHistoryRepositoryTest`: round trip, two profiles, missing, corrupt, too deep (hostile 10,000 levels), never written while unreadable, `moveAside` name and collision suffix and bytes preserved and nothing deleted, atomic.
  - `MigrationFolderStoreTest` (settings): round trip, unknown keys preserved, corrupt file gives none.
  - `MigrationsViewModelFolderTest`: each state transition (no folder, chosen, cancelled chooser, missing, not a folder, unreadable, read-only, history unreadable, retry success and failure, change folder keeps history, empty-history confirmation and cancel), no file I/O in the view model itself (it uses the ports), the chooser never receives a secret.
  - `ConnectionsViewModel` rename test: the history key moves.
  - `MigrationParserVersionTest`: a numeric version must be whole (`3` gives "3"; `1e3`, `NaN`, `1.5` refused with `the value of "version" has the wrong type`).
- Mutation checks to record: (a) the repository writes a normalised JSON instead of the raw text; (b) the history repository returns empty for a corrupt file; (c) the repository creates a missing folder; (d) `moveAside` deletes instead of renaming; (e) the folder store keeps no value across instances; (f) a version with a slash is accepted; (g) a read-only folder allows import. Each turns a named test red.
- `verifyTestDiscovery` green.

## Design notes
Adapters in `data-rabbit` use `java.nio.file` only (atomic move with a fallback only where `AtomicMoveNotSupportedException` is thrown, as `JsonFileAppSettings` does); no new dependency. Paths shown in messages are the user's own folder path (not secret); reasons are fixed; file content is never in a message. History and folder logic follow the `M1-13` pattern (message, retry, choose another, never overwrite).

## UX
States are drawn in `M5-08c` (the mockup is its step 1b); this task only provides the state types and texts.

## MCP
None here (`M5-09` reads the folder store and the history repository through these ports).

## Open points
From `M5-08a` (final review, 2026-11-10): `MigrationRunner.dryRun` and `applyPending` take the connection's virtual host as a REQUIRED parameter and `MigrationsViewModel` passes it; `exportCurrentTopology` returns `MigrationFile?` (null on failure, `state.errorMessage` set, no file); `ActionFailed` carries `index` and `total`; `echoableName(text): Boolean` is the one echo rule for names in messages (folder paths are the user's own and are shown whole, a file name from the folder passes `echoableName` or is shown generically); the shared status table `ManagementStatusTable` in `core-testing` is the single source of the fake broker's answers.
1. History key by profile name; rename handled in Scope 6; deleting a profile leaves its history entries (harmless, a follow-up idea: prune on delete).
2. Windows and exotic file systems: atomic move fallback and read-only detection are best effort and UNVERIFIED.
3. Whether `~/.caerbannog` exists on a fresh machine: the history repository creates its own parent folder, never the user's migrations folder.

## Definition of done
Tests first and passing, no unrelated change, no new dependency, spec subsection still true, mutation checks recorded, `verifyTestDiscovery` green.

## Status
- Step 1 (architect): DONE 2026-11-09.
- Step 2 (test developer): DONE 2026-11-10, round 0. Red for the right reason: core 43 failing (`MigrationsViewModelFolderTest` 22, `MigrationParserVersionTest` 18, `ConnectionsViewModelRenameHistoryTest` 2, `M508bStubGuardTest` 1), data-rabbit 33 failing (folder store, folder repository, history repository, `M508bStubGuardTest`), acceptance 15 failing (the 11 `@m5-08b` scenarios, the outline as 5 rows); all old tests green. Glue: `M508bPersistenceSteps` (real repositories on a temp root, fake chooser and clock); `RunTopologyMigrationsAcceptanceTest` no longer excludes `@m5-08b`. Compile stubs: `M508bTestStubs.kt` in `core/commonMain` and `data-rabbit/src/main` plus marked `M5-08b STUB` bodies in `MigrationsViewModel`, `MigrationRunner` (history interface), `ConnectionsViewModel`, `JsonFileAppSettings`; the developer deletes the files and replaces the markers (the two `M508bStubGuardTest` stay red until then). Signatures chosen where the brief was open: see the test developer's report. Mutation checks a to g not yet runnable (step 4). Read-only and unreadable folder tests skip where the machine cannot make them (root); they ran on the author's machine.
