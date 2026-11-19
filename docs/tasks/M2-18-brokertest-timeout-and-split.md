# Make `brokerTest` fit its timeout, and stop the Q3 timing assertion failing under load

Milestone: M2 (test infrastructure)    Depends on: M2-10 (merged first)    Size: S to M

## Goal
`./gradlew :data-rabbit:brokerTest` (CI job "Live broker tests" on main) finishes green inside its task timeout, and `StreamManagementApiSpikeTest` Q3 no longer fails because the host was busy.

## Why
Found in M2-10 step 4 and confirmed on CI. The whole `brokerTest` is about 326 tests in 55 classes and takes about 80 minutes on a loaded developer machine (12m40s + 17m36s + 39m22s in three chunks). The root `build.gradle.kts` gives `brokerTest` a 40 minute task timeout. CI run 44511906155 (main, merge of M9-13) shows `Requesting stop of task ':data-rabbit:brokerTest' as it has exceeded its configured timeout of 40m`, `BUILD FAILED in 41m 28s`: so the job is red on main today, with or without M2-10 (the M2-10 baseline on main also stopped at 40m23s). `ci.yml` has no job-level `timeout-minutes` for `broker-tests` (GitHub default 360), so the Gradle task timeout is the only limit. Until this is fixed the live-broker job cannot show a regression after test 295 or so, and `StreamClientSpikeTest` and `StreamManagementApiSpikeTest` are never reached.

## Scope
In:
1. Slowest classes first (measured, idle / loaded): `StreamClientSpikeTest` (2 min / 11 to 21 min), `StreamManagementApiSpikeTest` (up to 29 min under load), `RealBrokerM9SpikeDeadLetterTest` (534 s: S-C3 203 s, S-C4 104 s), `RealBrokerM8SpikeUpgradeFollowUpTest` (183 s), `RealBrokerM8SpikeUpgradeTest` (146 s). Find out why the two stream spike classes take 10 times longer under load (waits for broker statistics windows are the likely cause; bound them by the broker's documented interval, not by guesses).
2. Split by what the test protects. Proposed: keep `brokerTest` for the gates and the product `RealBroker*` classes that are not `Spike` (the regression guard), add `brokerSpikeTest` (tag `spike` or class-name filter, decide in the task) for the `*Spike*` classes that record broker facts. CI runs both as two steps with their own timeouts (like the fast job), so a hang shows which half. `task test:broker` keeps running both. Update `CLAUDE.md` ("`brokerTest`" lines), `Taskfile.yml` and `ci.yml` in the same change.
3. Interim, one line, if the split takes longer: raise the `brokerTest` timeout in `build.gradle.kts` to 90 minutes and set `timeout-minutes: 100` on the `broker-tests` job.
4. Q3 (`StreamManagementApiSpikeTest`, first_timestamp window `(now - 600 s)..(now + 60 s)`): the test body took 943 s under load, so a wall-clock window measured after the wait fails. Take `now` at the moment the message is published (or compare to the broker's own clock from the same response), not at the assertion. Assertion meaning does not change.
Out: any production code; changing what the spike tests assert (except the Q3 clock point); the Podman run (see Open points).

## Acceptance
- BDD: none (test infrastructure). The scenarios behind the `@broker` tests stay as they are.
- `:data-rabbit:brokerTest` and the new spike task each green on CI on main inside their timeout, test count together equal to 326 (or the current count; state it).
- A deliberately failing product `RealBroker*` test turns the first CI step red without waiting for the spike step.
- Q3 passes when a `Thread.sleep`-free load simulation (the test paused for 15 minutes between publish and assert, by a test hook or a clock parameter) is applied: show the red-before, green-after.
- Every `Test` task still logs `TEST START` / `TEST DONE`.

## Design notes
Build and CI files only (`build.gradle.kts`, `data-rabbit/build.gradle.kts`, `Taskfile.yml`, `.github/workflows/ci.yml`, `CLAUDE.md`) plus the one Q3 test. Keep the tag `broker` on both halves so `includeTags("broker")` users are not surprised; the split is by a second tag or class filter. The 40 minute limit for the other slow tasks (`keychainTest`, `screenshotTest`) stays.

## UX
None.

## MCP
None (internal test infrastructure).

## Open points
- Is the CI runner faster than the developer machine? CI timing: the job stopped at 40m on main, so even idle runners need more than 40 minutes for the whole suite.
- Podman (rootless, Fedora) was not run for M2-10; run `brokerTest` there once, drop or restore `api.version` in the task if Podman rejects the 2.x default (spec Risks, "Docker API version pin").
- The two `acceptance-tests` files tagged `broker` are not run by `data-rabbit:brokerTest`; say in the task where they run.

## Definition of done
CI "Live broker tests" green on main in two consecutive runs, Q3 fixed, docs (`CLAUDE.md`, task index) updated, no unrelated changes.
