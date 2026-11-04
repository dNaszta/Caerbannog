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
- Core contract change: `UnreadableCause` gains `UNEXPECTED` (last entry). Reusing `BAD_RESPONSE` is rejected: its
  `userMessage` blames the management API's answer, which is wrong when the app itself failed. `userMessage` for `UNEXPECTED`:
  "Permissions could not be read: an unexpected error occurred while checking." plus the common suffix (topology locked,
  publishing disabled, streams browsable). Core-owned text, no exception message, no secrets. The declaration rule applies to it
  like to every cause except `NO_PASSWORD` (spec "Declared permissions" already says "any cause", so no spec change).
- Adapter (`ManagementApiPermissionChecker`, data-rabbit): no exception leaves `check` except `CancellationException`.
  - Invalid host, port or vhost (URL cannot be built) gives `Unreadable(UNREACHABLE)`, detail = exception class simple name only.
    The type that arrives is NOT yet verified: Ktor 3.0.3 may raise its own `URLParserException` (an `IllegalStateException`) as
    well as `IllegalArgumentException` from the JDK. The invalid-URL test must show which; catch exactly those named types.
  - Timeouts map to `TIMEOUT` and are caught before `SSLException` and `IOException`: add `java.net.http.HttpTimeoutException`
    (parent of `HttpConnectTimeoutException`, replace that catch) and, if the test shows they arrive, Ktor's
    `io.ktor.client.network.sockets.ConnectTimeoutException` / `SocketTimeoutException`. Today `HttpRequestTimeoutException`
    already maps to `TIMEOUT`, so the silent-server test may already be green; that is allowed, record which exception type it saw.
  - The session catch below is the backstop; the adapter names what it knows and does not need to be exhaustive.
- Session (`ConnectionSession`, common code): a throw from `checker.check` other than `CancellationException` is stored as
  `Unreadable(UNEXPECTED, detail = e::class.simpleName)` (no `javaClass` in common code; class name only, never `e.message`), then
  the declaration rule applies. This is the one documented broad catch, at the boundary, and must say so in a comment.
  A throw from `secretStore.retrieve` (keychain failure) is treated the same way but the declaration is NOT applied, for the
  same reason as `NO_PASSWORD`: without a password nothing can connect. Recommended, see Open points.
- `saveProfileChanges`: needs no try/catch of its own once the session never throws; it must still return `Saved`.
- Session map: replace `mutableMapOf` with `MutableStateFlow<Map<String, PermissionCheckResult>>` updated with `update { }`
  (atomic, lock-free, `isUsable`/`permissions` stay non-suspend and read `.value`; `kotlinx-coroutines-core` is already a core
  dependency; no JVM-only `ConcurrentHashMap` in common code). A `Mutex` does not fit: the readers are not suspending. Rule, in a
  comment: the check runs outside any lock; the last check to finish wins per name. Out of scope: cancelling a superseded check.
Out: any UI; retries; caching; cancelling or de-duplicating overlapping checks; a persisted result.

## Acceptance
Each point is proven by exactly these tests (names are the contract; the test developer may refine wording, not coverage).

Core, `core/src/jvmTest/.../ConnectionSessionTest.kt` (fake checker that throws, `runBlocking`):
- `a throwing checker leaves the connection usable and Unreadable UNEXPECTED`: `IllegalStateException` thrown; `open` returns
  `Unreadable(UNEXPECTED, "IllegalStateException")`, `isUsable` true, `topologyUnlocked` and `publishingEnabled` false.
- `a throwing checker with a declaration gives DeclaredByUser carrying the UNEXPECTED reason`.
- `the exception message is not kept in the result`: thrown message contains the stored password; assert it appears nowhere in
  `detail` or `userMessage`.
- `a cancelled check propagates the cancellation and leaves the connection not usable`: checker throws `CancellationException`.
- `a failing secret store is Unreadable UNEXPECTED even with a declaration` (only if the open point is accepted).
- `overlapping opens of different profiles all end usable with their own result`: many coroutines on `Dispatchers.Default`, fake
  checker returning a per-name result.
- `for the same profile the check that finishes last wins`: two `open` calls gated with `CompletableDeferred`, released in the
  reverse order of start; no sleeps.
- `PermissionDecisionTest` already loops `UnreadableCause.values()` for the "Permissions could not be read" prefix: it covers
  `UNEXPECTED` with no edit; add one assertion that `UNEXPECTED`'s text does not mention the management API's answer.

Core, `ConnectionsViewModelPermissionTest.kt`:
- `a throwing checker does not hide the Saved result`: real `ConnectionSession` with a throwing checker, `saveProfileChanges`
  returns `Saved`, `session.permissions(name)` is `Unreadable(UNEXPECTED)`.

Data-rabbit, new plain unit class `data-rabbit/src/test/.../PermissionCheckerFailureTest.kt`, NOT tagged `@broker`, so it runs in the
default `test` task with no Docker:
- `an invalid host is Unreadable UNREACHABLE, not an exception` (for example a host with a space), and the same for an
  out-of-range port and a vhost/username that cannot form a URL if the first run shows that is reachable. Detail has no password.
- `a server that accepts and never answers is Unreadable TIMEOUT`: local `ServerSocket(0)` that accepts and stays silent,
  checker built with `ManagementApiPermissionChecker(connectTimeoutMillis = 300)` (the parameter drives both the connect and the
  request timeout). Closing the socket in the test ends it; no `sleep`.
- A blackhole or non-routable address is NOT used: result depends on the network (a router may answer "unreachable" at once), so it
  would be flaky. `HttpConnectTimeoutException` is covered by the type-based catch only; record that in the Status.
- Existing `RealBrokerPermissionCheckTest` unchanged and green (`:data-rabbit:brokerTest`).

Gherkin: no new scenario. Behaviour visible to users (a failed check means safest mode, declaration applies for any cause but a
missing password) is already in `tests/bdd/features/02_permission_check.feature`; the new cause is an internal wording difference,
and the unit tests above pin it. `02_permission_check.feature` and the acceptance steps stay green and unchanged
(`FakePermissionChecker.refusal` takes an `UnreadableCause`; nothing switches exhaustively on the enum outside `userMessage`).

Red first: before the build step, the core tests fail to compile (no `UNEXPECTED`) or fail on a thrown exception; the invalid-host
test fails on the exception; the silent-server test may be green already (characterisation).

## Design notes
Modules: `core` (session, enum, userMessage) and `data-rabbit` (adapter). No password in `detail`. `core` stays free of
data-rabbit and of JVM-only types. No new dependency. Everything is fake-checker based in core; data-rabbit tests use a local
socket, no real broker.

Contract the test developer follows (core, `dev.caerbannog.core.connections`):
```kotlin
enum class UnreadableCause { NOT_AUTHORIZED, NOT_FOUND, TIMEOUT, UNREACHABLE, BAD_RESPONSE, NO_PASSWORD, UNEXPECTED }
// open/reopen: never throw, except CancellationException. Signatures of ConnectionSession are unchanged.
```

## Open points
- No product decision needed.
- Recommended, for the senior developer to confirm: a `SecretStore.retrieve` exception is handled like a checker exception, without
  applying the declaration. Not in the original review findings; included because `open` is documented as never throwing.
  If rejected, drop that test and say so in the Status.
- Unverified until the failure tests run: which exception types Ktor 3.0.3 and the Java engine raise for a bad URL and for a
  connect timeout. Record them in the Status; do not assume.

## Definition of done
Tests first and failing for the right reason, no unrelated changes, M1-03 brief Final contract updated if a cause is added.

## Status
- Round: 0, step 1 done (architect): brief confirmed and sharpened against `main`. Next: step 2 tests (test developer).
- Branch: `m1-09-permission-check-hardening`, based on `main`. Not pushed.
- Must land before `M1-07-permission-state-ui`.
