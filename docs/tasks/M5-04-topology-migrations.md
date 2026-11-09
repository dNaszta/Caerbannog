# M5-04: Topology migrations runner and UI

Milestone: M5    Depends on: M5-03 (merged)    Size: L    Branch: `m5-04-topology-migrations`

## Goal
Implement topology migrations execution, history tracking, dry-run, export/import, and UI according to `tests/bdd/features/10_topology_migrations.feature`, `docs/spec.md`, and `docs/ux/Migrations.dc.html`:
1. **Core Domain (`core`):**
   - Migration file model: `MigrationFile(version, description, up: List<MigrationAction>, rawJson, checksum)`.
   - Action models: `declareQueue`, `deleteQueue`, `declareExchange`, `deleteExchange`, `declareBinding`, `deleteBinding`, `declareStream`, `deleteStream`, `declarePolicy`, `deletePolicy`.
   - Migration parsing & validation: valid JSON, required version, non-duplicate version, known actions.
   - History tracking: `MigrationHistoryRepository` per machine, storing applied versions and checksums per profile.
   - Checksum tracking: warn if an applied file is modified on disk; do not re-apply modified files.
   - Dry run engine: checks permissions and self-constraint `TopologyControlLevel` without sending requests to broker.
   - Apply engine: applies actions sequentially; stops on first error without recording migration; safe to run twice (idempotent); records applied version and checksum on success.
   - Export current topology: generates a new migration file declaring current queues, exchanges, and bindings.
   - Import migration: validates and saves imported migration files.
2. **Data & Execution Layer (`data-rabbit` / `core`):**
   - Execute migration actions via Management API (HTTP PUT/DELETE on queues, exchanges, bindings) and Stream Client for streams.
   - Wire into `HeldConnection` or migration execution gateway.
3. **View Model & UI (`core`, `ui`, `desktopApp`):**
   - `MigrationsViewModel` handling file listing, selection, dry-run status, apply, import, export.
   - `MigrationsScreen` matching `docs/ux/Migrations.dc.html`.
   - Connect `AppDestination.Migrations` into `AppNavigation` and composition root.
4. **BDD & UI Tests (`acceptance-tests`, `ui`, `core`):**
   - `RunTopologyMigrationsAcceptanceTest` and `TopologyMigrationsSteps` for `10_topology_migrations.feature`.
   - Compose UI tests in `MigrationsUiTest.kt`.
   - Unit tests for validation, dry-run, execution, checksum modification detection.

## Scope
### In
- `core`:
  - Models, parsing, validation, runner, dry-run, history repository, view model.
- `core-testing`:
  - `FakeMigrationRunner`, `FakeMigrationHistoryRepository`.
- `ui`:
  - `MigrationsScreen`, import/export dialogs/controls, dry-run status display.
- `desktopApp`:
  - Wire `MigrationsScreen` and activate navigation destination.
- `mcp-server`:
  - (Superseded 2026-11-09, owner decision) Not built here. The three tools once listed (`caerbannog_list_migrations`, `caerbannog_dry_run_migration`, `caerbannog_apply_migrations`) are replaced by two READ-ONLY tools, `caerbannog_list_migrations` and `caerbannog_dry_run_migration`, built in `M5-09`; there is no apply tool: applying stays a human action in the app.
- `acceptance-tests`:
  - BDD runner and steps for `10_topology_migrations.feature`.

### Out
- Packaging and real-broker gate verification (M5-06).

## Acceptance Criteria
1. All `@fake-broker` scenarios in `tests/bdd/features/10_topology_migrations.feature` pass.
2. Migrations are applied in version order; only pending migrations run.
3. History is maintained per connection profile with content checksums.
4. Editing an applied migration displays a warning banner and is never re-applied.
5. Dry-run evaluates actions without changing broker topology.
6. Configure permission and `TopologyControlLevel` constraints are enforced.
7. Exporting current topology produces a valid next-version migration file.
8. Import rejects invalid JSON, missing versions, duplicate versions, or unknown actions.
9. Compose UI tests and `./gradlew test` pass cleanly.

## Status
- **Accepted (2026-11-07):** Completed and verified on branch `m5-04-topology-migrations`. All 14 scenarios/examples in `10_topology_migrations.feature` pass, all 10 Compose UI tests in `MigrationsUiTest.kt` pass, 8 ViewModel tests in `MigrationsViewModelTest.kt` pass, desktopApp wiring passes, and full `./gradlew test` passes cleanly.
