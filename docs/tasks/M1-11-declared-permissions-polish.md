# Declared permissions polish (wording, guard comment, mockup)

Milestone: M1    Depends on: M1-06-profile-declared-permissions    Size: S

## Goal
Small leftovers of M1-06 are closed: the edit panel title says the declaration is the user's own statement, the
acceptance module documents why steps must not touch the keychain, and the mockup matches the built widgets.

## Why
Spec "Declared permissions": a declaration must never look like verified information. M1-06 review findings 2 and 3.

## Scope
In:
- `ui` `DeclaredPermissionsSection`: title text "Declared permissions (your own statement)" as in `docs/ux/Main.dc.html`
  (update the UI test that finds the title, if any).
- `acceptance-tests/build.gradle.kts`: comment next to `project(":data-rabbit")` that steps must not instantiate the
  keychain-backed secret store (keyring and JNA native libs are on the classpath only for the file repository).
- Architect (docs only, done at the same time): the mockup shows the two yes/no choices as Yes/No radio pairs, as built.
Out: CA certificate applies on Save (idea, separate task if wanted); acceptance scenario for Keep.

## Acceptance
- BDD: none new. `./gradlew test` stays green.

## Design notes
No behaviour change.

## UX
`docs/ux/Main.dc.html`, connection edit panel.

## Open points
None.

## Definition of done
Tests first (title assertion) and passing, no unrelated changes.
