# Make `brokerTest` fit its timeout: split it into a regression half and a spike half, and fix the Q3 clock point

Milestone: M2 (test infrastructure)    Depends on: M2-10 (merged)    Size: M    Runs in parallel with `M8-15`; before `M2-19`

## Goal
CI "Live broker tests" on main is green inside its limits as two Gradle tasks with their own CI steps: `brokerTest` (gates and product `RealBroker*` classes, the regression guard) and `brokerSpikeTest` (the classes that record broker facts); `StreamManagementApiSpikeTest` Q3 no longer fails because the host was busy; each half has a task timeout and a job timeout that were measured, not guessed.

## Why
Found in M2-10 step 4 and confirmed on CI. The whole live suite is about 326 tests in 55 classes (24 of them spike classes) and took about 80 minutes on a loaded developer machine (12m40s + 17m36s + 39m22s in three chunks); the root `build.gradle.kts` gives `brokerTest` a 40 minute task timeout. CI run 44511906155 (main) shows `exceeded its configured timeout of 40m`, `BUILD FAILED in 41m 28s`, so the job is red on main with or without M2-10. `ci.yml` has no `timeout-minutes` on `broker-tests` (GitHub default 360). Until this is fixed a regression after about test 295 cannot show, and `StreamClientSpikeTest` and `StreamManagementApiSpikeTest` are never reached. Since 2026-11-19 there is also a second cost: a red spike class hides the verdict of the product classes in one long log.

## Checked on 2026-11-19 (architect)
- Q3 is NOT fixed: `StreamManagementApiSpikeTest` line 579 still takes `now = System.currentTimeMillis()` after `awaitMessages` and asserts `first_timestamp in (now - 600_000)..(now + 60_000)`.
- Of the `acceptance-tests` files that mention `@broker` (`connections/Fakes.kt`, `connections/RunConnectionsAcceptanceTest.kt`, and the `Run*AcceptanceTest` KDoc of clients, topology, migrations), none runs a `@broker` scenario: every suite filters `not @broker`. The real-broker proof of those scenarios is the data-rabbit class named in each KDoc. Nothing in `acceptance-tests` needs a live broker, so the split does not touch that module; say it in the task report and in the `CLAUDE.md` line.
- The Podman block of `brokerTest` (engine choice, `DOCKER_HOST`, Ryuk) lives inline in `data-rabbit/build.gradle.kts` lines 95 to 130. Podman was not run for M2-10.
- 55 files carry `@Tag("broker")`; 24 of them are spike classes by name (`*Spike*`, including the `RealBrokerM8Spike*` and `RealBrokerM9Spike*` families and the two stream spike classes).

## Scope
In (one task, this order, one commit per numbered step):
1. **Q3 clock point.** In the Q3 test take `val publishedAt = System.currentTimeMillis()` immediately before `publishViaManagement(name, 1000)` (the first message is published after it) and assert `first_timestamp in (publishedAt - 5_000)..(publishedAt + 600_000)`: the first message is published after `publishedAt`, and at most the publishing time later (generous, bounded by the 600 s the test may take). Assertion meaning is unchanged. Red-before, green-after without a sleep: add a clock parameter or a test hook (an `internal var clock: () -> Long` on the test class, default `System::currentTimeMillis`) and one fast test of the window helper with a clock that is 15 minutes later at assert time; show it red against the old window and green against the new one.
2. **Find the stream spikes' load sensitivity.** Look at the waits in `StreamClientSpikeTest` (2 min idle, 11 to 21 loaded) and `StreamManagementApiSpikeTest` (up to 29 loaded). If a wait is a fixed poll for a statistics window, bound it by the broker's documented interval (management statistics `collect_statistics_interval`, default 5 s; stream stats refresh) with a clear failure message, never `Thread.sleep`. If the cost is the 1,000-message publishes and the 500-queue setup, say so and leave it: the split makes it harmless. Do not change what any spike asserts.
3. **The split.**
   - Tag: spike classes get a second tag `spike` next to `broker` (`@Tag("broker") @Tag("spike")`); rule: every class that carries `broker` and has `Spike` in its simple name carries `spike`, and no other class does. A fast guard test `BrokerTestSplitGuardTest` (data-rabbit, no container, reads the compiled test classes by reflection or the sources; follow how `verifyTestDiscovery` is built at the root) enforces both directions, so a new spike class cannot silently land in the regression half.
   - `brokerTest`: `includeTags("broker")` and `excludeTags("spike")` (JUnit tag expression `broker & !spike`). The gates (`RealBrokerM7GateTest`, `RealBrokerM8GateTest`, M9 gate) and all non-spike `RealBroker*` classes stay here.
   - `brokerSpikeTest`: new task, tag expression `broker & spike`. Description says what it protects (recorded broker facts, not product behaviour).
   - One shared function in `data-rabbit/build.gradle.kts` (`fun Test.configureLiveBroker()`) holds the Podman and Docker engine selection and is called by both tasks; no copy-paste of the block. The root `build.gradle.kts` `slowTaskNames` gets `brokerSpikeTest`; both keep the `TEST START` / `TEST DONE` / `SLOW TEST` logging.
   - Taskfile: `test:broker` runs both (`./gradlew :data-rabbit:brokerTest :data-rabbit:brokerSpikeTest --continue`); add `test:broker:spikes` and keep `test:broker:podman` and `test:broker:docker` running both with `-PcontainerEngine=...`.
   - `ci.yml` job `broker-tests`: two steps, `Run regression broker tests` (`./gradlew :data-rabbit:brokerTest`, own `timeout-minutes`) then `Run spike broker tests` (`./gradlew :data-rabbit:brokerSpikeTest`, `if: always()` so a red first half still runs the second and shows both verdicts, own `timeout-minutes`); job-level `timeout-minutes` set to the sum plus 10; report upload on failure unchanged.
   - `CLAUDE.md`: the "`brokerTest`" lines (Commands: one line for `brokerSpikeTest`; Testing: the timeout sentence names both tasks and the measured limits). Task README and `docs/spec.md` mentions of `brokerTest` as the whole suite are checked (`grep -n brokerTest`).
4. **Timeouts, measured.** After the split run each half once on an idle machine and once on the CI runner (a `workflow_dispatch` run) and record the times in the task report. Set the task timeout to measured CI time times 1.5, rounded up to 5 minutes, for each half; the step `timeout-minutes` equal to that; the job timeout as above. Do not set the 90-minute interim value except as a throwaway first commit if the split is not ready (then remove it in step 3).
5. **Podman once.** Run `./gradlew :data-rabbit:brokerTest -PcontainerEngine=podman` on Fedora (owner's machine; the developer agent cannot, so if it cannot run it, the report says "Podman NOT RUN" and the open point stays). If Podman rejects the Testcontainers 2.x API default, add `systemProperty("api.version", ...)` back inside `configureLiveBroker()` only for the Podman branch (spec Risks, "Docker API version pin").
Out: production code; changing what spike tests assert (except the Q3 clock point); image pinning (`M2-19`); the shovel rule (`M8-15`); a third task or a per-milestone split.

## Acceptance
- BDD: none (test infrastructure); the scenarios behind the `@broker` tests stay as they are.
- `:data-rabbit:brokerTest` and `:data-rabbit:brokerSpikeTest` each green on CI on main inside their limits; the two test counts add up to the count before the split (state both numbers and the total; 326 on 2026-11-18, plus the tests added since).
- A deliberately failing product `RealBroker*` test turns the first CI step red and the second step still runs (show it on a branch with `workflow_dispatch`, then drop the failing test).
- `BrokerTestSplitGuardTest` red when a spike class lacks the `spike` tag and when a non-spike class has it.
- Q3: red-before, green-after as in step 1.
- Every `Test` task still logs `TEST START` / `TEST DONE`; `keychainTest` and `screenshotTest` keep 40 minutes.

## Design notes
Files: `build.gradle.kts` (root, one set entry), `data-rabbit/build.gradle.kts`, `Taskfile.yml`, `.github/workflows/ci.yml`, `CLAUDE.md`, the tag line on the 24 spike classes, `StreamManagementApiSpikeTest` (Q3 and the waits), the new guard test. Keep tag `broker` on both halves so `includeTags("broker")` users are not surprised. Conflicts to expect: `M8-15` edits `RealBrokerM8SpikeShovelTest` and `RealBrokerM8ShovelTest` (this task only adds a tag line to the spike one: trivial merge); `M2-19` edits `ci.yml`, `CLAUDE.md` and the same test files after this task, so it starts after this one merges.

## UX
None.

## MCP
None (internal test infrastructure).

## Open points
- Is the CI runner faster than the developer machine? Unknown until step 4 measures it; the 40-minute stop on main says even idle runners need more than 40 minutes for the whole suite.
- Podman (rootless, Fedora) not run for M2-10: step 5, owner's machine.

## Definition of done
CI "Live broker tests" green on main in two consecutive runs (both steps), Q3 fixed, docs (`CLAUDE.md`, task index) updated, report states the measured times and the two counts, no unrelated changes.
