# Taskfile for local developer commands (fast tests, @broker tests)

Milestone: M1    Depends on: M1-01-skeleton-and-spike    Size: S

## Goal

A `Taskfile.yml` at the repo root gives local developers short, memorable commands for the things this
task cares about — the fast default test suite and the Docker-dependent `@broker` suite — instead of
everyone having to recall the exact `./gradlew` invocations. Running `task test` runs the fast suite across
all modules; `task test:broker` runs `:data-rabbit:brokerTest` (and will pick up any later module's
`brokerTest`-style task without the developer needing to know its exact name). The `api.version=1.43`
Testcontainers pin that M1-01 found necessary against this machine's Docker Desktop is re-confirmed as part
of wiring `task test:broker`, and the finding (still relevant, still local) stays recorded in
`docs/spec.md`'s Risks section.

## Why

This replaces the task formerly named `M1-CI-01-broker-tests-wiring`. The product owner decided
(2026-11-03) that there is no CI pipeline being set up right now — the project stays in
local-development-only mode for the time being — overriding the architect's earlier GitHub Actions call for
that task. The underlying engineering concern that task existed to address (making the Docker-dependent
`@broker` suite easy and reliable to run, not just possible via a long `./gradlew` command remembered by
heart) is still real for local use, so the task is kept, retargeted at local developer ergonomics via
go-task (https://taskfile.dev) instead of a CI provider. `docs/spec.md`'s Risks section already carries the
Testcontainers API-version finding from M1-01; no other part of the spec commits to a CI pipeline, so no
spec correction is needed beyond what this brief already describes doing.

## Scope

In:
- `Taskfile.yml` at the repo root (go-task convention; `version: '3'`).
- `task test` — wraps `./gradlew test` (fast, no Docker required; every module's default `test` task already
  excludes the `broker` tag, per `data-rabbit/build.gradle.kts`'s `excludeTags("broker")`).
- `task test:broker` — wraps `./gradlew :data-rabbit:brokerTest`. Confirm this still passes locally (Docker
  Desktop running) before marking this task done; this is a re-confirmation of M1-01's finding, not new
  investigation. If the `api.version=1.43` pin (`data-rabbit/build.gradle.kts`, `brokerTest` task) still
  resolves it against whatever Docker Desktop version is on the machine this task is done on, record that
  version number alongside the one already in `docs/spec.md`'s Risks section; if it no longer resolves it
  (Docker Desktop updated since M1-01), investigate and adjust the pin, same as M1-01 did.
- `task run` — wraps `./gradlew :desktopApp:run`, since "open the app" is as basic a local command as
  "run the tests" and costs nothing extra to wire alongside the two above.
- `task package:dmg` and `task package:msi` — wrap `./gradlew :desktopApp:packageDmg` /
  `:desktopApp:packageMsi` respectively, matching the two installer tasks already in
  `desktopApp/build.gradle.kts`. No change to expected outcome: `packageMsi` stays unverified on this
  (macOS) machine, same as `docs/spec.md`'s Packaging row already says; this task does not newly verify it.
- A short comment block at the top of `Taskfile.yml` (or `task --list`'s own `desc:` fields on each task)
  documenting what each command does and that go-task (https://taskfile.dev) must be installed separately —
  this is the task's only "documentation" deliverable; no new root `README.md` is created by this task (none
  exists yet; creating one is a separate, broader decision left for later).

Out:
- Anything CI-related: no CI provider selection, no CI job definitions, no `.github/workflows/`, no
  GitHub-hosted-runner Docker Engine vs. Docker Desktop licensing question. All of that is dropped, not
  deferred — it does not apply while the project is local-development-only.
- A root `README.md` or other onboarding documentation beyond the Taskfile's own `desc:` fields.
- A `task build`, `task lint`, `task check`, `task clean`, or similar catch-all convenience task: no linter
  is chosen yet (`docs/spec.md` still lists ktlint/detekt as "once chosen"), and a generic `build`/`check`
  wrapper adds no value over `test` + `test:broker` for what this task is scoped to cover. Left for whichever
  task next needs it (for example, once ktlint/detekt is actually adopted).
- Any new `@broker` test content, or any change to `data-rabbit/build.gradle.kts`'s tag-based exclusion —
  this task only wraps existing Gradle tasks, it does not add test coverage.
- Resolving the Testcontainers `withPluginsEnabled` deprecation warning noted in `docs/spec.md`'s Risks
  section (M1-01 finding) — unrelated to this task, left for whichever task next touches
  `data-rabbit/build.gradle.kts`.

## Acceptance

No `tests/bdd/features/` scenario is targeted (this is local tooling, like M1-01). Done means:

- `task test` runs green locally and is equivalent to `./gradlew test` (same tests run, same result).
- `task test:broker` runs green locally with Docker Desktop available, and actually starts a RabbitMQ
  container (not skipped) — equivalent to `./gradlew :data-rabbit:brokerTest`.
- `task run` opens the desktop app window, equivalent to `./gradlew :desktopApp:run`.
- `task package:dmg` produces the same working `.dmg` that `./gradlew :desktopApp:packageDmg` already
  produces (per `docs/spec.md`'s Packaging row); `task package:msi` is wired identically but stays
  unverified on macOS, same caveat the underlying Gradle task already carries.
- `docs/spec.md`'s Risks section records whichever Docker Desktop/API version this task's `test:broker`
  confirmation ran against (may be unchanged from M1-01's numbers, or updated if the local machine's Docker
  Desktop has since changed).

## Design notes

- Each Taskfile entry is a thin wrapper (`cmds: ["./gradlew ..."]`) with a one-line `desc:` — no logic
  belongs in the Taskfile itself; it exists purely to shorten and standardize the command a developer types,
  not to change what Gradle does.
- Keep `test` and `test:broker` as separate task entries (not one task with a flag) so a developer without
  Docker running can still use `task test` day to day, mirroring the existing Gradle-level separation
  between `test` and `brokerTest`.

## Open points

- The `api.version=1.43` Testcontainers pin (`data-rabbit/build.gradle.kts`, `brokerTest` task) is expected
  to still be needed for local `task test:broker` runs on this machine's Docker Desktop; re-confirm rather
  than assume, and update `docs/spec.md`'s Risks section either way (confirmed unchanged, or changed and
  why). This replaces the CI-runner version of the same open question from the old `M1-CI-01` brief, which
  no longer applies.
- go-task itself is a new local dependency (not previously mentioned anywhere in `docs/spec.md` or the
  module skeleton); it is assumed available via Homebrew (`brew install go-task`) on macOS dev machines, same
  installation story as the other CLI tools M1-01 already assumed (Gradle wrapper aside). Not independently
  verified as part of this task's authority to decide — if go-task itself cannot be installed or run on a
  given developer's machine, that is worth a quick note back rather than silently working around it with a
  different task runner.

## Definition of done

- `Taskfile.yml` exists at the repo root with `test`, `test:broker`, `run`, `package:dmg`, `package:msi`
  tasks, each passing/behaving identically to its underlying `./gradlew` command, confirmed locally.
- `docs/spec.md` Risks section reflects this task's local Docker Desktop/API version re-confirmation.
- No unrelated changes (no new test coverage, no CI config, no `data-rabbit`/`desktopApp` production code
  changes).
