# Two test-side hazards found in M2-14c: flaky TIMEOUT test and a hanging cause-loop test

Milestone: M2    Depends on: none (independent of M2-14c; may land after its merge)    Size: S

## Goal
(a) `PermissionCheckerFailureTest` "a server that accepts and never answers is Unreadable TIMEOUT" no longer fails when the machine is loaded. (b) `ManagementHttpClassificationTest` cause-loop test fails cleanly (instead of hanging the build) if the depth limit in `ManagementHttp.classify` is ever removed.

## Why
Found by the M2-14c step 4 verify (brief `docs/tasks/M2-14c-connect-hint-tls.md`, Status). (a) is pre-existing on main: the test sets one 300 ms value for both Ktor timeouts, so a slow loopback connect raises `ConnectTimeoutException` (an `IOException`), which `ManagementApiPermissionChecker` maps to `UNREACHABLE` instead of `TIMEOUT`. It fails when tasks run in parallel in one `verifyTestDiscovery` or full run and erodes trust in the guard. (b) `withTimeout` cannot interrupt a busy loop, so the mutation "no depth limit" hangs until an outer timeout kills the run.

## Scope
In:
- (a) Test-side fix, no product change: give the test a connect timeout far above the request timeout (for example connect 5 s, request 300 ms), so the only way to get a timeout is the server that never answers. Run it 10 times under CPU load before and after.
- (b) Run the cause-loop and deep-chain cases so a hang becomes a failure: a worker thread with `join(timeoutMillis)` and an assertion that it finished (or JUnit `assertTimeoutPreemptively`). Check by mutation (remove the depth limit): the test must fail within seconds.
Out:
- Mapping the Ktor connect timeout to `TIMEOUT` in `ManagementApiPermissionChecker`. That is a product call (is a host that drops SYNs "timed out" or "unreachable"?) and the architect recommends leaving it as is: today "unreachable" is the truthful, safe text for a connect that did not complete. If the owner wants it, open a separate task with a scenario first.
- Any change in `ManagementApiPermissionChecker` or `ManagementHttp`.

## Acceptance
- Unit tests only (no scenario): the two tests above, plus the mutation check for (b) written in the Status.
- `verifyTestDiscovery` green three times in a row.

## Design notes
Test code in `data-rabbit/src/test`. No sleeps added to wait for results; the timeouts are configuration of the fixture.

## UX
None.

## Open points
None.

## Definition of done
Tests green under load, mutation for (b) fails fast, no production file changed, README row added.
