# M9-02: Planner view model: the wizard flow, the draft, save as migration, dismiss

Milestone: M9    Depends on: M9-01    Size: M

PLANNED 2026-11-15. Checked against the real M9-01 code 2026-11-16. Second task of the earliest working slice. Branch `m9-02-planner-view-model-draft-and-save`, stacked on `m9-01-plan-core-forms-and-naming` (head `0cb1804`) until the owner merges M9-01.

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

  `PlannerViewModel(draftStore, folderPorts: MigrationFolderPorts, settings: AppSettings, context: PlannerContext, scope)` with one `StateFlow<PlannerState>` (state in, events out, as the other view models; every public method hops onto the scope). `PlannerContext` is a plain value in `core`, shaped like `MigrationsInputs` (which lives in `desktopApp` and is not touched): connection name, virtual host, `TopologyControlLevel`, configure permission, the reason a connection cannot run migrations (3.x, unknown version; null when it can) and a ready `KnownTopology` value (M9-01 made it plain values the caller builds; reading the `TopologyCatalog` into it, and mapping `MigrationsInputs` to `PlannerContext`, is wiring in `desktopApp` and belongs to `M9-03`). The window rebuilds the view model when the context changes, as it does for the Migrations screen.
- Plan-from-file factory, a pure addition to `TopologyPlan` (M9-01 has none): `TopologyPlan.of(description, actions, defaultVhost)` gives every action a step id (1..n) and takes the plan's vhost from the first action that has one, else `defaultVhost`. It is how a stored draft is loaded and how `M9-12` will open a file. Do not change any existing M9-01 signature.
- The Migrations screen's entry: a pure function `PlannerEntry.of(DraftLoad)` in `core` giving `New plan` (no draft), `Continue plan(stepCount)` or `DraftUnreadable(path)`; the scenarios "A draft survives a restart" and "There is one draft at a time" bind to it (the button is drawn in `M9-03`).
- Folder state: the planner needs the same folder verdict as the Migrations screen (`ChooseFolder`, `FolderUnavailable`, `FolderReadOnly`, `Ready`). `MigrationsViewModel.folderState` is private; extract it as a pure function of `(FolderListing?, path)` next to `MigrationFolderState` and call it from both, with no behaviour change and no test edited. The planner reads `repositoryFor(folder).load()` and follows `folderPorts.store.changes`.
- Wizard state machine: `Kind`, `Details(form)`, `Summary(actions)`; Next is refused while the form has a problem; "Add to plan" appends; Cancel adds nothing; editing a step opens `Details` with `StepForms.formOf(action)`.
- The draft is stored after every change that leaves a description or a step, and cleared when the plan is empty and has no description (an empty `up` array is a valid migration for `MigrationParser.parse`, so a description-only draft round trips; the vhost of a draft without steps is not kept and reads as the connection's). Its text is `MigrationParser.exportToJson("draft", description, actions)` and it is read with `MigrationParser.parse` (A3): no new format.
- Save as migration: gated by one `saveGate` (Enabled, or Disabled with the first reason that applies: "Add a step first", "Fix the N problem(s) in the plan first", "Choose a migrations folder first", the folder's own reason, "The folder is read-only"). It calls `repository.nextVersion()` and `repository.exportTopology(version, description, actions)` of the folder in use (the existing writer of a new file from actions, which never overwrites); a `MigrationWriteException` leaves the draft and shows "The file could not be written". On success the draft is cleared and the state carries `SavedPlan(version, fileName)` (`MigrationFile` has no path; the file name is `<version>.json` and the folder is the one in the store) for the screen to open the Migrations screen with, selected and pending. The message is "Plan saved as <version>. Nothing was sent to the broker. Apply it when you are ready."
- Dismiss: with at least one step it sets the question "Dismiss this plan? Its N steps are discarded. Nothing was sent to the broker."; confirm clears the draft; an empty plan is dismissed at once.
- Unreadable draft: `DraftUnreadable(path, reason)` in the state; "Start a new plan" asks with the real aside name and moves the file aside on confirm (the pattern of `MigrationsViewModel.startWithEmptyHistory`).
- Naming style: read from and written to `AppSettings.plannerNamingStyle` (new property; key `plannerNamingStyle` in `settings.json`; a missing key or an unknown value reads as the default); changing it renames nothing.
- The banner of the planner (one, the first that applies): connection cannot run migrations (3.x, unknown version: then no wizard, no save, no draft write, and the draft is left untouched); unreadable draft; Read-only level; no configure permission; not connected; folder problem; the plan's virtual host is not the connection's.
- `data-rabbit`: `FilePlanDraftStore` (`~/.caerbannog/planner-draft.json`, `AtomicFileWriter`, default file permissions; a draft holds no secret, A8) and the new key in `JsonFileAppSettings`.
- `core-testing`: `FakePlanDraftStore` (in memory, can be told to be unreadable or to refuse a write), and the new property on `FakeAppSettings`.

Out: any composable (`M9-03`); the check (`M9-05`, `M9-06`); opening a migration file (`M9-12`); applying (the Migrations screen does it, unchanged).

## Acceptance
- BDD: `19_topology_planner.feature` / "The naming style is remembered on this machine" (@fake-broker)
- BDD: `19_topology_planner.feature` / "The naming style is kebab-case until I choose another" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A plan is a draft until it is saved" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "Save as migration writes the next version" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "A saved plan is applied by the existing Apply" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A plan with a name that does not follow the naming style can be saved" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Saving never overwrites a migration file" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Dismiss discards the draft after a question" (@fake-broker)
- BDD: `19_topology_planner.feature` / "An empty plan is dismissed without a question" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A draft survives a restart" (@fake-broker)
- BDD: `19_topology_planner.feature` / "There is one draft at a time" (@fake-broker)
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
Stacked branch: `m9-02-planner-view-model-draft-and-save` is based on the head of `m9-01-plan-core-forms-and-naming` (`0cb1804`), because it uses `TopologyPlan`, `StepForms`, `FormContext`, `PlanValidation`, `PlanNaming`, `PlannerTexts` and `NamingStyle` from there; it merges after M9-01. Use the M9-01 API as it is: `TopologyPlan.append/replace/remove/moveUp/moveDown/withDescription/withVhost`, `PlanStep(id, action)`, `StepForms.formOf(action): StepForm?` (null for an action with no form, which the wizard then does not open), `StepForm.problems/actions/hints(FormContext)`, `PlanValidation.problems(plan): List<PlanProblem(stepId: Int?, message, blocking)>`.

Step ids: the view model keeps only `PlanStep.id` values that are live in the current plan. Selection, the step being edited and an undo entry are cleared or re-resolved by position when the step they name is removed (see the M9-01 note in Open points); never carry an id across a removal.

Save writes only through `MigrationFileRepository.exportTopology` (the existing writer, never overwrites, owner-only mode for a plan with an access action) and the draft only through `PlanDraftStore`; the view model has no other file or network path (A7). `PlanDraftStore.save` does not go through the migrations folder or `MigrationFileRepository`.

Model the planner as two sealed things, not flags: `PlannerMode` (`NewPlan` now, `EditFile` in `M9-12`) and `WizardState`. Results at the boundary are typed (`DraftLoad`, `DraftMoveResult`), never exceptions or text matching. `MigrationFileRepository.exportTopology` is reused as it is; its name is historical and may be given a neutral alias in this task only if no test has to change. The folder ports are the ones of the Migrations screen (`MigrationFolderPorts`), so choosing a folder from the planner is the same store and the Migrations screen follows it. Where the broker is faked: `KnownTopology` comes from `FakeTopologyCatalog`; no real broker in this task.

## UX
`docs/ux/Planner.dc.html`: the plan header, "2. Empty plan", "3." to "5." (wizard pages), "10. Save as migration", "11. Dismiss", "12. Blocked and limited states" (the texts and their order). This task builds the state and the texts; the drawing is `M9-03`.

## MCP
None (owner decision 2026-11-15). The draft is in the app's own data and no tool reads it; a saved plan is an ordinary migration file that `caerbannog_list_migrations` and `caerbannog_dry_run_migration` already see.

## Open points
- Product questions: none. The wording of the banner order and the save gate is fixed by the spec and the mockup sections named in UX.
- Q9 (decided 2026-11-15): the draft survives a restart, one draft at a time, Dismiss asks once when the plan has a step.
- Q8, Q5 (decided 2026-11-15): the style is chosen in the planner and remembered per machine, the default is kebab-case.
- Q11 (decided 2026-11-15): Apply happens on the Migrations screen; the view model only reports `SavedPlan`, where the screen goes is `M9-03`.
- A draft is one per machine and not per connection; a draft whose virtual host differs from the connection's is kept and shown with the banner "the plan's virtual host is not the connection's", and Save stays allowed (the Migrations screen applies per connection). This is as the spec stands; no change proposed.
- Note from the M9-01 review (architect, 2026-11-16): `TopologyPlan.append` hands out max id + 1, so after the last step is removed its id is given to the next appended step ("never repeats a live id" is all M9-01 promises). The view model must not key a selection, an undo entry or an in-flight edit on a step id across a removal; clear or re-resolve them when a step is removed. Add a unit test for it in `PlannerViewModelWizardTest`. `PlanProblem.stepId` is `Int?`: null is a problem of the whole plan (the parser round trip), shown at the top of the list, not on a step.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-16, base M9-01 head `0cb1804`, branch `m9-02-planner-view-model-draft-and-save`, round 0): brief checked against the spec (A3, A7, O5, Q5, Q8, Q9, Q11), the 19 `@m9-02` scenarios in `19_topology_planner.feature` (they match the 19 names in Acceptance one to one) and the real M9-01 code. Brief changed: `PlannerContext` is a core value (`MigrationsInputs` is in `desktopApp`, mapping is `M9-03`); added `TopologyPlan.of` (no factory in M9-01), `PlannerEntry.of(DraftLoad)` for "Continue plan" and "New plan", extraction of the private folder-state function of `MigrationsViewModel` as a shared pure one; `SavedPlan` carries a file name, not a path (`MigrationFile` has none); stacking on M9-01, the M9-01 API and the step-id rule written into the design notes. No scenario changed. No spike needed. Open product questions: none. Next: step 2 (test developer).
