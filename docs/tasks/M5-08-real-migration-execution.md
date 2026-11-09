# Real migration execution (umbrella: decisions, rerun semantics, split)

Milestone: M5 (gap found in M8-04 step 1)    Depends on: M8-04 (merged), M5-07 (merged)    Size: L, split into three tasks (M5-08a, M5-08b, M5-08c) plus the small M5-09

## Goal
Applying a migration really changes the broker, a rerun is safe by design, the migration files live in a folder the user chose, the applied history is kept on disk per machine and profile, the desktop app uses the profile's real topology control level and virtual host, and MCP can only read and dry-run migrations. The two `@broker` scenarios of `10` that were never implemented run against a real broker.

## Why
Finding of M8-04 step 1 (2026-11-09), by reading the code (re-read on main ff286db): `MigrationsWindowContent` in `desktopApp/.../Main.kt` wires a no-op executor (`execute` returns `Success`), `InMemoryMigrationFileRepository` and `InMemoryMigrationHistoryRepository`, and a hard-coded `controlLevel = FULL_CONTROL`; `ManagementHttp` has `get` and `post` only; `MigrationRunner.dryRun` never checks an action's virtual host against the connection's (spec V1); `MigrationsViewModel.exportCurrentTopology` reads only the first queue page and, when the catalog call fails, silently writes an EMPTY migration. The app has no directory chooser and no folder opener at all (no `java.awt` or `javax.swing` use anywhere). Without this work the generated migrations of M8 cannot be applied in the app.

## Owner decisions (2026-11-09)
1. **Failure and rerun.** Option A: stop at the failing action, report which one and why, do not record the migration as applied. A rerun must be safe by design: idempotent declares, read-back after each action, an existing resource with different settings is a conflict that fails the run without overwriting. Exact semantics below.
2. **Migration files: the app asks for the folder.** First use of the Migrations screen asks the user (a native directory chooser); the choice is remembered. Design below.
3. **MCP is read-only.** Only list-migrations and dry-run tools may exist; apply stays a human action in the app. The three tools of the M5-04 brief are replaced by two read-only ones (`M5-09`); the M5-04 brief and the spec are corrected.

## The split (decided by the architect)
Size L is too big for one round and the three parts have different test surfaces. Ordered, each with its own brief and branch:

| Task | Content | Depends on |
| --- | --- | --- |
| `M5-08a-migration-executor` | `ManagementHttp` PUT/DELETE, the management executor with conflict rule and read-back, the runner's V1 vhost rule, the file-level rerun-safety rule, export reads every page and fails loudly, `HeldConnection.migrationExecutor`, live test `RealBrokerM5MigrationTest` | M8-04, M5-07 |
| `M5-08b-migration-persistence-and-folder` | `MigrationFolder` port, folder store in `settings.json`, folder-backed file repository, history repository on disk, unreadable-folder and unreadable-history behaviour, version validation, view-model states | M5-08a (the models), M1-13 pattern |
| `M5-08c-migrations-wiring-and-ux` | desktop wiring (real executor, repositories, level, vhost), folder chooser and opener adapters, the Migrations screen states, mockup states | M5-08a, M5-08b |
| `M5-09-mcp-migration-readonly-tools` | `caerbannog_list_migrations` and `caerbannog_dry_run_migration` | M5-08b |

Recommended order: a, b, c, then M5-09 (M5-09 may run in parallel with c). M8-05's and M8-12's apply half and the M8-11 gate need a, b and c; their generation halves need none of them.

## Rerun semantics (exact; the contract of M5-08a)
| Action | Broker call | Created | Already there, same settings | Already there, other settings | Read-back after |
| --- | --- | --- | --- | --- | --- |
| `declareQueue` | `PUT /api/queues/{v}/{n}` | 201 | 204: no-op | 400 `PRECONDITION_FAILED`: `Failed("<name> exists with different settings")`, nothing deleted or recreated | `GET` detail: exists; type, durable and every declared argument equal (numbers as numbers) |
| `declareExchange` | `PUT /api/exchanges/...` | 201 | 204: no-op | 400: same conflict | exists; type, flags, arguments |
| `declareBinding` | `POST /api/bindings/.../e/{src}/q or e/{dst}` | 201 | 204: no-op | not possible: a binding is its source, destination, routing key and arguments; another key is another binding | the binding list of source to destination contains it |
| `declarePolicy`, `declareOperatorPolicy` | `GET` first, then `PUT` | absent: `PUT` | equal (pattern, apply-to, priority, every definition key, numbers numeric): no request at all | different: conflict failure, NO `PUT` | `GET`: equal |
| `declareStream` | `StreamManager.createStream` | `Success` | "already exists" refusal: no-op (stream settings are not in the action) | n/a | the stream is listed |
| `delete*` (Full control only) | `DELETE` | 204 | n/a | n/a | `GET` answers 404 |
| delete of something already gone | `DELETE` | 404: no-op | | | |
Rules: the executor never deletes or recreates anything on its own initiative; a delete happens only for an explicit delete action. A migration file may not both declare and delete the same item (kind, vhost, name; bindings by source, destination and routing key): the parser refuses it ("split it into two migrations"), because a rerun would delete what the first run created. A rerun after a failure at action k repeats 1 to k-1 as no-ops, retries k, and records only when all pass; a recorded migration is not offered again and, if forced, changes nothing; a rerun after the broker changed fails at the changed resource; a rerun after a restart reads history and files from disk. Failure texts are fixed ("action 2 of 3 failed: <fixed reason>"), never the broker's text.

## The migrations folder (exact; the contract of M5-08b and M5-08c)
- **Chooser.** The app has no file dialog today. A port in `core`, `MigrationFolderChooser` (`suspend fun choose(startIn: String?): String?`, null when cancelled), with a fake in `core-testing`; the desktop adapter uses `javax.swing.JFileChooser` with `DIRECTORIES_ONLY` (works on macOS, Windows and Linux; AWT's `FileDialog` can pick a directory only on macOS). Both are in the JDK module `java.desktop`, which the Compose runtime already needs: no new dependency; the packaged runtime is checked once on the DMG (open point). A second port, `FolderOpener` (`fun show(path)`, adapter `java.awt.Desktop`), is shared with `M1-13`; whichever task lands first creates it.
- **Remembered where.** `settings.json` (`JsonFileAppSettings`, which already preserves unknown keys), key `migrationsFolder`, exposed through a small new interface `MigrationFolderStore { var folder: String? }` (not by widening `AppSettings`). GLOBAL per machine, not per profile: a migration file is applied to several brokers (uat, then prod), so one folder serves every connection; the history is per profile. A corrupt `settings.json` falls back to "no folder chosen" (the app asks again; nothing in the folder is touched).
- **History where.** `~/.caerbannog/migration-history.json`, in the app's data, never in the user's folder (confirmed: history is machine state, the folder may be a shared git repository, and the same file in two clones would disagree). Format `{"schema":1,"profiles":{"<profile>":{"<version>":"<checksum>"}}}`, atomic write, read through `parseUntrustedJson` (limit 32).
- **First use.** Opening the Migrations screen with no folder shows the state "Choose folder" (no modal at app start): an explanation and the button "Choose folder"; Import, Export and Apply are disabled; no file is read or written.
- **Folder problems** (consistent with `M1-13`): states `FolderUnavailable(path, reason)` with fixed reasons "The folder does not exist", "The path is not a folder", "The folder cannot be read"; actions "Try again", "Choose another folder"; the app never creates, recreates, copies or overwrites anything in or in place of the folder. A readable but not writable folder lists its migrations and disables Import and Export with the reason "The folder is read-only". "Change folder" is always available in the screen menu; changing it keeps the history.
- **Files.** One `<version>.json` per migration holding exactly the raw text (checksum stable), written atomically, never rewritten unless the user imports or edits. The version must match `[A-Za-z0-9_-]+` and a numeric `version` in a file must be a whole number (M8-04 finding: `NaN` and `1e3` are accepted as text today), so a version cannot write outside the folder. A file in the folder that is not a valid migration is listed as invalid with the reason and is never changed or deleted.

## MCP (decided)
Read-only tools only, built in the new small task `M5-09` (not `M8-10`: they depend only on the folder and history of `M5-08b`, not on the 3.x chain): `caerbannog_list_migrations` (versions, descriptions, status per profile) and `caerbannog_dry_run_migration` (the dry-run rows for a profile, `Declare user <name>` and no hash); both read the folder from `settings.json` and the history file; neither writes anything. No apply tool. The M5-04 brief and the spec are corrected (done in step 1); scenario "The MCP apply tools refuse access actions" in `18` became "No MCP tool applies a migration".

## Product decisions
None remain open from the owner's three. Open points of the architect:
1. History is keyed by profile name: a renamed profile loses its history (it shows every migration as pending; actions are rerun-safe). Proposal: the rename in `ConnectionsViewModel` also renames the history key: a small item in `M5-08b`.
2. `DELETE` of a binding needs the properties key (`~` for an empty routing key, escaped otherwise): the live test of `M5-08a` measures it.
3. `java.desktop` in the packaged runtime: check the chooser on the DMG in `M5-08c` (unverified).
4. A dry run that compares with the broker (shows which actions would be no-ops, created or conflicts before applying) would be valuable and is not in scope; a follow-up idea after `M5-08c`.

## Scenario ownership
All in `tests/bdd/features/10_topology_migrations.feature`, tag `@m5-08` plus `@m5-08a`, `@m5-08b` or `@m5-08c` (the suite `RunTopologyMigrationsAcceptanceTest` excludes a sub-tag until its task builds the glue). `@m5-08a`: A failing action stops the migration; A rerun after a failure finishes the migration; A rerun after a success changes nothing; A rerun when the broker changed in between is a conflict; A queue that exists with other arguments is a conflict; A policy that exists with other settings is a conflict, never overwritten; A policy that exists with the same settings is a no-op; Deleting something that is already gone is a no-op; Applying reads back every declared queue (also the `16` scenario of the same name); A migration for another virtual host is blocked; A migration that declares and deletes the same thing is refused; An export that cannot read the broker writes no file; Exporting the topology reads every page; the two `@broker` scenarios. `@m5-08b`: Applied migrations and files survive a restart; The first visit asks for the folder; The chosen folder is remembered for every connection; The history stays with the connection, not with the folder; A folder that has disappeared is reported and nothing is created; A folder that cannot be read is reported and nothing is changed; A read-only folder can be read but not written; An unreadable history file blocks applying and is kept; Starting with an empty history keeps the old file under a new name; A migration file in the folder that is not valid is listed as invalid, not deleted; A version that could escape the folder is refused (5 rows). `@m5-08c`: The topology control level of the connection is honoured; A failed apply shows which action failed and why; A conflict names the resource; The folder can be changed at any time. `@m8-02` (needs the 3.x detection): A 3.x source connection cannot apply migrations.

## Definition of done (umbrella)
All four sub-tasks accepted; `docs/spec.md` "Running migrations for real" matches the behaviour; the M5-04 brief shows the superseded MCP line; no assertion of an older test edited.

## Status
- Step 1 (architect, brief, split and scenarios): DONE 2026-11-09. Round 0. Branch `m5-08-real-migration-execution` (worktree `.claude/worktrees/m5-08`, base ff286db). Owner decisions recorded (A, ask for the folder, MCP read-only). Sub-briefs written: `M5-08a`, `M5-08b`, `M5-08c`, `M5-09`. Changed: this brief, the four sub-briefs, `tests/bdd/features/10_topology_migrations.feature` (the `@m5-08` block rewritten with sub-tags, the `@needs-decision` scenario replaced by exact ones, folder scenarios added), `tests/bdd/features/18_migrate_access_from_3x.feature` (MCP scenario), `tests/bdd/README.md` (tag row), `docs/spec.md` (new subsection "Running migrations for real", MCP table, discrepancy resolved), `docs/tasks/M5-04-topology-migrations.md` (MCP line superseded), `docs/tasks/README.md`.
- Next: `M5-08a` step 2 (tests first).
