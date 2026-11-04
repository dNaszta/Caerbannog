# Permission check hardening: no exception leaves the check

Milestone: M1    Depends on: M1-03-permission-check    Size: S    Must be done before: M1-07-permission-state-ui

## Goal
`ManagementApiPermissionChecker.check` and `ConnectionSession.open` never throw for any failure of the check: every
failure becomes an `Unreadable` result, the connection ends up in safest mode, and `ConnectionsViewModel.saveProfileChanges`
still returns its `SaveConnectionResult` when the check fails.

## Why
Spec "Broker-read permission"; brief M1-03 Design notes ("typed results at the boundary"). M1-03 review findings 1, 2, 3.
Nothing defaults to unlocked today (a thrown check leaves the connection not usable), but M1-07 wires this to the UI and
an uncaught exception there would lose the save result or crash the screen.

## Scope
In:
- Adapter: an invalid host, port or vhost (URL cannot be built; `IllegalArgumentException`) becomes `Unreadable(UNREACHABLE)`
  with a safe detail (exception class only). `java.net.http.HttpTimeoutException` (the parent of the connect timeout) becomes
  `TIMEOUT`, not `UNREACHABLE`. Still no broad catch: name the exceptions. `CancellationException` is never caught.
- Session: when `checker.check` throws anything other than `CancellationException`, store
  `Unreadable(BAD_RESPONSE, detail = exception class name)` and apply the declaration rule as for any `Unreadable`.
  Decide with the senior developer whether a new cause `CHECK_FAILED` is clearer than reusing `BAD_RESPONSE`; if added, update
  `userMessage` (must still start "Permissions could not be read") and the Final contract in the M1-03 brief.
- `saveProfileChanges`: a failing check never hides the `Saved` result.
- Session map: make it safe for concurrent `open` calls from different coroutines (for example `Mutex` or a
  `MutableStateFlow<Map>`); state the chosen rule in a comment (last finished check wins per name).
Out: any UI; retries; caching.

## Acceptance
- Unit (core): fake checker that throws `IllegalStateException`: `open` returns `Unreadable`, `isUsable` true, `topologyUnlocked`
  false; with a declaration the result is `DeclaredByUser`; `CancellationException` propagates. `saveProfileChanges` with a
  throwing checker returns `Saved`.
- Unit or `@broker` (data-rabbit): profile with an invalid host gives `Unreadable(UNREACHABLE)`; a server that never answers
  gives `TIMEOUT` (use a local TLS socket that accepts and stays silent, no sleeps in the test beyond the request timeout setting).
- Existing scenarios in `tests/bdd/features/02_permission_check.feature` stay green. No new scenario needed.

## Design notes
Modules: `core` (session), `data-rabbit` (adapter). No password in `detail`.

## Open points
None.

## Definition of done
Tests first and failing for the right reason, no unrelated changes, M1-03 brief Final contract updated if a cause is added.
