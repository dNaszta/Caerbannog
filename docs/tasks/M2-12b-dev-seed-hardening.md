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

## Status
- Step 2 (test developer): tests first. `SeedArgsTest` +6 (rejected mix names `--max-age` and `--queues`; five allowed mixes), new `CaHintTest` (4). Stub `caHint(failureMessage: String?): String? = null` in `dev-seed/src/main/.../CaHint.kt` (signature only; the senior developer replaces the body and wires it into `main`). `./gradlew :dev-seed:test`: 44 tests, 4 red (rejected mix not thrown; three hint tests get null), 40 green incl. the 34 untouched.
- Step 3 (senior developer): `caHint` implemented and printed from the `main` catch block; `SeedArgs.parse` rejects `--max-age` with `--queues N` off `/`; `dev/README.md` updated (a to d). Measured on the dev broker: `--queues 10000` OK in about 34 s; `50000` OK in about 3 min (a seed right after got a connect timeout while the broker was busy); `100000` fails with `400 Exceeded HTTP request body size limit`. README states 50,000 as the ceiling. **Conflict:** the existing test `SeedArgsTest."every flag is read"` uses `--max-age 7D --queues 1000 --vhost many`, exactly the mix now rejected, so it fails (43 of 44 green). The brief forbids touching existing tests; waiting for a ruling (suggest the test developer changes that test to `--queues 0` or vhost `/`).
- Step 4 (test developer): **edited the existing test** `SeedArgsTest."every flag is read"`: it passed `--max-age 7D --queues 1000 --vhost many`, the mix the new rule rejects. It now parses every flag except `--max-age` (all assertions kept, `retentionMaxAge` null), then reads `--max-age 7D` in a second `parse` with `--vhost many`. No assertion weakened; every flag still read with a non-default value. `./gradlew test` BUILD SUCCESSFUL (`verifyTestDiscovery` part of it); `dev-seed` 44 tests (SeedArgsTest 20, CaHintTest 4, others 20) green. Mutation: rule disabled and `caHint` always null gave 4 red (the rejected mix, three hint tests), restored, green. README a to d match the code; 10,000 queues re-run on the dev broker: `dev:up` plus seed 35 s total, consistent with about 34 s; `dev:reset` done. Not run: 50,000 and 100,000 (taken from the developer's measurement), `@broker`/screenshot/keychain tags. No findings.
