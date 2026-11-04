# Connection session as the single source of truth

Milestone: M2    Depends on: none (M1 merged into `main`)    Size: M

Branch: `m2-01-connection-session`, created from `main` (ac3d166 or later). Merge back into local `main`; nothing pushed.

## Goal
`ConnectionSession` is the one place that says which connection is current and what its permissions are; the sidebar
(drawer Connection card) and every later M2 screen read from it, and an older, slower permission check can never
overwrite the result of a newer one.

## Why
Spec "Risks" (`ConnectionSession.open` is last-write-wins; prerequisite for M2) and "Topology migrations and permission
guardrails". The M2 stream list and topology screen need a current connection; today the drawer Connection card is a
stand-in that shows whichever profile is open in the edit panel (`ui/.../connections/ConnectionsFrame.kt`, "M1 has no
session yet"). `ConnectionsViewModel` also keeps its own per-name check state next to the session: two sources of truth.

## Scope
In:
1. Test first, then fix `ConnectionSession.open`: keep only the result of the latest STARTED check per profile name, with a
   token like `ConnectionsViewModel` (a late result of an older check is dropped, and does not make the connection
   "usable" or overwrite a newer result; `forget` during a running check still wins, as today). Cancellation and the
   `UNEXPECTED` rules from M1-09 stay unchanged.
2. A current connection in the session: `current: StateFlow<...>` (name plus its latest `PermissionCheckResult` or
   "checking" / "not checked"), set by an explicit `select`/connect call (name chosen by the owner of the Connections
   screen), cleared when the profile is deleted. Exact type names are the senior developer's; core stays free of JVM-only
   and broker types.
3. `ConnectionsViewModel` takes its permission state from the session instead of a parallel map, keeping its existing
   token guard for the save/edit flow only if still needed after (2); no behaviour change visible on the Connections
   screen (all M1-07/M1-12a UI tests stay green unchanged).
4. Replace the drawer Connection card stand-in with the session's current connection (name, host, pill); with no current
   connection it keeps the "No connection selected" text already asserted by the M1-12a tests. The pill text and colours
   are unchanged (spec "Declared permissions", product decision from `M1-07`).
5. A way for the UI to make a saved profile current (connect/switch). For this task: a button or card action on the
   Connections screen is enough; the final place (sidebar connection picker) comes with the stream list screen
   `M2-05`. Choose the smallest thing that makes the scenario below drivable and tell the architect what you chose.
Out: stream list, any management API call beyond the existing permission and reachability checks, holding a long-lived
connection, reconnect, polling, un-skipping `Switch between connections` (its last step needs the stream list, done in
`M2-05`), the topology screen, new dependencies.

## Acceptance
- BDD (new, written by the architect in this brief's commit; the test developer writes the step definitions first):
  - `tests/bdd/features/02_permission_check.feature` / `The latest check wins when two checks of one connection overlap` (tags: @fake-broker)
  - `tests/bdd/features/02_permission_check.feature` / `Switching connection shows the permission state of the new current connection` (tags: @fake-broker)
- BDD that must stay green: all existing `@fake-broker` scenarios in `01` and `02` (sidebar pill, edit panel wording).
- Unit (no Gherkin, add a row to the "Unit-level coverage" table in `tests/bdd/README.md`): `ConnectionSessionTest` gets
  tests with a gated checker (no sleeps): older check finishing last does not overwrite; older check finishing first is
  then replaced; a stale check does not make an un-opened connection usable after `forget`; `current` follows select and
  delete.
- UI (`ui` jvmTest): drawer card shows the session's current connection, not the edit-panel selection; selecting another
  profile in the panel without connecting does not change the card.
- Extra check: `./gradlew test --rerun-tasks` green in all modules; no new dependency; no secret in any state object.

## Design notes
- Modules: `core` (session, view model), `ui` (drawer card), `desktopApp` (composition root already builds the session;
  `CompositionRootTest` stays green). No `data-rabbit` change.
- The session keeps results and the current name only. It does NOT hold a broker connection in this task (see open point 1).
- Keep the token logic in one place. Suggest moving the token map from `ConnectionsViewModel` into the session
  (`open` returns `null`/a "superseded" result, or the caller reads the latest result back) rather than duplicating it;
  say which in the Status.
- State in, events out: the UI reads the session flow; no Compose type in `core`.
- The fake checker and fake secret store used in `ConnectionSessionTest` are reused by the acceptance steps.

## UX
`docs/ux/Streams.dc.html` and `Main.dc.html` show the drawer Connection card: label "Connection", name, pill, host. Only
that card changes behaviour here; no new screen. Empty state: "No connection selected". Locked state: red pill as today.

## Open points
All need the product owner before the tasks that depend on them (not before this one, which is deliberately neutral):
1. What "connected" means and how a connection is held (see the M2 proposal below). This task keeps the M1 meaning: a
   connection is "usable" once the permission check has finished; no socket is held.
2. Whether the password stays in memory for the life of the current connection (needed by M2-03 for every management
   call) or is read from the keychain per request. This task keeps today's behaviour (read per check, never stored in the
   session).
3. `Switch between connections` is un-skipped in `M2-05`, not here.

## Definition of done
Tests written first (red recorded) and passing, no unrelated changes, scenarios in `02` pass, README unit table row
added, spec Risks entry for last-write-wins closed (architect moves it) once merged.

## Status
- State: briefed 2026-11-04 by the architect, awaiting product owner confirmation and the team loop (step 1 onward).
- Step 2 (tests first, test developer, 2026-11-04): red. Written: `ConnectionSessionTest` (latest-started-wins in both release
  orders, names independent, forget/rename during a check, `current` follows select/forget/check; replaces the M1-09 test
  that pinned last-finished-wins), `ConnectionsViewModelSessionTest` (new), `GatedPermissionChecker` (core test fake),
  acceptance steps for the two new `02` scenarios (`PermissionCheckSteps`, `PermissionFakes`), UI tests in
  `ConnectionsScreenUiTest` (drawer card follows the session; four M1-12a stand-in drawer tests rewritten, two icon tests
  adjusted to click Connect first), README unit row. Core and acceptance do not compile (missing members); 9 UI tests fail.
  Contract the developer must follow: see the test developer's step-2 report (names below).
  - `data class CurrentConnection(val name: String, val status: PermissionCheckStatus?)` in core (null status = not checked;
    reuses `PermissionCheckStatus` Checking/Done).
  - `ConnectionSession`: `val current: StateFlow<CurrentConnection?>`, `val statuses: StateFlow<Map<String, PermissionCheckStatus>>`
    (Checking from the start of `open`, Done at the end), `fun select(name: String)` (no check, no lookup), `open` keeps its
    signature and returns its own result; `forget(name)` also invalidates a running check for that name and clears `current`
    when it is that name. Token logic lives in the session.
  - `ConnectionsViewModel`: `suspend fun connect(name: String)` (unknown name: no-op; else `session.select` then the check);
    `ConnectionsState.currentConnection: CurrentConnection?` and `permissionChecks` both mirrored from the session (must be
    readable synchronously from `state.value`).
  - UI: button tagged `connectButton`, text "Connect", in the edit panel of a saved profile only; calls `viewModel.connect`.
  - Brief gaps: names above; rename of the current profile clears `current` (user reconnects), to be confirmed; the existing
    M1-12a drawer tests asserted the stand-in, so "unchanged" cannot hold for them.
