# Measure the policy claims M9-08 had to leave open

Milestone: M9 (follow-up)    Depends on: M9-08 (merged)    Size: S

## Goal
Each claim the policy step states as unmeasured has a live row and an answer, and each code constant or note that rests on one is confirmed or changed in one line.

## Why
M9-08 ships conservative defaults and says so. This task turns them into facts. Live rows only (a `@broker` class `RealBrokerM9PolicyClaimsTest`, Testcontainers, RabbitMQ 4.4.0, bounded polls of about 6 s per policy, no fixed sleep, no queue message read); the answers go into `docs/spec.md` "Measured facts" and this brief.

## Scope
In, one row each:
1. Precedence of a policy over an argument for `max-age` and `stream-max-segment-size-bytes` (a stream created with `x-max-age` 1D, then a policy `max-age` 7D; same for the segment size): decides whether `RetentionSources.policyWinsOverArgument` grows (one line) and which scenario in `03` is added.
2. `dead-letter-strategy: at-least-once` with `overflow: reject-publish-dlx` on a quorum queue: accepted? in effect? Decides whether the at-least-once note (`PlannerTexts.AT_LEAST_ONCE_WITHOUT_REJECT`) may fire for it (today it fires unless `overflow` is exactly `reject-publish`) and whether `reject-publish-dlx` stays offered for quorum queues.
3. Does `apply-to: queues` include streams? Decides whether `QUEUES` may offer more than the intersection.
4. The boundaries the form lets through untested (`max-length 0`, `message-ttl 0`, `delivery-limit 0`, `max-length-bytes 0`): the status of each PUT, so the catalog can say `min`.
5. Which policy wins at an equal priority (the note says RabbitMQ does not say; record only).
Out: new form features; operator policies.

## Acceptance
- The class runs green; each row records its answer; a changed constant has a unit-test row that states it; the architect updates the spec and scenarios named above.

## MCP
None (spike).

## Open points
None beyond the five rows.

## Definition of done
Live rows run (or reported as not run), spec "Measured facts" updated, M9-08's "Open points" lines moved out.

Status 2026-11-18: briefed (ready); parent task merged.
