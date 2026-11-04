# Connections screen: surface save errors, clear stale banners

Milestone: M1    Depends on: M1-02-connection-profiles (accepted/merged)    Size: S

## Goal

Saving a connection profile over a name that already exists shows a visible, specific error (never a
silent no-op), and switching the selected profile never shows a check-connection (or other) banner left
over from a different profile.

## Why

Found during `M1-02-connection-profiles`'s step-4 verification (test developer, exploratory, not a
scenario failure) and confirmed by the architect at step 5 review: `ConnectionsScreen` never reads
`ConnectionsState.lastSaveResult`, so a user hitting Save on a colliding name sees nothing happen — no
error, no feedback, button just does nothing visible. Separately, `ConnectionsState.lastCheckResult`
survives a `selectConnection`/`startNewConnection` call, so a stale "Connection test passed" or error
banner from the previously selected profile can show against the newly selected one.

This is a gap in `M1-02`'s own UX section, not a developer defect: that section's "Build these states"
list never named a Save-error state, so the senior developer built exactly what was asked. Root cause is
the brief, fixed here rather than reopening `M1-02`. Both gaps work against the product's own house rule
(`01_connections.feature`'s Check-connection scenarios: "no generic 'something went wrong'" — extended
here in spirit to "no silent nothing happened" and "no stale result from a different profile").

## Scope

**In:**

- `ConnectionsScreen`'s `EditPanel`: render `ConnectionsState.lastSaveResult` when it is
  `SaveConnectionResult.NameAlreadyExists` — an inline error (same visual treatment as
  `CheckConnectionBanner`'s error state) naming the colliding name, e.g. `A connection named 'uat-eu'
  already exists.`
- `ConnectionsViewModel.selectConnection` and `ConnectionsViewModel.startNewConnection`: clear
  `lastCheckResult`, `lastSaveResult` and `lastCaCertificateValidationResult` in the state update, so no
  banner from a previous selection survives a switch.

**Out:**

- No new Gherkin scenario (Compose rendering of view-model state is not something Cucumber drives per
  `M1-02`'s own Design notes decision 4 — "Cucumber drives domain behavior, not Compose selection
  state"; the equivalent rule applies to rendering a result the view model already computed correctly).
  A Compose UI test is the right level, same style as the existing `ConnectionsScreenUiTest`.
- No change to `SaveConnectionResult`, `ConnectionCheckResult`, or any repository/secret-store/checker
  interface — this is screen-state wiring only.
- No permission-check logic (`M1-03`, unbriefed, still owns that).

## Acceptance

- New cases in `ui/src/jvmTest/kotlin/dev/caerbannog/ui/connections/ConnectionsScreenUiTest.kt`:
  - Saving a new connection with a name that already exists renders an inline error naming that name
    (not a silent no-op).
  - After a successful "Check connection" on profile A, selecting profile B no longer shows A's banner.
  - After a successful "Check connection" on profile A, clicking "New connection" no longer shows A's
    banner.
- No BDD feature change required (see Scope/Out).

## Design notes

Module/boundary unchanged from `M1-02`: `ui` + `core` only, no new interfaces. Use the same local fakes
pattern already in `ConnectionsScreenUiTest` (`InMemoryRepository`, `NoOpSecretStore`,
`AlwaysReachableChecker`, `AlwaysValidCertificate`) — add an `AlwaysCollidingChecker`-equivalent only if
needed (a plain in-memory repository that already has a profile under the target name is enough to
trigger `NameAlreadyExists` without a new fake).

## UX

Screen: `docs/ux/Main.dc.html`. No new screen. Amend `M1-02`'s UX section in spirit (not restating the
whole screen here): the "Loaded, profile selected (Editing)" state additionally needs a Save-error
sub-state with the same visual weight as the Check-connection error banner, and every banner
(save-error, check-connection result, CA-certificate-rejected) is scoped to the currently selected
profile only — never left over from a previous selection.

## Open points

None. This is a corrective, tightly scoped brief; no open design questions remain.

## Definition of done

- Tests written first and passing (the three cases above).
- No unrelated changes.
- `docs/tasks/README.md` updated.

## Status

- Step: 4 (verification passed; awaiting architect verdict)
- Round: 1
- Branch: `m1-04-connections-screen-feedback-gaps`
- Order note (architect, 2026-11-04): picked before `M1-05` because it is corrective to already-accepted
  `M1-02` and changes only `ui`/`core`; `M1-05` is independent and lands after it so the first human run
  of the real screen already has both fixes. `M1-03` (permission check) is still unbriefed and is not a
  dependency of either.
- Tests first: the three `ConnectionsScreenUiTest` cases must fail for the right reason (no error shown;
  stale banner shown) before any production change.
- Tests (test developer, step 2): three cases added to `ConnectionsScreenUiTest` (name collision error naming
  `uat-eu`; select-another-profile clears banner; New connection clears banner). They compile. The red run
  could not be executed in the test developer's sandbox (Gradle test-executor process spawn blocked), so
  failing-for-the-right-reason is unconfirmed; expected failures are "node with text ... does not exist"
  (collision) and "expected no node, found banner" (the two stale-banner cases).
- Build (senior developer, step 3): red confirmed (assertion errors). Production done: `EditPanel` renders
  `NameAlreadyExists` as an error banner (`ErrorBanner`, shared with the check-connection error);
  `selectConnection`/`startNewConnection` clear check, save and CA results; `refreshAfter` already sets
  `lastSaveResult` for add, update and rename. Also made the edit panel `verticalScroll`: at the test
  window (1024x768) it is taller than the window, so the banner and buttons were off-screen.
  The three tests still fail for TEST reasons, not production: (1) `onNodeWithText("New connection")` matches
  two nodes (sidebar button and panel title) in the collision test; (2) clicks on "Check connection" and
  "Save" land off-screen, so need `performScrollTo()` first. With those test edits (patch kept at
  scratchpad `test-fix.patch`) all 6 tests pass. Test developer to apply.
- Verification (test developer, step 4): test defects fixed (patch applied, commit after 777192d). Mutation check: with the
  production behaviour reverted (scroll kept) the 3 new tests fail on the assertions (collision banner node missing; stale
  banner node still present). Restored: ConnectionsScreenUiTest 5/5 (the new 3 plus 2 existing), core jvmTest 6/6, ui jvmTest
  6/6, acceptance-tests (Cucumber, `not @needs-M2`; includes @smoke and @fake-broker of 01_connections) 17 run, 1 skipped, 0 failed.
  @broker not run: Docker unavailable here and no @broker scenario exists for this task. No blocker or major findings.
