# dev-seed --hold: keep publishers and consumers connected

Milestone: M2    Depends on: M2-12, M2-12b (merged), M2-06 (for the screen to look at)    Size: S

Status: review fixes 1 and 2 done (developer, 2026-11-05): dead return removed, SHUTDOWN_GRACE_SECONDS named, dev-seed 84 tests green; earlier: step 5 review done (architect, 2026-11-05): Accepted with follow-ups (two minor, one idea), ready to merge into local main on the user's word, nothing pushed; earlier: step 4 verify done (tester, 2026-11-05): pass with findings, see the report; earlier: step 3 build done (developer, 2026-11-05): 11 tests green, real check on the plain dev broker passed; NEXT: step 4 verify (tester), then architect review.

## Goal
`task dev:seed -- --password <pw> --hold` publishes as today, then keeps one named publisher and one named consumer per dev stream connected (consumer stores its offset) until Ctrl+C, so the Publishers and consumers tab has rows to show.

## Why
M2-06 hand trial A1: the seed publishes and exits, the tab shows empty sections. The user needs a one-line way to see live rows.

## Scope
In: the flag in `SeedArgs` (off by default, no value), the hold loop with clean close on Ctrl+C, a consumer name and a reference that are readable ("dev-seed-publisher", "dev-seed-consumer"), README line in `dev/` docs, same TLS rules as the rest of the seed (`--no-tls` only by the existing flag).
Out: any change outside `dev-seed` and its docs, queues, super streams.

## Acceptance
- Unit: `SeedArgsTest` parses `--hold` (absent = false, no value allowed); the hold uses the existing client factory so a fake can check connect and close.
- Hand check: with the flag, the tab shows one publisher and one consumer per stream and both disappear on the next refresh after Ctrl+C.

## Design notes
Reuse the seed's stream client and credentials handling; no secret in logs. Test first.

## Open points
None.

## Definition of done
Tests first and passing, no unrelated changes, `dev:seed` description in `Taskfile.yml` mentions `--hold`.

## Test contract (step 2)
Files in `dev-seed/src/test/.../devseed/`: `SeedArgsHoldTest`, `HoldTest`, `TaskfileHoldTest`. No existing test edited.
Stubs the developer fills in: `SeedArgs.hold` (parse the valueless `--hold`, show it in `toString`), `Hold.kt` (`HoldConnector` seam: one `openPublisher(stream, "dev-seed-publisher")` and one `openConsumer(stream, "dev-seed-consumer")` per stream; `Hold.run(awaitStop)` opens, waits, always closes, closes the rest when one close or open fails; real connector = `StreamPublisher`, wired in `Main`, shutdown hook calls close), `Taskfile.yml` descriptions of `dev:seed` and `dev:seed:plain` mention `--hold`, `USAGE` and dev README line.
