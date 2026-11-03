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
