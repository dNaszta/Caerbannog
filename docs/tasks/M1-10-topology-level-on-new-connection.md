# Choose the topology control level while creating a connection

Milestone: M1    Depends on: M1-05-wire-desktopapp-connections (screen is wired; branch base), M1-02-connection-profiles    Size: S

## Status
- State: briefed, not started. Product decision taken by the product owner: the level must be selectable on the form
  while creating a connection.
- Branch (when started): `m1-10-topology-level-on-new-connection`, based on `m1-05-wire-desktopapp-connections`.
- Merge note: `m1-03-permission-check` also changes `ConnectionProfile` and `ConnectionsViewModel` (reopen after save,
  declared permissions). Expect a conflict in `ConnectionsViewModel.kt`; resolve at merge, whoever merges second.
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
  `ConnectionProfileInput` on Save. Clicking a radio changes form state only, nothing is persisted until Save. Remove
  the `onTopologyControlChange` parameter if nothing else uses it.
- Short helper text under the selector, fixed wording: "The most this app will do to topology on this connection.
  The broker's own permissions still apply." (Wording is new: see Open points, UX.)
Out:
- Any enforcement of the level (effective permission, M1-03/M2+).
- Persistence: unchanged, `topologyControlLevel` is already stored by `JsonFileConnectionProfileRepository`.
- Declared permissions UI (`M1-06-profile-declared-permissions` on the m1-03 branch), sidebar pill (`M1-07`).
- A confirmation dialog for Full control (see Open points).

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
above) before or alongside this task; this does not block the code. States to build: new (default Read-only,
enabled), edit (saved level, enabled), no locked or disabled state any more.

## Open points
- Decision needed, not blocking: apply-on-Save (recommended, above) versus keeping immediate apply in edit mode.
  Immediate apply keeps today's behaviour but makes new and edit behave differently. If the product owner prefers
  immediate apply for edit, only the edit-mode UI tests and the "Out" of this brief change.
- Decision needed, not blocking: should choosing Full control ask for a confirmation? Recommendation: no for now;
  the level is only a ceiling, the broker's permissions and the later effective-permission rule still apply, and the
  default stays Read-only. Revisit in M5 when delete exists.
- Spec: `docs/spec.md` "Self-constraint level" does not say where the level is set. Add one sentence after the table
  once decided: "The level is chosen on the connection form, when the profile is created or edited, and applies when
  the form is saved." Architect does this on acceptance, not before.

## Definition of done
Tests first and passing (`./gradlew test`), no unrelated changes, `docs/tasks/README.md` updated, the new scenarios
in `01_connections.feature`, the `ConnectionProfileInput` KDoc corrected, the spec sentence and the mockup addition
done by the architect.
