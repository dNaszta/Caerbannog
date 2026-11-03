# CI wiring for @broker Testcontainers tests on Docker Desktop

Milestone: M1    Depends on: M1-01-skeleton-and-spike    Size: S

## Goal

CI runs `./gradlew :data-rabbit:brokerTest` (and, from M1-02-onward tasks, any other Gradle task tagged
`broker`/`@broker`) successfully against the CI runners' own Docker Desktop install, with the fast default
`test` task still excluding those tests. If the CI runners' Docker Desktop rejects Testcontainers the same
way the dev machine's does, the fix is applied and documented in the same place M1-01 documented it.

## Why

M1-01 (`docs/tasks/M1-01-skeleton-and-spike.md`, Status / Open points resolution) found that Testcontainers
1.20.4–1.21.3 fail against this machine's Docker Desktop (29.8.2, API 1.56, `MinAPIVersion` 1.40) because
Testcontainers' `DockerClientProviderStrategy.getTransportConfig()` hardcodes a fallback API version (1.32)
that this engine's `MinAPIVersion` rejects with HTTP 400. The workaround — pinning
`systemProperty("api.version", "1.43")` on the forked test JVM, in `data-rabbit/build.gradle.kts`'s
`brokerTest` task — is proven locally but never run against a CI Docker Desktop install, which may be a
different version with a different (or no) `MinAPIVersion` floor. `docs/spec.md`'s Risks section already
flags this as something to confirm before relying on `@broker` scenarios in CI. The product-owner-level
decision here (Docker Desktop, for both local dev and CI, over Podman/Colima) is already made and final
(M1-01 Status section, 2026-11-03) — this task is pure engineering verification and CI plumbing, nothing
about it needs to go back to the product owner.

## Scope

In:
- A CI pipeline (or CI job) definition that runs `./gradlew test` (fast, no Docker required) on every push,
  and a separate job/step that runs `./gradlew :data-rabbit:brokerTest` with Docker Desktop available on
  the runner.
- Confirm whether the `api.version=1.43` pin is needed on the CI runner's Docker Desktop version. If the CI
  version differs enough that 1.43 is wrong or unneeded, adjust the pin (or remove it) and record the CI
  runner's Docker Desktop version and API version alongside the dev-machine numbers already in
  `docs/spec.md`'s Risks section.
- If the CI job cannot get Docker Desktop working at all (licensing, runner image limits, etc.), stop and
  report back rather than silently falling back to something else — the Docker Desktop choice is a
  product-owner decision already made (M1-01 Status section) and is out of this task's authority to change.
- Keep the `brokerTest` task's tag-based exclusion from the default `test` task (`excludeTags("broker")` in
  `data-rabbit/build.gradle.kts`) unchanged; this task only adds CI plumbing around tasks that already exist.

Out:
- Any change to which runtime is used (Docker Desktop stays the answer; Podman/Colima is not revisited).
- Any new `@broker` test content — this is wiring, not new coverage.
- Signing/notarizing installer artifacts in CI, or any other CI job unrelated to running `@broker` tests.
- Resolving the Testcontainers `withPluginsEnabled` deprecation warning noted in `docs/spec.md`'s Risks
  section (M1-01 finding) — that is a `data-rabbit` code change, not a CI concern, and is left for whichever
  task next touches `data-rabbit/build.gradle.kts` (expected to be M1-03 or later, once real `@broker`
  integration tests are built).

## Acceptance

No `tests/bdd/features/` scenario is targeted (this is CI/build infrastructure, like M1-01). Done means:

- A CI run (link or log attached to the PR) shows both the fast `test` task and the `brokerTest` task
  passing green on the CI runner, with `brokerTest` actually starting a RabbitMQ container (not skipped).
- `docs/spec.md`'s Risks section updated: either confirm the 1.43 pin travels unchanged to CI, or record
  what changed and why.
- If Docker Desktop cannot run on the CI runner at all, this task stops short of "done", reports the
  blocker back (do not substitute a different runtime without a new product-owner decision), and is marked
  `blocked` in `docs/tasks/README.md`.

## Design notes

- CI config lives wherever the team's CI provider expects it (e.g. `.github/workflows/`); no production
  module code should need to change for this task beyond the `brokerTest` task's `systemProperty` value if
  the API version needs adjusting.
- Keep the two jobs (fast tests vs. `@broker` tests) separate so a Docker outage on the CI runner only fails
  the broker job, not the whole pipeline.

## Open points

- Exact CI provider/runner is not yet chosen by this task's author; the developer picking this up should
  confirm with whoever owns CI infrastructure before writing the pipeline config.
- Whether the CI runner's Docker Desktop requires a paid license for this organization was explicitly left
  unconfirmed by the M1-01 Docker Desktop decision (M1-01 Status section: "License eligibility for this org
  was not separately confirmed as part of this decision"); if the CI provider's hosted runners turn out to
  need a commercial Docker Desktop license, that is a new product-owner question, not something this task
  resolves on its own — stop and escalate rather than guessing.

## Definition of done

- CI runs both the fast and the `@broker` test jobs green, as described in Acceptance.
- `docs/spec.md` Risks section reflects the CI runner's actual Docker Desktop/API version findings.
- No unrelated changes (no new test coverage, no runtime substitution, no `data-rabbit` production code).
