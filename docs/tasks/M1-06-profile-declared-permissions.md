# Declared permissions on the connection profile (model, persistence, edit panel)

Milestone: M1    Depends on: M1-03-permission-check (core `DeclaredPermissions`, session rule)    Size: S

## Status
- State: step 2 done (test developer): tests written, red as expected; next: step 3 build (senior developer).
- Branch (when started): `m1-06-profile-declared-permissions`, based on `main`.
- Round: 0, step 2 done (test developer), branch `m1-06-profile-declared-permissions`.
  - Red, core: `:core:jvmTest` does not compile, `Unresolved reference 'DeclaredPermissionsEdit'` (ConnectionsViewModelTest: 12 new
    tests; ConnectionsViewModelPermissionTest: 6 new). Also `:acceptance-tests:compileTestKotlin` (steps use the new type).
  - Red, `data-rabbit` (`JsonConnectionProfileRepositoryTest`): 3 of 7 new tests fail (declaration not written/read; the key is
    rejected as unknown). Green already, kept as characterisation/guards: M1-02 fixture loads with no declaration, undeclared
    profiles write exactly the fixture text (golden, no key), incomplete declaration object is refused.
  - Red, `ui`: 16 of 20 new tests fail on assertions (switch not found); 4 are already green by design (switch off saves nothing, an
    untouched Save keeps the declaration, other fields independent).
  - Red, BDD (checked with a throw-away stub of the core type, removed again): "Declared permissions are saved with the profile"
    fails on the assertion; "A profile saved before declarations existed has none" is green already (loads the fixture with the real
    `JsonFileConnectionProfileRepository`; `acceptance-tests` -> `data-rabbit` pulls no unstable runtime: the keyring library is not
    loaded by this scenario). It guards back-compat and must stay green.
  - Contract the UI tests pin beyond the brief: the two yes/no choices are RadioButtons in the order configure-Yes, configure-No,
    write-Yes, write-No, shown (as radios 3..6 after the three topology radios) only while the Role.Switch is on. A different widget
    needs a test change agreed with the test developer.
  - Fixture: `data-rabbit/src/test/resources/fixtures/connections-m1-02.json` (copy in `acceptance-tests/src/test/resources/fixtures/`).
- Brief revised: apply-on-Save through `ConnectionProfileInput` (no `setDeclaredPermissions`), clear-by-sentinel,
  no reopen in `setCaCertificate`, one switch plus two yes/no choices instead of three-state per right, UI session
  wiring left to M1-07. Reasons in Design notes.

## Goal
The user can say, per connection profile, whether their broker user has configure and write permission; the
declaration is saved with the profile, survives a restart, and a profile file written before this existed still loads.
At view model level, saving a profile re-runs the permission check with the new declaration (this already holds for
`saveProfileChanges`; the screen does not call it until M1-07 wires the session).

## Why
Spec "Broker-read permission" / "Declared permissions" (decision D). Only administrators can read permissions, so
without this a normal stream user is stuck in safest mode.

## Scope
In:
- `core` `ConnectionProfile.declaredPermissions` already exists (`DeclaredPermissions?`, last parameter); only fix its
  KDoc ("Not persisted yet").
- `data-rabbit` `JsonFileConnectionProfileRepository`: private DTO gets `declaredPermissions: DeclaredPermissionsDto? = null`
  (nested `@Serializable` class with `configure: Boolean`, `write: Boolean`, no defaults). `Json { prettyPrint = true }`
  does not encode defaults, so a profile without declaration writes no key (file unchanged for users who do not use
  it) and an old file loads as null. No version number for an additive optional field. `Json` keeps its default of NOT
  ignoring unknown keys, so an older build cannot read a file that has the key (downgrade unsupported, local-only
  app); say so in the DTO comment. Map both ways like `topologyControlLevel`. Never serialize the core type.
- `core` `ConnectionProfileInput` gets `declaredPermissions: DeclaredPermissionsEdit = DeclaredPermissionsEdit.Keep`,
  a sealed type in `core`: `Keep` (add: no declaration; update: keep the saved one, incl. rename), `Clear` (remove
  the declaration), `Set(DeclaredPermissions)`. Reason: M1-10's "null means keep" cannot also mean "not declared";
  a sentinel keeps the convention (default = keep) and makes clearing explicit. `addConnection` maps `Keep` and
  `Clear` to null. `updateConnection` applies it; `saveProfileChanges` is unchanged (it already reopens after every
  successful save).
- No `setDeclaredPermissions` setter: apply-on-Save is the one rule (M1-10), and the edit panel already sends every
  field through `ConnectionProfileInput`. Do not add a second path.
- Reopen rule (settled): `saveProfileChanges` reopens after every successful save, whatever changed (the check is
  cheap, a password or host change also needs it, and change detection would be a second rule to test). A failed save
  runs no check. `setCaCertificate` does NOT get a reopen: the screen calls it on every keystroke of the CA field and
  saves immediately (existing behaviour, not apply-on-Save); a reopen there would run a check per keystroke and make
  it suspend. The next Save reopens with the saved CA path. A follow-up "CA certificate applies on Save" is an idea,
  not part of this task.
- `ui` `EditPanel`, below "Topology control", above "Permission check": a switch "I know what this user may do on the
  broker"; when off the declaration is null (not declared). When on, two yes/no choices: "Can change topology
  (configure)" and "Can publish (write)", each defaulting to No when first switched on. Form state like the level;
  applied on Save only: off sends `Clear`, on sends `Set(DeclaredPermissions(...))`. Fixed helper text: "Only used when
  the broker does not let this user read its own permissions. This is your own statement; the broker still enforces
  its permissions." The label must make clear it is the user's own claim. The fields are always shown, also when a
  broker check succeeded (the declaration is then simply ignored; the explanation is M1-07).
Out:
- Sidebar pill, banner, topology-screen wording, "declared vs verified" display (`M1-07`).
- Wiring `ConnectionSession` into the screen; the edit Save keeps calling `updateConnection` (`M1-07`).
- Any change to how the check or the session decides (`M1-03`).

## Acceptance
- BDD (`tests/bdd/features/02_permission_check.feature`, `@fake-broker`): remove `@needs-M1-06` from "Declared
  permissions are saved with the profile" and "A profile saved before declarations existed has none", and change the
  runner filter in `RunPermissionCheckAcceptanceTest.kt` to `not @needs-M2 and not @needs-M1-07 and not @broker`
  (update its KDoc). Update the `@needs-M1-06` row in `tests/bdd/README.md` (tag no longer used) in the same commit.
  Scenario 1 runs on the in-memory repository through `saveProfileChanges` with `Set(...)`. Scenario 2 needs a real
  file: add `testImplementation(project(":data-rabbit"))` to `acceptance-tests/build.gradle.kts` (project-internal,
  no new third-party library; update the build-file comment that says it is avoided) and let the step write the
  old-format fixture into a temp dir and load it with `JsonFileConnectionProfileRepository`; the "safest mode" step
  then runs the session with that loaded profile. If that dependency turns out to pull a heavy or unstable runtime
  (keyring native libs), stop and tell the architect; the fallback is a reworded scenario on the in-memory fake.
- Unit, `data-rabbit` (`JsonConnectionProfileRepositoryTest`): round trip with and without a declaration (all four
  boolean combinations are not needed: yes/no and no/yes suffice); loading a fixture file that is the literal M1-02
  format (copy it as a test resource); the file written for a profile without a declaration equals a golden text
  with no `declaredPermissions` key; a file with the key loads it.
- Unit, `core` (`ConnectionsViewModelTest`): add with `Set` stores it; update with `Keep` keeps it (also on rename);
  `Set` replaces; `Clear` removes; `saveProfileChanges` with a changed declaration calls `reopen` once and the session
  then decides with the new declaration (use the existing fake checker: Unreadable + declared yes gives DeclaredByUser).
  Dropped from the old brief: "identical declaration does not reopen".
- UI (`ConnectionsScreenUiTest`): new connection with the switch off saves no declaration; switch on plus Yes/No saves
  `DeclaredPermissions`; editing a profile shows its saved state (off, or on with the right values); switching off and
  Save clears it; nothing is saved before Save; helper text is shown; password and other fields unaffected.

## Design notes
Persistence stays in `data-rabbit`, `core` has no serialization (existing rule in the repository's class comment).
`DeclaredPermissionsEdit` lives in `core` next to `ConnectionProfileInput`. `DeclaredPermissions` stays a pair of
required booleans (spec: "configure yes/no and write yes/no"); that is why the UI is one switch plus two yes/no
choices and not three-state per right: a per-right "not declared" would create half declarations the model and the
M1-03 decision do not define. `@fake-broker` only, no real broker.

## UX
`docs/ux/Main.dc.html`, connection edit panel: the new "Declared permissions" group is added to the mockup (docs
only, done by the architect). Build states: not declared (switch off, choices hidden), declared yes/no combinations.

## Open points
- Per-right "not declared" (model change to `Boolean?` per right, spec and M1-03 touched) was considered and not
  chosen; revisit only if users ask to declare one right only.
- Whether to show the declaration while a broker-read result wins: decided here as "fields always shown, ignored",
  the explanatory wording is M1-07.
- CA certificate saves immediately instead of on Save: idea for a later task, not blocking.

## Definition of done
Tests first and passing (`./gradlew test :core:jvmTest :ui:jvmTest` or `task test`), no unrelated changes, KDoc on
`ConnectionProfile.declaredPermissions` corrected, `docs/tasks/README.md` and the feature tags updated.
