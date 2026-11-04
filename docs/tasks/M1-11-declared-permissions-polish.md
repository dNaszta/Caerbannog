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
  (no existing test asserts the title text, so there is nothing to change; a NEW assertion is added tests-first, see Acceptance).
- `acceptance-tests/build.gradle.kts`: comment next to `project(":data-rabbit")` that steps must not instantiate the
  keychain-backed secret store (keyring and JNA native libs are on the classpath only for the file repository).
- Architect (docs only, DONE on main): mockup `Main.dc.html` now shows the two yes/no choices as Yes/No radio pairs, as built. The build does not change (the single switch stays a switch).
Out: CA certificate applies on Save (idea, separate task if wanted); acceptance scenario for Keep.

## Acceptance
- BDD: none new. `./gradlew test` stays green.
- UI test (tests-first, red before the string change): in `ConnectionsScreenUiTest.kt`, new test asserting `onNodeWithText("Declared permissions (your own statement)")` exists (scroll to it), next to `the declaration switch, its label and the fixed helper text are shown`. Pinned strings (switch label, helper, configure/write labels) do NOT change.
- Guard comment and mockup: not testable, checked in review.

## Design notes
No behaviour change.

## UX
`docs/ux/Main.dc.html`, connection edit panel.

## Open points
None.

## Definition of done
Tests first (title assertion) and passing, no unrelated changes.

## Status
- State: step 3 done (senior developer, 2026-11-04): title string changed, guard comment added, full `./gradlew test --rerun-tasks --continue` green; waiting for step 4 (verify) and review. Branch `m1-11-declared-permissions-polish`.
- Mockup radios done by the architect on main (docs only).
- Test developer: DONE: `the declared permissions section title says it is the user's own statement` in `ConnectionsScreenUiTest.kt`, red (no node with the exact text). Next: the senior developer changes the one string in `DeclaredPermissionsSection` and adds the guard comment in `acceptance-tests/build.gradle.kts`.
- Open points: none; no product decision needed.
