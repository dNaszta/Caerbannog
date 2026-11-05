# dev-seed hardening (follow-up of M2-12 review)

Milestone: M2 (developer tooling)    Depends on: M2-12    Size: S (under half a day)

## Goal
The seed tool fails clearly on a wrong CA and on a contradictory flag mix, and the dev README states the three behaviours the review found.

## Why
M2-12 step 5 review, findings 1, 2, 3 and 6 (`docs/tasks/M2-12-local-dev-environment.md`, "Review"). Developer tooling only.

## Scope
In:
- Wrong CA: when the failure message is null or empty (`StreamException: null`), `main` prints a hint that the `--ca` file may not be the CA that signed the broker certificate. No password in it.
- `SeedArgs`: reject `--max-age` together with `--queues N` (N > 0) and `--vhost` other than `/` (today it is silently ignored). Message names both flags.
- `dev/README.md`: (a) `--streams x` creates the stream when it does not exist; (b) `--queues 0` with `--vhost many` publishes into streams of that vhost and creates them; (c) the practical maximum of `--queues` (state the measured number; 5,000,000 fails on request body size), (d) the floating image tag is deliberate.
Out: `DEV_SEED_PASSWORD` env var (ruled out, would change the tested contract), digest pinning, any change to compose or definitions.

## Acceptance
- BDD: none. Unit tests first in `dev-seed/src/test` (`SeedArgsTest`: the rejected mix, and the allowed mixes: `--max-age` with `--queues` on `/`, `--max-age` without `--queues` on `many`); the CA hint as a pure function (message in, hint out) with a test.
- `./gradlew test` green; the 34 existing tests untouched.

## Design notes
Pure functions in `dev-seed/src/main`; no new library; no Docker in tests.

## UX
None.

## Open points
The measured `--queues` maximum: the developer runs 10,000 and 100,000 once and writes the result.

## Definition of done
Tests first and green, README updated, no unrelated change, `docs/tasks/README.md` row added.
