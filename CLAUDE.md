# Caerbannog

"Behind the rabbit": a free desktop (JVM) app for browsing RabbitMQ Streams and inspecting quorum-queue topology, built with Kotlin Multiplatform and Compose Multiplatform. MIT licensed. `docs/spec.md` is the source of truth for scope, architecture and product decisions; read the relevant section before changing behaviour. Task briefs live in `docs/tasks/`, UX mockups in `docs/ux/`, BDD features in `tests/bdd/features/`.

## Modules

| Module | Holds | Rule |
| --- | --- | --- |
| `core` | domain models, view models, interfaces, pure formatting (e.g. `BodyPreview`) | No RabbitMQ, Paho or `java.*` imports. Talks to the broker only through interfaces. |
| `data-rabbit` | stream client, management API client, MQTT publisher, profile and keychain storage | Implements the `core` interfaces. The only module that knows RabbitMQ and Paho. |
| `ui` | Compose screens and components | Single `jvm()` target today, but keep `commonMain` free of `java.*` where you can; the one exception is clipboard code in `ui/components/Clipboard.kt`. |
| `desktopApp` | `main()`, composition root, adapters, packaging | Wires `core` interfaces to `data-rabbit`. |
| `mcp-server` | headless MCP server over stdio | New product capabilities need an MCP decision (expose it, or record why not). |
| `core-testing`, `acceptance-tests`, `dev-seed` | fakes, Cucumber step definitions, dev data seeding | |

## Commands

Prefer the `task` wrappers (`task --list`); Fedora installs it as `go-task`.

- `./gradlew :<module>:jvmTest` (`:data-rabbit:test`, `:mcp-server:test`): the fast, no-container suite per module. `task test` runs all.
- `./gradlew :data-rabbit:brokerTest --tests '*Name*'`: live-broker regression half (gates and product `RealBroker*` classes; tag `broker`, not `spike`; Testcontainers, needs Docker or rootless Podman). They are excluded from `test` on purpose.
- `./gradlew :data-rabbit:brokerSpikeTest`: the spike half (classes that record broker facts; tags `broker` and `spike`, rule: `broker` and `Spike` in the class name, enforced by `BrokerTestSplitGuardTest`). `task test:broker` runs both. Nothing in `acceptance-tests` needs a live broker: every suite filters `not @broker`.
- `task test:keychain` touches the real OS keychain, `task test:screenshots` writes screenshots: both are opt-in.
- `task run` launches the app. `task dev:up` / `dev:seed -- --password dev-password` start and fill a local broker (see `dev/README.md`).
- CI: `.github/workflows/ci.yml` (fast tests on every push/PR, live broker tests on main) and `package.yml` (all four installers on native runners; tag `v*` attaches them to a release).
- `task package` builds the installer for the current OS (`.dmg`, `.msi`, `.rpm`; `package:deb` for Ubuntu). jpackage cannot cross-build: run each on its own OS. Fedora setup: `docs/DEVELOPING_ON_FEDORA.md`; the `.rpm` also needs `rpm-build` and `java-21-openjdk-jmods` (Fedora splits the jmods out of the JDK package). Verified in clean `fedora:42` and `ubuntu:24.04` containers.

## Testing

- Test first: unit tests with fakes behind the `core` interfaces, BDD scenarios for user-visible behaviour, Compose UI tests for screens, Testcontainers for anything that depends on real broker behaviour. When a claim about the broker is unverified, write a live test instead of guessing (see `RealBrokerStreamExchangeAnnotationsTest`, `RealBrokerMqttHeadersTest`).
- Run the affected module's tests while working; run the whole suite once before proposing a merge. Do not run `brokerTest` or `brokerSpikeTest` in full unless shared transport code changed.
- Every Test task logs `TEST START`/`TEST DONE` per class (and `SLOW TEST` over 30 s). Fast tasks time out after 15 min; `brokerTest` after 25 min, `brokerSpikeTest`, `keychainTest` and `screenshotTest` after 40 min (root `build.gradle.kts`; the two broker limits are CI time x 1.5, measured in M2-18, and the CI steps use the same numbers).
- Give UI elements a `testTag`; keep existing tags stable, tests and BDD steps rely on them.
- Tests must be deterministic: bounded polling with a clear failure message, never a bare `Thread.sleep`.

## Safety rules (never break)

- Streams: read only with an unnamed or `noTrackingStrategy()` consumer, so browsing changes nothing on the broker.
- Queues: the app's own process never gets, consumes, acknowledges, rejects, deletes or purges queue messages. The only way the app reads messages is `ManagementQueueMessageInspector` (management API `get` with `ack_requeue_true`); never add another read path. The one accepted way messages are moved is a broker-side dynamic Shovel (M8, owner decision 2026-11-09), created, read and removed only through `ShovelGateway` with the safeguards in `docs/spec.md` "Forwarding messages with a dynamic Shovel": typed confirmation naming source and destination, dry run, `ack-mode` `on-confirm` only, never delete the source queue, never write to a 3.x broker, Shovel URIs (they carry credentials) never logged, shown or returned, no Shovel as a migration action, no MCP tool that creates or removes a Shovel. Keep `RealBrokerM7GateTest` and `RealBrokerM8GateTest` green when touching either.
- RabbitMQ 3.x is supported only as a read-only migration source (M8): send it GET requests only.
- Passwords live only in the OS keychain and in the per-connection holder's memory. Never put them in `SessionState`, logs, error strings or MCP output.
- No silent fallback between TLS and plain; the profile switch decides.
- Topology changes and publishing respect the profile's declared permissions and topology control level.

## Conventions

- Keep composables thin: formatting and decisions go into `core` as pure, unit-tested functions; copying goes through `rememberCopyToClipboard()`.
- Compose pitfall: `verticalScroll`/`horizontalScroll` give children an infinite max size, so `weight`, `fillMax*` and `TextOverflow.Ellipsis` do nothing useful inside them. Put the scroll where the content truly overflows and show a scrollbar for mouse users.
- Do not add dependencies without a reason in the commit message; check the license is compatible with MIT and update `THIRD_PARTY_NOTICES.md`.
- MQTT headers are MQTT 5 user properties (Paho MQTT 5 client, RabbitMQ 4+). The publisher is tested through the small `MqttWire` seam, not Paho's interfaces.
- Commit messages: short imperative subject, then a body of what and why. Never use `git add -A` when other agents may be editing the same checkout.

## Working with the owner and agents

- The repo has a team loop: `/iterate <task>` runs solution-architect, test-developer and senior-developer (`.claude/agents/`, `.claude/commands/iterate.md`); the `coordinator` agent drives it. Agents run in their own worktrees.
- Ask the owner only for product decisions (what screens show, wording, scope, new dependencies). Technical questions go to the architect.
- Do not push, merge or delete branches and worktrees without the owner's word; say which branch you mean.
- Report plainly what was not run.
