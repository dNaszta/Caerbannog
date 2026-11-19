# Move the broker tests to Testcontainers 2.x

Milestone: M2 (optional, any time)    Depends on: none    Size: S

Branch: `M2-10-testcontainers-2x` from `main`. Needs Docker. Product owner approved the dependency bump on 2026-11-19.

## Goal
`data-rabbit` broker tests run on Testcontainers 2.x with no deprecation warnings from our own code and without the `api.version` pin, all `@broker` tests unchanged in behaviour.

## Why
`docs/spec.md` Risks (Testcontainers deprecation, Docker API pin). M2-02 spike answer 6: on 1.21.3 the warning is the compile-time deprecation of `withPluginsEnabled`; a trial of 2.0.5 passed the existing tests and made the pin unnecessary, but 2.x deprecates `org.testcontainers.containers.RabbitMQContainer` too, so the fix is a migration, not a bump. Low value: do it only when 1.x stops working with a new Docker Desktop or when the product owner wants a warning-free build.

## Scope
In: rename artifacts (`testcontainers-junit-jupiter`, `testcontainers-rabbitmq`, BOM `testcontainers-bom` 2.x; use a version older than two weeks at the time); move to `org.testcontainers.rabbitmq.RabbitMQContainer` (no `withPluginsEnabled`; it has `withAdminUser`, `withAdminPassword`, `withRabbitMQConfig*`); enable plugins by mounting an `enabled_plugins` file with `withCopyFileToContainer` (this also removes the deprecated method); try removing the `api.version` pin in the `brokerTest` task and keep it if the Docker engine still needs it; share one container-factory function in test code instead of the copy in each test class (`PermissionsEndpointSpikeTest`, `RabbitMqContainerSpikeTest`, `RealBrokerPermissionCheckTest`, `RealBrokerReachabilityTest`, `StreamManagementApiSpikeTest`) if it stays small.
Out: any production code; changing assertions of existing tests, except deleting the spike test `Q6 RabbitMQContainer withPluginsEnabled is annotated Deprecated...` in `StreamManagementApiSpikeTest` (it asserts the 1.x annotation and the class it reflects on moves).

## Acceptance
- `./gradlew :data-rabbit:brokerTest --rerun-tasks` green twice (same test count as before the change, 25 or more at the time of writing). **Amended at step 5 (2026-11-19):** the whole task no longer fits its 40 minute timeout (about 326 tests, about 80 minutes; same on main, see `M2-18`), so the accepted evidence is the chunked run in step 4 (all 55 classes, every test green after the S-F1d test fix and the Q3 re-run) plus the M5, M7, M8 and M9 gates.
- Compile output of `:data-rabbit:compileTestKotlin` has no `withPluginsEnabled` or `RabbitMQContainer` deprecation warning.
- The two stream plugins are enabled and the TLS conf still applies (the spike's Q7 tests prove it).
- `docs/spec.md` Risks entries (Testcontainers deprecation, API version pin) updated with the result.

## Design notes
Test source set only. The container for "plugin missing" (Q7) still starts with only `rabbitmq_stream` in its `enabled_plugins` file.

## Open points
- Whether 2.x keeps needing the `api.version` pin on the Docker Desktop in use (the trial said no).
- 1.21.4 patch bump was not tried; it is not a fix for the warning.

## Definition of done
Tests green twice, no unrelated changes, spec Risks updated, task index row updated.

## Status
Step 3 (senior developer, 2026-11-19): built, ready for test. Test source set and `data-rabbit/build.gradle.kts` only; no production code.
- Dependency: Testcontainers BOM 2.0.5 (released 2026-04-20, the newest release older than two weeks; MIT), artifacts `testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-rabbitmq`. Only `data-rabbit` uses Testcontainers (mcp-server, acceptance-tests, dev-seed do not). `THIRD_PARTY_NOTICES.md` updated.
- Code: `org.testcontainers.rabbitmq.RabbitMQContainer` in all 59 usages (import swap). The one shared factory already existed (`RabbitContainers.kt`, `brokerWithPlugins`); it now copies an `enabled_plugins` file (`[rabbitmq_management,<plugins>].`) with `withCopyToContainer(Transferable.of(...))` instead of running `rabbitmq-plugins enable` after start; the file lists `rabbitmq_management` because it replaces the image's own. Q7 "plugin missing" container still gets only `rabbitmq_stream` (plus management). Spike test Q6 (asserted the 1.x `@Deprecated`) deleted as the brief says.
- `api.version` pin: removed from `brokerTest`; the tests start on Docker Desktop 29.8.2 without it. Podman not re-run.
- `:data-rabbit:compileTestKotlin --rerun-tasks`: no Testcontainers warning.
- Finding: the full `brokerTest` suite is now about 326 tests (the brief said 25) and needs more than the 40 minute task timeout on this machine, on main too (baseline on main: timed out at 40m23s after 295 tests, with `StreamClientSpikeTest` and `StreamManagementApiSpikeTest` not reached). Also on main and after: `RealBrokerM9SpikePolicyKeysTest` "S-F1d" fails (a spike assertion about broker 4.x retention-by-policy behaviour, floating image `rabbitmq:4-management`; unrelated to this task). Follow-up brief candidate: split `brokerTest` or raise its timeout; look at S-F1d.
Verification (senior developer, 2026-11-19): two full `./gradlew :data-rabbit:brokerTest --rerun-tasks` runs on the 2.0.5 tree, both identical: 298 tests in 53 classes, 1 failed (S-F1d above, same as on main), then the task timeout of 40 minutes stopped the run (40m17s and 40m15s) before `StreamClientSpikeTest` and `StreamManagementApiSpikeTest`; those two (14 + 14 tests) were run separately and are green (2m18s). Total about 326 tests, so not "green twice" by the letter of the acceptance, for the two reasons above that also hold on main. The dev broker was up for `RealBrokerM5GateTest` and stopped afterwards.

Step 4 (test developer, 2026-11-19): verified in three chunks, because the whole suite does not fit the 40 minute task timeout on this machine (also on main). Branch tip d8cb5310, Docker Desktop, `rabbitmq:4-management` (floating, local image id 753099a1...), all runs `--rerun-tasks` for chunks 1 to 3.
- Chunk 1, `*RealBrokerM8*` (14 classes: Access, Gate, LiveSource, MigrationRequests, Shovel, SpikeAmqpsShovel, SpikeCluster, SpikeDefinitions, SpikeDelivery, SpikeGuestLoopback, SpikeInMemoryLimit, SpikeShovel, SpikeUpgradeFollowUp, SpikeUpgrade): 72 tests, 0 failed, 12m40s. M8 gate green.
- Chunk 2, `*RealBrokerM9*` (15 classes): 105 tests, 1 failed (S-F1d), 17m36s. M9 gate green (1 test, 29.6 s).
- Chunk 3, the other 26 data-rabbit classes (5 spike, M5 gate and migration, M7 gate, Catalog, Clients, MessageBrowser, Mqtt*, PayloadViewer, PermissionCheck, Plain, PlanCheck, Planner*, Reachability, StatsMatch, Stream*, SuperStream, Topology; dev broker up for M5 and Mqtt): 149 tests, 1 failed (`StreamManagementApiSpikeTest` Q3 first_timestamp window), 39m22s. M5 gate (5 tests) and M7 gate (4 tests) green.
- Together 326 tests in 55 data-rabbit classes (the 55 data-rabbit files with `Tag("broker")`; the two further files in `acceptance-tests` are not run by `data-rabbit:brokerTest` and were not run).
- Q3 failure: not branch related, a timing assertion. The test body took 943 s (normally a few seconds; host load average 10 to 19 on 8 cores, other work on the machine) and the assertion `first_timestamp in (now - 600 s)..(now + 60 s)` then failed. Re-run of the two spike classes: green (Q3 again took 970 s, but passed), 28 tests, 49m24s.
- S-F1d: root cause is a stale test, not the broker and not the image. M9-08 slice 1 (53d65383, 2026-11-17) made the stream row merge `effective_policy_definition` into the retention; the test still asserted that the row shows none. The raw-argument assertions (no `x-max-age`) still hold on the current image. Fixed in the test only (d8cb5310); class `RealBrokerM9SpikePolicyKeysTest` green after the fix (12 tests). CI would have failed on it too (same test, same floating image, ci.yml runs the whole `brokerTest` on main).
- Timeout: `brokerTest` needs about 80 minutes here (12m40s + 17m36s + 39m22s, with load); the slowest classes are `StreamClientSpikeTest` (11 to 21 min under load, 2 min idle), `StreamManagementApiSpikeTest` (up to 29 min under load), `RealBrokerM9SpikeDeadLetterTest` (534 s: S-C3 slow retry 203 s, S-C4 104 s), `RealBrokerM8SpikeUpgradeFollowUpTest` (183 s), `RealBrokerM8SpikeUpgradeTest` (146 s). Recommendation (not changed): split `brokerTest` (for example spikes as their own task) or raise the timeout; follow-up brief.
- Fast suite (`task test`, branch tip d8cb5310): BUILD SUCCESSFUL, 685 test classes reported done, 0 failed, 2h00m under the same machine load.

Step 5 (architect, 2026-11-19): ACCEPTED. Merge pending the owner. Reviewed: the diff to main (64 files: 59 import swaps, `RabbitContainers.kt`, `data-rabbit/build.gradle.kts`, `THIRD_PARTY_NOTICES.md`, spec Risks, brief, README), the test report, `ci.yml` and the root `build.gradle.kts`. Not reviewed: the broker runs themselves (taken from the report), Podman, the two `acceptance-tests` files tagged `broker`.
- Chunked acceptance accepted: the "twice" and the 40 minute limit cannot hold on main either (CI run 44511906155 on main hit the 40m task timeout, `BUILD FAILED in 41m 28s`). Follow-up `M2-18` covers the timeout and split and the Q3 clock point; neither blocks the merge. The CI live-broker job stays red on main until M2-18 is done; that was already so before this branch.
- Test-side only, no production code, no safety rule touched; the S-F1d fix changes an assertion that was stale since M9-08 (53d65383) and keeps the raw-argument assertions.
