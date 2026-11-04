# Choose the topology control level while creating a connection

Milestone: M1    Depends on: M1-05-wire-desktopapp-connections (screen is wired; branch base), M1-02-connection-profiles    Size: S

## Status
- State: done (accepted with follow-ups, round 0; step 5 architect review; not merged). Briefed earlier. Product decisions taken by the product owner: (1) the level is selectable on the form
  while creating a connection; (2) in edit mode it applies on Save, one rule everywhere; (3) choosing Full control asks
  for a confirmation.
- Branch (when started): `m1-10-topology-level-on-new-connection`, based on `m1-05-wire-desktopapp-connections`.
- Merge note: `m1-03-permission-check` also changes `ConnectionProfile` and `ConnectionsViewModel` (reopen after save,
  declared permissions). Expect a conflict in `ConnectionsViewModel.kt`; resolve at merge, whoever merges second.
- Step 2 (test developer): added 2 BDD scenarios in `01_connections.feature` (+ step `topology control level` row,
  two new steps in `ConnectionsSteps.kt`), 5 view model tests in `ConnectionsViewModelTest`, 14 UI tests in
  `ConnectionsScreenUiTest`. Red: core and acceptance-tests fail to compile (no `topologyControlLevel` parameter on
  `ConnectionProfileInput`); UI tests compile and fail on assertions (11 of 19). The UI tests check the persisted level
  through the in-memory repository, so they need no production signature.
- Step 3 (developer): `ConnectionProfileInput.topologyControlLevel` (null: add = READ_ONLY, update = keep, incl. rename);
  `EditPanel` holds level + `pendingLevel` as form state, `FullControlConfirmDialog`, helper text; dead
  `onTopologyControlChange` removed. Green: core 11, ui 19, acceptance 19 (1 skipped, @broker), data-rabbit fast, desktopApp.
  Note: `./gradlew test` does not run core/ui `jvmTest`; run `:core:jvmTest :ui:jvmTest` too.
- Step 4 (test developer, verify): PASS, no blocker or major. `./gradlew test :core:jvmTest :ui:jvmTest --rerun-tasks
  --continue` green: core 11, ui 19, acceptance 19 run / 1 skipped ("Switch between connections", @needs-M2, by design),
  desktopApp 3, data-rabbit fast green. 7 mutations of the key rules each fail the right tests; tree restored.
  Plain `./gradlew test` (= `task test`) does NOT run core/ui `jvmTest` (dry-run lists only acceptance-tests,
  data-rabbit, desktopApp): the documented command misses two modules. Open for the architect.
- Step 5 (architect review): Accepted with follow-ups. Follow-up: `M1-TASKFILE-02-test-task-runs-all-modules`
  (plain `./gradlew test` skips core and ui). Mockup `docs/ux/Main.dc.html` now shows the selector and the dialog.
  Stale `brokerTest` XML under `data-rabbit/build` (RealBrokerPermissionCheckTest, 6 failures) is output from an
  earlier run on `m1-03-permission-check`, where that class exists; not from this branch, no action.
- Round: 0

## Goal
On the "New connection" form the three Topology control choices (Read-only, Declare and edit, Full control) are
enabled, default Read-only, and the chosen level is saved with the new profile. On the "Edit connection" form the
same choice is applied when the user presses Save, like every other field on the form.

## Why
Spec "Self-constraint level": the level is the app's own ceiling, set explicitly per connection, default Read-only.
Today the radios are disabled for a new connection (`enabled = selected != null`), so the user must save, reopen and
click, which is a needless detour for a deliberate setting.

## Scope
In:
- `core`: let the add path and the update path carry a level. Recommended shape: add
  `topologyControlLevel: TopologyControlLevel? = null` to `ConnectionProfileInput`. Meaning: on `addConnection`,
  null means `READ_ONLY`; on `updateConnection`, null means "keep the saved level" (same convention as `password`).
  Do NOT make the field non-null with a READ_ONLY default: an update from a caller that does not know the field would
  silently lower a saved Full control to Read-only. Update the KDoc of `ConnectionProfileInput` (it currently says the
  level is deliberately not carried; the dedicated `setTopologyControlLevel` stays for programmatic use and for the
  existing scenario, it is not removed).
- `ui` `ConnectionsScreen.kt` `EditPanel`: hold the level as form state (`remember(selected?.name)`, initial value
  `selected?.topologyControlLevel ?: READ_ONLY`), enable the selector in both modes, pass the value in
  `ConnectionProfileInput` on Save. Clicking a radio changes form state only, nothing is persisted until Save. Choosing Full control first shows a
  confirmation (see Confirmation below). Remove
  the `onTopologyControlChange` parameter if nothing else uses it.
- Short helper text under the selector, fixed wording: "The most this app will do to topology on this connection.
  The broker's own permissions still apply." (Wording is new: see Open points, UX.)
- Confirmation for Full control, UI-only (see below), including its text and the two buttons.
Out:
- Any enforcement of the level (effective permission, M1-03/M2+).
- Persistence: unchanged, `topologyControlLevel` is already stored by `JsonFileConnectionProfileRepository`.
- Declared permissions UI (`M1-06-profile-declared-permissions` on the m1-03 branch), sidebar pill (`M1-07`).
- Any core or BDD-visible confirmation: the confirmation is UI-only state.

## Confirmation (decision: at pick time, UI only)
- When: the moment the user selects "Full control" in the form, in both New and Edit mode, when the form's current
  selection is not already Full control. Not at Save. Why: the click is the deliberate act; asking at Save would
  also fire on unrelated edits of a profile that already is Full control, or be forgotten behind a long form. Picking
  Full control when it is already selected, or going Full -> Read-only, asks nothing; Full -> Declare and edit -> Full
  asks again (each raise is a fresh act).
- Form: a Material 3 `AlertDialog`. Title: "Allow full control?". Text, fixed: "Full control lets this app delete
  topology (queues, streams, exchanges, bindings, policies) on this connection and run migrations with destructive
  actions. This is only your own ceiling for this connection: the broker's permissions still apply, and the app never
  reads, gets or purges queue messages." Buttons: "Use Full control" (confirm) and "Cancel".
- Cancel (or dismissing the dialog with Esc or a click outside) leaves the previous selection untouched. Confirm sets
  the form selection to Full control; it is still persisted only on Save.
- State: a local `remember` boolean/pending-level in `EditPanel`; no core change, no new state in the view model, no
  new BDD scenario (nothing core-observable). Do not use a boolean that switches behaviour: model the pending choice
  as `pendingLevel: TopologyControlLevel?` (non-null means the dialog is open).

## Acceptance
- BDD, extend `tests/bdd/features/01_connections.feature` (all plain, no tag, run by `:acceptance-tests`):
  - Keep "A new connection defaults to read-only topology control" unchanged (it must still pass: no level given).
  - New: "A new connection can be created with a chosen topology control level": add a connection with the settings
    table plus a row `topology control level | Full control`; then its topology control level is "Full control".
    The step "I add a connection with these settings" in `ConnectionsSteps.kt` learns the optional row; the
    existing then-step is reused.
  - New: "Editing a connection without touching the level keeps it": given a saved connection "uat-eu" at "Full
    control", when I change its host and save, then its topology control level is still "Full control". (Guards the
    null-means-keep rule.)
  - "Raise a connection's topology control level" stays as is (dedicated setter).
- UI tests in `ui` (`ConnectionsScreenUi`, same style as the existing five):
  - New connection mode: all three radios are enabled and Read-only is selected.
  - New connection: pick "Declare and edit", fill the fields, Save: the view model receives `DECLARE_AND_EDIT`.
  - Confirm path: in New mode, click "Full control": the dialog with the fixed text appears and the selection is still
    Read-only; press "Use Full control": the selection is Full control and Save sends `FULL_CONTROL`.
  - Cancel path: click "Full control", press "Cancel": no dialog, selection is the previous one (test from Read-only
    and from Declare and edit); Save sends the previous level. Dismiss by Esc behaves like Cancel.
  - No dialog for Read-only, Declare and edit, or re-clicking an already selected Full control.
  - Edit mode: picking "Full control" does not change the saved profile until Save is pressed; after Save it is
    saved. Selecting a different profile resets the radios to that profile's saved level.
- View model unit tests (`ConnectionsViewModelTest`): add with a level stores it; add with null stores READ_ONLY;
  update with null keeps the level; update with a value changes it.
- Write the tests first and see them fail for the right reason.

## Design notes
- Modules: `core` (input type, view model), `ui` (form state). No change in `data-rabbit`. All tests are fake-based
  (`@fake-broker` is not needed, no broker is involved).
- Why apply on Save in edit mode (decision, least surprising and consistent): every other field on the form is applied
  on Save, and a new connection can only apply on Save; a single rule "the form is applied when you press Save" is
  easier to understand than "this one control acts at once". It also makes an accidental click on Full control
  harmless until the user confirms by saving, and it is one write instead of two. Cost: a behaviour change for saved
  profiles (today a click applies immediately).
- Rename path: `renameTo` copies from `updated`, which is built from `original.copy(...)`; make sure the resolved
  level (input or kept) is part of that copy so a rename plus level change is one consistent write.
- M1-03 interaction: there `updateConnection` `Saved` calls `session.reopen`. A level change alone does not affect the
  broker-read check, so no extra reopen is needed; just do not add one inside `setTopologyControlLevel`.

## UX
`docs/ux/Main.dc.html`, connection edit panel. Mismatch: the mockup has no Topology control selector at all (it shows
only the permission check banner and its note). The Compose screen has had the selector since M1-02. The architect
asks for a small addition to the mockup (three radios under the CA certificate field, default Read-only, helper text
above) and the Full control confirmation dialog (text above) before or alongside this task; this does not block the
code. States to build: new (default Read-only, enabled), edit (saved level, enabled), confirm dialog open (selection
unchanged underneath), no locked or disabled state any more.

## Open points
- Closed by the product owner: apply on Save in edit mode; Full control asks for confirmation.
- Open, minor, no decision needed unless the owner objects: the dialog's wording above is the architect's proposal;
  confirmation also fires on a New form (default Read-only to Full control), which is intended.
- Spec: sentences added to `docs/spec.md` "Self-constraint level" by the architect (done with this update).

## Definition of done
Tests first and passing (`./gradlew test`), no unrelated changes, `docs/tasks/README.md` updated, the new scenarios
in `01_connections.feature`, the `ConnectionProfileInput` KDoc corrected, the mockup addition done by the architect.
