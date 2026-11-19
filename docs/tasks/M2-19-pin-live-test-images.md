# Pin the live-test broker images to explicit versions

Milestone: M2 (test infrastructure)    Depends on: M2-18 and M8-15 merged first (they touch `ci.yml`, `CLAUDE.md` and the two shovel test classes)    Size: S to M

## Goal
Every live-broker test, the dev brokers and CI start RabbitMQ from an explicit patch tag kept in one place, so a new RabbitMQ release can no longer turn `main` red without a commit; moving to a newer release is a documented, deliberate step, and one non-blocking CI job still tells us early what the floating tag does.

## Why
Found 2026-11-19: `rabbitmq:4-management` moved to 4.4.1, which changed shovel validation, stream segment sizes and a reason text, and four live classes went red with no change in our code (two test fixes already merged, shovel in `M8-15`). Owner decision 2026-11-19 (B): pin the live-test images. The spec already pins what the tests prove ("measured on RabbitMQ 4.4.0 / 4.4.1"); the tags should agree with the facts.

## Verified tags (Docker Hub, 2026-11-19)
Exist: `4.4.1-management` (also `4.4.0-management`, `4.4-management`), `3.13.7-management`, `4.0.9-management`, `4.1.8-management`, `4.2.9-management`, `4.3.8-management` (4.3.7 also exists; the local image of the floating `4.3-management` is 4.3.7). Local floating tags today: `4-management` is 4.4.1, `3.13-management` is 3.13.7.

## Scope
In:
1. One place: `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RabbitContainers.kt` gets `internal object BrokerImages` with `CURRENT_4X = "rabbitmq:4.4.1-management"`, `LEGACY_3X = "rabbitmq:3.13.7-management"`, and the ladder images of the upgrade spikes (`rabbitmq:4.0.9-management`, `4.1.8`, `4.2.9`, `4.3.8` as `UPGRADE_4_0` ... `UPGRADE_4_3`, with the 4.4 step being `CURRENT_4X`). Replace every literal in the test classes and support files (about 60 uses of `rabbitmq:4-management`, 19 of `rabbitmq:3.13-management`, 6 of the ladder: `RabbitContainers.kt`, `M8SpikeSupport.kt`, `M9SpikeCSupport.kt`, `RealBroker*Test`, `StreamClientSpikeTest`, `StreamManagementApiSpikeTest`, the `RealBrokerM8Spike*` classes; the two shovel classes of `M8-15` already use 4.4.1). A fast guard test (`BrokerImagePinTest`, no container) reads the test sources and fails on any `rabbitmq:` literal outside `BrokerImages`, or any tag that is floating (`4-`, `4.4-`, `3.13-`, `latest`, no tag).
2. `dev/broker/docker-compose.yml` and `dev/broker-3x/docker-compose.yml`: explicit tags `docker.io/library/rabbitmq:4.4.1-management` and `...:3.13.7-management`. Owner decision (B) covers the "live-test images"; the dev broker used to float on purpose (`dev/README.md` says so, line about "floating on purpose"). Recommendation: pin the dev brokers too, because `task dev:up` is what the CI job and the two dev-broker gates (`RealBrokerM5GateTest`, `RealBrokerMqttIntegrationTest`) use; the early-warning role moves to the non-blocking CI job (item 4). The README sentence is replaced (item 3).
3. `dev/README.md`: replace the floating-tag paragraph by "Image versions": where the pins live (the three places above), and the **deliberate bump procedure**: (a) pick the new tag, check it exists (`docker manifest inspect docker.io/library/rabbitmq:<tag>` or Docker Hub); (b) change `BrokerImages`, the two compose files and the Risks line this task adds to `docs/spec.md` (item 5); (c) run `./gradlew :data-rabbit:brokerTest` and `brokerSpikeTest` (see `M2-18`) on a quiet machine; (d) fix what is red as its own commit per cause, with the broker's own words in the commit body; (e) record newly measured facts in `docs/spec.md`; (f) one commit "Move the live tests to RabbitMQ x.y.z". Also: how to try a version without a commit (`BROKER_IMAGE` is NOT introduced; edit the constant locally).
4. CI (`.github/workflows/ci.yml`): the blocking jobs use the pinned images through the code (no workflow change needed for Testcontainers). Add one non-blocking job `Live broker tests on the floating 4.x image` (`continue-on-error: true`, `workflow_dispatch` plus a weekly `schedule`, not on every push, own `timeout-minutes`) that runs only the regression half (`brokerTest`, see `M2-18`) with `-PbrokerImage=rabbitmq:4-management` read by `BrokerImages.CURRENT_4X` through a system property `caerbannog.brokerImage` (set by the Gradle task only when the property is given; default stays the pinned tag; the 3.x and ladder images are not overridable). It shows a red mark and a report artifact; nobody is blocked. Not wired to `push`.
5. `docs/spec.md`: the Risks line "Docker API version pin" neighbour gets "Live-test images are pinned (`BrokerImages`); bump procedure in `dev/README.md`"; the T1-style decision table gets a row "Pin live-test images" (decided 2026-11-19, owner).
Out: pinning by digest (a tag is enough; document that a tag can be re-pushed), changing what any test asserts, the Testcontainers version, Podman, MCP.

## Acceptance
- BDD: none (test infrastructure).
- `BrokerImagePinTest` green and shown red on a branch where one literal is put back; `./gradlew :data-rabbit:brokerTest` and `brokerSpikeTest` green on the pinned images (same count as before, state it); `task dev:up` and `dev:3x:up` start the pinned tags (`docker compose ... config | grep image`); the floating job runs once by hand (`workflow_dispatch`) and its result is recorded in the task report (a red result there does not fail the task).
- `RealBrokerM8GateTest` and `RealBrokerM7GateTest` green.

## Design notes
Test code, two compose files, `ci.yml`, `data-rabbit/build.gradle.kts` (the one system property pass-through in `brokerTest`), `dev/README.md`, `docs/spec.md`. No production code. `BrokerImages.CURRENT_4X` reads `System.getProperty("caerbannog.brokerImage")` once; nothing else may read it. Keep tag `broker` on all classes.

## UX
None.

## MCP
None (internal test infrastructure).

## Open points
- Does the dev broker pin need an owner word? Decision (B) says "live-test images"; the dev compose file is the CI job's dev broker and a hand-trial tool. Recommendation: pin (above). If the owner wants the dev broker to float, drop item 2 and keep the CI job.
- Which patch to move to next is not decided here; the procedure is.

## Definition of done
Tests first (the guard test red before the sweep), no unrelated change, `dev/README.md` and spec updated, floating job run once.
