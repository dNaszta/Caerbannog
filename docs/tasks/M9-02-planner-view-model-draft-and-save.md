# M9-02: Planner view model: the wizard flow, the draft, save as migration, dismiss

Milestone: M9    Depends on: M9-01    Size: M

PLANNED 2026-11-15. Not started. Second task of the earliest working slice.

## Goal
A `PlannerViewModel` in `core` drives the whole planner without a screen: the three wizard pages, the step list, the naming style, the draft that is kept in the app's own data, "Save as migration" through the existing file repository, and "Dismiss". Nothing in it can change a broker.

## Why
Spec "Topology planner (M9)": A3 (where a draft lives), A7 (no new write path), "The draft" (owner decision O5), "Naming". The owner's words: "works as a draft builder with dismiss".

## Scope
In:
- `core`, package `dev.caerbannog.core.planner`:

```kotlin
/** The one draft of this machine, in the app's own data (spec A3). Never the migrations folder. */
interface PlanDraftStore {
    suspend fun load(): DraftLoad                    // None | Loaded(plan) | Unreadable(path, reason)
    suspend fun save(plan: TopologyPlan): Boolean    // atomic; false and nothing changed when the write fails
    suspend fun clear()                              // after Dismiss and after a successful save
    fun asideFileName(): String                      // planner-draft.json.unreadable-<yyyyMMdd-HHmmss>
    suspend fun moveAside(): DraftMoveResult         // never deletes or overwrites
}
```

  `PlannerViewModel(draftStore, folderPorts: MigrationFolderPorts, settings: AppSettings, context: PlannerContext, scope)` with one `StateFlow<PlannerState>` (state in, events out, as the other view models; every public method hops onto the scope). `PlannerContext` is what `MigrationsInputs` carries today: connection name, virtual host, level, configure permission, the reason a connection cannot run migrations (3.x, unknown version), and `KnownTopology` read from the held connection's `TopologyCatalog`.
- Wizard state machine: `Kind`, `Details(form)`, `Summary(actions)`; Next is refused while the form has a problem; "Add to plan" appends; Cancel adds nothing; editing a step opens `Details` with `StepForms.formOf(action)`.
- The draft is stored after every change that leaves a description or a step, and cleared when the plan is empty and has no description. Its text is `MigrationParser.exportToJson("draft", description, actions)` and it is read with `MigrationParser.parse` (A3): no new format.
- Save as migration: gated by one `saveGate` (Enabled, or Disabled with the first reason that applies: "Add a step first", "Fix the N problem(s) in the plan first", "Choose a migrations folder first", the folder's own reason, "The folder is read-only"). It calls `repository.nextVersion()` and `repository.exportTopology(version, description, actions)` of the folder in use (the existing writer of a new file from actions, which never overwrites); a `MigrationWriteException` leaves the draft and shows "The file could not be written". On success the draft is cleared and the state carries `SavedPlan(version, path)` for the screen to open the Migrations screen with.
- Dismiss: with at least one step it sets the question "Dismiss this plan? Its N steps are discarded. Nothing was sent to the broker."; confirm clears the draft; an empty plan is dismissed at once.
- Unreadable draft: `DraftUnreadable(path, reason)` in the state; "Start a new plan" asks with the real aside name and moves the file aside on confirm (the pattern of `MigrationsViewModel.startWithEmptyHistory`).
- Naming style: read from and written to `AppSettings.plannerNamingStyle` (new property; key `plannerNamingStyle` in `settings.json`; a missing key or an unknown value reads as the default); changing it renames nothing.
- The banner of the planner (one, the first that applies): connection cannot run migrations (3.x, unknown version: then no wizard, no save, no draft write, and the draft is left untouched); unreadable draft; Read-only level; no configure permission; not connected; folder problem; the plan's virtual host is not the connection's.
- `data-rabbit`: `FilePlanDraftStore` (`~/.caerbannog/planner-draft.json`, `AtomicFileWriter`, default file permissions; a draft holds no secret, A8) and the new key in `JsonFileAppSettings`.
- `core-testing`: `FakePlanDraftStore` (in memory, can be told to be unreadable or to refuse a write), and the new property on `FakeAppSettings`.

Out: any composable (`M9-03`); the check (`M9-05`, `M9-06`); opening a migration file (`M9-12`); applying (the Migrations screen does it, unchanged).

## Acceptance
- BDD: `19_topology_planner.feature` / "The naming style is remembered on this machine" (@fake-broker @needs-decision)
- BDD: `19_topology_planner.feature` / "The naming style is kebab-case until I choose another" (@fake-broker @needs-decision)
- BDD: `19_topology_planner.feature` / "A plan is a draft until it is saved" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "Save as migration writes the next version" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "A saved plan is applied by the existing Apply" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A plan with a name that does not follow the naming style can be saved" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Saving never overwrites a migration file" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Dismiss discards the draft after a question" (@fake-broker)
- BDD: `19_topology_planner.feature` / "An empty plan is dismissed without a question" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A draft survives a restart" (@fake-broker @needs-decision)
- BDD: `19_topology_planner.feature` / "There is one draft at a time" (@fake-broker @needs-decision)
- BDD: `19_topology_planner.feature` / "The draft is not kept in the migrations folder" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A draft that cannot be read is reported and kept" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "An empty plan cannot be saved" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "Saving needs a migrations folder" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A read-only folder cannot take the plan" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A failed write keeps the draft" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "The planner cannot change the broker by itself" (@fake-broker @negative @safety)
- BDD: `19_topology_planner.feature` / "A plan can never hold a password or a password hash" (@fake-broker @negative @security)
- The `@m9-01` scenarios keep passing with the glue's `PlannerDriver` re-pointed at the view model (no feature line edited).
- Unit tests, written first: `PlannerViewModelWizardTest`, `PlannerViewModelDraftTest` (stored after each change, cleared when empty, survives a new view model on the same store), `PlannerViewModelSaveTest` (gate reasons in order, next version, never overwrites, write failure keeps the draft), `PlannerViewModelDismissTest`, `PlannerViewModelBannerTest` (the order), `PlannerViewModelThreadTest` (the rule of `StreamListViewModelThreadTest`), `FilePlanDraftStoreTest` (data-rabbit, temp folders: round trip, atomic write, unreadable shapes, aside name and collision, original bytes kept), `JsonFileAppSettingsTest` additions.
- Guard `PlannerArchitectureGuardTest` (core, source scan in the style of `MigrationArchitectureGuardTest`): no file under `core/planner` names `TopologyMigrationExecutor`, `MigrationRunner.applyPending` or a publisher; and in `data-rabbit` the draft store names no `ManagementHttp`.
- A test with a sentinel password in the fake secret store and a sentinel hash in a migration of the folder: neither appears in the draft file, the state or any text.

## Design notes
Model the planner as two sealed things, not flags: `PlannerMode` (`NewPlan` now, `EditFile` in `M9-12`) and `WizardState`. Results at the boundary are typed (`DraftLoad`, `DraftMoveResult`), never exceptions or text matching. `MigrationFileRepository.exportTopology` is reused as it is; its name is historical and may be given a neutral alias in this task only if no test has to change. The folder ports are the ones of the Migrations screen (`MigrationFolderPorts`), so choosing a folder from the planner is the same store and the Migrations screen follows it. Where the broker is faked: `KnownTopology` comes from `FakeTopologyCatalog`; no real broker in this task.

## UX
`docs/ux/Planner.dc.html`: the plan header, "2. Empty plan", "3." to "5." (wizard pages), "10. Save as migration", "11. Dismiss", "12. Blocked and limited states" (the texts and their order). This task builds the state and the texts; the drawing is `M9-03`.

## MCP
None (owner decision 2026-11-15). The draft is in the app's own data and no tool reads it; a saved plan is an ordinary migration file that `caerbannog_list_migrations` and `caerbannog_dry_run_migration` already see.

## Open points
- Q9 (the draft survives a restart; one draft; Dismiss asks once): built as recommended. If the owner wants no persistence, `FilePlanDraftStore` is replaced by an in-memory store in the composition root and the two `@needs-decision` scenarios are rewritten; nothing else changes.
- Q8, Q5 (where the style lives, the default): built as recommended (per machine, kebab-case).
- Q11 (Apply happens on the Migrations screen): the view model only reports `SavedPlan`; where the screen goes is `M9-03`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
