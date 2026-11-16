# M9-12: A pending migration file opened as steps ("Edit steps")

Milestone: M9    Depends on: M9-03 (M9-07 and M9-10 add their recognition when they exist)    Size: M

PLANNED 2026-11-15. Not started.

## Goal
A migration file that is applied on no connection of this machine can be opened in the planner as steps, changed with the wizard and saved back under the same version. Everything the wizard cannot build is kept as written. A file is never rewritten without a change, never opened when it holds access actions or keys the app does not know, and never overwritten when it changed on disk meanwhile.

## Why
Spec "Topology planner (M9)": A2 (nothing is lost), A8 (access files stay out), the owner's concept ("a file that is not applied yet opens in the planner as steps instead of raw JSON; applied files stay read-only: checksum"). It is also the import half of "planning / executing / export / import": an imported file can be continued as steps.

## Scope
In:
- `core.planner`: `PlanOpening.decide(file, rawKeysKnown, appliedOn: List<String>)` giving `Openable` or `NotOffered(reason)` with the fixed texts: a `declareUser` ("This file holds password hashes. Edit it outside the app.", the existing `MigrationsWording.HASH_FILE_NO_EDIT`); any other broker-level action ("This file holds virtual hosts, users or permissions. The planner works on topology only."); a key the app does not know ("This file holds entries the planner does not know. Edit it as JSON so nothing is lost."); applied on a connection ("<version> is applied on <name>, so it is not edited", the name through the echo rule).
- `core.migrations`: every `ActionCodec` states the keys it reads (`val keys: Set<String>`), and `MigrationParser.hasUnknownKeys(rawJson): Boolean` answers whether the file, or an action in it, holds any other key (top level: `version`, `description`, `up`; per action: `action`, `vhost` and the codec's keys). A guard test proves that each codec reads exactly the keys it states.
- `PlannerMode.EditFile(version, baseChecksum)`: the plan is built from the file's actions (one step per action, read-only steps where `formOf` is null); "Save <version>" is enabled only after a change; before writing, the file is read again and its checksum compared with `baseChecksum` (a difference refuses the save with the fixed text and writes nothing); the write is `repository.updateFile(version, MigrationParser.exportToJson(version, description, actions))`; leaving with a change asks "Discard the changes to <version>?"; leaving without a change writes nothing. This mode never touches the draft of a new plan.
- "Copy into a new plan": the steps of a file that is not edited become a new draft; when a draft with steps exists the user is asked first ("Dismiss the current plan and copy <version> into a new plan?").
- `desktopApp`: `appliedOn(version)` from the migration history of every saved profile (the history is per profile on this machine).
- Migrations screen: the action row of a selected valid file gets "Edit steps" as the main button beside "Edit file", or the fixed text in its place.

Out: opening an invalid file (it stays in the invalid list); merging two files; the raw JSON editor's own rules (unchanged, including that it has no button for a file with a `declareUser`).

## Acceptance
- BDD: `19_topology_planner.feature` / "A pending migration opens as steps" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "Saving the steps of a pending file writes the same version" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Opening and closing a file without a change does not rewrite it" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Leaving with unsaved changes asks first" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Steps the wizard cannot build are kept as written" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A migration that is applied on any connection of this machine is not edited" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A file with password hashes is never opened in the planner" (@fake-broker @negative @security)
- BDD: `19_topology_planner.feature` / "A file with virtual hosts or permissions is not opened in the planner" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A file with entries the planner does not know is edited as JSON only" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A file that changed on disk while it was open is not overwritten" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "The raw JSON editor stays beside "Edit steps"" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Editing a file leaves the draft of a new plan alone" (@fake-broker)
- Unit tests, written first: `PlanOpeningTest` (one test per refusal and their order: hash, access, unknown keys, applied elsewhere), `MigrationParserUnknownKeysTest` and `ActionCodecKeysGuardTest` (core.migrations), `PlannerViewModelEditFileTest` (no write without a change, same bytes after open and close, checksum guard, discard question, the draft untouched), `PlanRoundTripTest` (every action kind of the format, with arguments, flags, deletes and operator policies: open, add one step, save, parse: the old actions are equal and in place). Compose: `MigrationsEditStepsUiTest`, `PlannerEditFileUiTest`.
- A test with a sentinel hash in a `declareUser` file: the planner state, the draft file and every text never hold it, and no `PlannerViewModel` is created for that file.

## Design notes
The first save of a hand-written file rewrites it in the app's layout (the writer of every export); that is why a file with unknown keys is refused instead of silently losing them, and why nothing is written without a change. `MigrationAction.DeclareUser` cannot reach the planner (refused before a plan is built), so `SecretValue.expose()` gains no caller. The status "applied" is per connection: the rule here is stricter than the Migrations list (any profile, not only the current one). No broker in this task.

## UX
`docs/ux/Planner.dc.html`: "13. A pending migration file opened as steps", the second row of "1. Entry", "7. Step list" (read-only steps).

## MCP
None (owner decision 2026-11-15).

## Open points
- Q16 (decided 2026-11-15): "Edit steps" is the main button beside "Edit file".
- Q10 (decided 2026-11-15): deletes are not built by the wizard; a delete in a file is a read-only step (already the rule here).

- Stand-ins from M9-07 (architect, step 2.5, 2026-11-16): `PlannerStreamSteps` (acceptance-tests, `planner` package) binds `a pending migration {string} that declares the streams {string} and {string}` (puts the actions in the draft file) and `I choose {string} for {string}` (restarts the planner on that draft; it knows only "Edit steps"). They serve the two `@m9-07` scenarios "A saved pair is shown as a pair again when its name follows the rule" and "Two streams whose names do not follow the rule are two streams" (feature 21, `I choose "Edit steps" for "0005"`). M9-12 must REPLACE the step `I choose {string} for {string}` with the real Migrations screen button (one binding only, or Cucumber reports an ambiguous step) and bind the pending-migration step to a real pending file, then run those two scenarios against the real path. A response stream recognised by `PlanPairs` is opened as an ordinary stream step (`StepForms.formOf(DeclareStream)` gives `StreamForm(name)`, no tick).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
