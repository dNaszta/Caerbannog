# dev-seed --hold: keep publishers and consumers connected

Milestone: M2    Depends on: M2-12, M2-12b (merged), M2-06 (for the screen to look at)    Size: S

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
