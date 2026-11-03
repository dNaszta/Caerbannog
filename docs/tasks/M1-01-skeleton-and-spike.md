# Skeleton and M1 tooling spike

Milestone: M1    Depends on: none (first task)    Size: L (spike — see Open points about splitting)

## Goal

A running, empty four-module desktop app (`core`, `data-rabbit`, `ui`, `desktopApp`) builds and opens a
window, and the three unverified tooling choices from `docs/spec.md` are each settled by a demonstrated,
trivial proof rather than a guess: the build tool (Kotlin Toolchain vs Gradle fallback), the test stack
(unit, BDD, integration, UI), and the keychain library for connection-profile secrets.

## Why

`docs/spec.md`, Milestones table: "M1 Skeleton and connection — Modules, test setup, profiles, installer
spike". Risks section: the Kotlin Toolchain installer question and "the keychain library choice needs a
quick spike". Open questions, first bullet: run `kotlin package --help` and fall back to Gradle if there is
no dmg/msi support. Module layout and tech-stack table and the Testing table both mark several rows
"to confirm" (Packaging, Settings and secrets, Testing row). Nothing else in M1 (connection profiles,
permission check) can be built test-first until this ground is settled, per the spec's quality requirement
(TDD/BDD, `docs/spec.md` "Testing: TDD and BDD").

## Scope

In:
- Four-module skeleton matching the Module layout table: `core` (commonMain, no RabbitMQ imports), `data-rabbit`
  (jvmMain), `ui` (commonMain, no broker/network imports), `desktopApp` (jvmMain, `main()` + window).
- Run `kotlin package --help` on the installed Kotlin Toolchain CLI, capture the output, and apply the
  spec's own decision rule (dmg/msi present to stay on Kotlin Toolchain; otherwise fall back to Gradle with
  the Compose Multiplatform Gradle plugin). Set up whichever tool is chosen end to end for all four modules.
- `desktopApp` opens a plain Compose window (placeholder content only, e.g. app name and version) via the
  chosen tool's run command.
- Pick Kotest or JUnit 5 (+ `kotlinx-coroutines-test`) and get one trivial test passing in `core` through
  the chosen build tool's test task.
- Wire Cucumber-JVM with Kotlin step definitions so it can discover and run a `.feature` file, proven with
  one throwaway scenario outside `tests/bdd/features/` (see Design notes — the 12 canonical feature files
  are not touched by this task).
- Wire Testcontainers and start a RabbitMQ 4.x container once, confirming the management port answers.
  Tag this test so it is skipped (not failed) when Docker is unavailable.
- Wire the Compose Multiplatform UI test API and render one trivial composable, asserting a text node exists.
- Spike a keychain/secret-storage library (candidate: `com.github.javakeyring:java-keyring`) with a
  write/read/delete round trip for a throwaway secret, run at least on macOS (the current dev machine).
- Update `docs/spec.md`: replace each "to confirm" marker this spike resolves with the decision and a one
  line reason; move resolved items out of Open questions; update Risks if a risk is retired or changed.

Out:
- Any real connection-profile CRUD (that is `tests/bdd/features/01_connections.feature`, a follow-up task,
  tentatively `M1-02`).
- Any real permission-check logic (`02_permission_check.feature`, tentatively `M1-03`).
- Step definitions for any of the 12 canonical feature files.
- TLS certificate handling, the management API client, the stream client — `data-rabbit` stays an empty
  shell with build wiring only.
- Producing a signed/notarized installer artifact; only proving the packaging command exists and runs for
  an empty app.
- CI pipeline setup (a separate task), though this spike's Docker finding feeds that task.

## Acceptance

This is a spike: no scenario in `tests/bdd/features/` is targeted by this task. The scenarios in
`01_connections.feature` and `02_permission_check.feature` (tagged `@M1`) are real feature work that
depends on this skeleton and belongs to the follow-up tasks named above.

Instead, "done" means each of these is demonstrated and recorded (where to record: a short note appended to
this file's Open points resolution, plus the `docs/spec.md` updates described in Scope):

1. Build tool: `kotlin package --help` output captured verbatim; decision stated as "Kotlin Toolchain" or
   "Gradle fallback" with the one-line reason from the spec's own rule.
2. `desktopApp` launches an empty window via the chosen tool's run command (manual check is acceptable;
   screenshot or terminal transcript attached to the PR).
3. One trivial unit test passes in `core` via the chosen framework and the chosen build tool's test task.
4. One trivial Cucumber-JVM scenario (Kotlin steps) runs and passes outside the canonical `tests/bdd/features/`
   tree (see Design notes for where).
5. One Testcontainers test starts a RabbitMQ 4.x container and reaches its management port; it is skipped
   cleanly (not failed, not hung) when Docker is absent.
6. One Compose Multiplatform UI test renders a composable and finds it by text.
7. One keychain round-trip test (write, read back, delete) passes locally on macOS; Windows is noted as
   unverified until run on Windows hardware or CI (see Open points).

## Design notes

- Module boundaries from the start: `core` has zero RabbitMQ or network imports (not even as a test
  dependency) so its tests always run against fakes; `ui` has zero broker/network imports. `data-rabbit`
  is the only module allowed to depend on `com.rabbitmq:stream-client` or the management HTTP client later;
  for this task it stays an empty jvmMain module with just the build wiring, so the boundary exists before
  anyone is tempted to cross it.
- Where the Cucumber-JVM proof scenario and its step defs live is itself part of this spike's output, not
  pre-decided: propose a dedicated test module (for example `acceptance-tests`, jvmMain test only) that can
  depend on both `core` (for fakes, `@fake-broker` scenarios) and `data-rabbit` (for `@broker` scenarios,
  later). Do not add the throwaway proof scenario to `tests/bdd/features/` — that directory is the canonical
  BDD source tied to the traceability table in `tests/bdd/README.md`; keep the spike's scratch `.feature`
  file in the new test module's own resources and delete it once `M1-02` lands real step definitions.
- The Testcontainers proof test belongs under `data-rabbit`'s test source set (that is where real broker
  calls will live), tagged clearly (e.g. a JUnit/Kotest tag mirroring `@broker`) so it is excluded from the
  default fast test run and only opted into when Docker is present.
- Keychain spike: do not reuse or invent the real `ConnectionProfile` domain model for this test — that
  model belongs to `M1-02`. Use a throwaway key/value pair local to the spike test, and make sure the test
  deletes what it wrote (no leftover entries in the OS keychain after a run).
- No dependency injection framework, navigation library or state-management pattern is introduced yet; the
  placeholder screen is a single `@Composable fun App()` with no view model, since there is no behavior to
  hold state for.

## UX

No real screen from `docs/ux/` is built in this task. `desktopApp` shows a placeholder Compose screen only
(app name and version text) to prove the window launches end to end. The real target is
`docs/ux/Main.dc.html` (Connections screen), built in the follow-up task `M1-02`; this task does not need
to match it.

## Open points

These are listed so the senior developer does not guess; see "escalation" note below for the one item that
needs a decision from outside engineering before the Testcontainers/`@broker` part of this task can be
trusted for CI use.

- **Kotlin Toolchain vs Gradle (spec open question, line ~195 of `docs/spec.md`):** the spec already states
  the decision rule and its fallback, so this is not a new product decision — run the check, apply the
  rule, record the result. Escalate only if the CLI's answer is ambiguous (for example, partial jpackage
  support that the spec's wording does not clearly cover).
- **Keychain library choice:** an engineering call, not a product one. Starting candidate:
  `com.github.javakeyring:java-keyring` (simple cross-platform API over macOS Keychain, Windows Credential
  Manager, and libsecret on Linux — Linux is not a v1 packaging target per the M5 gate, so only macOS and
  Windows need to actually work). Confirm with the round-trip test; record the final choice in
  `docs/spec.md`'s "Settings and secrets" row.
- **Test stack choices (Kotest vs JUnit 5, Cucumber-JVM, Compose UI test API):** engineering calls, no
  product input needed. Record the final choices in `docs/spec.md`'s Testing table.
- **Docker / Testcontainers for `@broker` scenarios — needs a decision outside engineering.** Testcontainers
  requires a working Docker (or Docker-API-compatible) runtime wherever `@broker` tests run, including CI.
  Two things are open that this task cannot resolve alone:
  - Is Docker (or Podman/Colima) available on the CI runners this project will use?
  - Docker Desktop's license requires a paid subscription for commercial use at larger organizations (not
    for personal use, small business, or education); confirm with whoever owns tooling procurement whether
    Docker Desktop's license terms apply and are acceptable for this project's organization, or whether the
    team should standardize on a license-free alternative (Podman, Colima, or a remote Docker daemon) for
    both local dev and CI.
  - **Recommendation:** default to Colima or Podman for local dev and CI to sidestep the licensing question
    entirely, since Testcontainers works against either with a compatible socket; keep Docker Desktop as an
    option only if someone already has an eligible license. This does not block this task's own deliverable
    (the trivial container-start test can be demonstrated with whatever runtime is on the dev machine), but
    it blocks deciding how `@broker` scenarios run in CI, which is a follow-up task.
- **Non-administrator permission read via the management API** and **stream search by name**: real spec
  open questions, unrelated to this task's build/test-stack scope; left untouched here, still open for
  `M1-03` and the global-search task respectively.

## Definition of done

- Each of the seven "Acceptance" proofs is demonstrated and recorded.
- `docs/spec.md` updated in place: Module layout/tech-stack table and Testing table rows with "to confirm"
  resolved to the actual choice with a one-line reason; the Kotlin Toolchain open question either resolved
  or left open with the captured `kotlin package --help` output quoted; the keychain risk line updated or
  retired.
- `tests/bdd/features/` (the 12 canonical files) are untouched by this task.
- `docs/tasks/README.md` status for this task updated from "in progress" to "done".
- No unrelated changes (no real connection-profile, permission-check, or screen code beyond the placeholder
  described above).

## Status

- Step: 3 (build) — in progress
- Round: 1
- Last verdict: none yet
- Open findings: none yet
- Open questions:
  - Product decision made (2026-11-03): use **Docker Desktop** as the Testcontainers runtime for `@broker`
    scenarios, for both local dev and CI (overrides the architect's Colima/Podman recommendation above).
    License eligibility for this org was not separately confirmed as part of this decision; if that becomes
    a concern later it is a separate follow-up, not a blocker for this task.
  - Kotlin Toolchain vs Gradle: test developer found the installed `kotlin` CLI (v2.4.21, Homebrew) has no
    `new`, `run`, or `package` subcommands at all — not the project-oriented Toolchain the spec describes.
    Senior developer should still run and record the formal `kotlin package --help` check per Acceptance
    item 1, but should expect to land on the Gradle fallback.
  - Keychain library and test-stack choices: engineering calls, confirmed candidates now exercised in the
    red tests (`com.github.javakeyring:java-keyring`; JUnit 5 + `cucumber-junit-platform-engine` for BDD).
    Senior developer may still switch the plain unit-test framework to Kotest if preferred — only
    `core/src/jvmTest/kotlin/dev/caerbannog/core/SpikeSanityTest.kt` depends on that choice.
- Branch: `m1-01-skeleton-and-spike` (scaffold + red tests at commit `0b6d5a1`)
