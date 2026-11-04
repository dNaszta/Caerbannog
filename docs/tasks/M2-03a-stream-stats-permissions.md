# Mini-spike: stream statistics for non-admin users

Milestone: M2    Depends on: M2-02 (merged)    Size: S

Branch: `m2-03a-stream-stats-permissions` from `main`. Needs Docker. First step of M2-03; may be done on the M2-03 branch as its first commit.

## Goal
We know, on a real RabbitMQ 4 broker, whether `Environment.queryStreamStats(stream)` works for users with the `monitoring` tag, the `management` tag and no tag, with and without stream permissions on the stream, and that the call writes nothing.

## Why
Decision D5 (b): the M2 list shows first offset for every visible row. The M2-02 spike only tried admin. `docs/spec.md` open question "Can a user without the management tag...".

## Scope
In: `@broker` tests (extend the `StreamManagementApiSpikeTest` setup, or a new class with the same container): for each of the three users with permissions on `^spike-allowed-.*`: `queryStreamStats` on an allowed stream and on a stream without permission; record the exception type and message when denied (needed for the typed `Unavailable` result). Also: after the call, management `messages` and `tracking` of the stream are unchanged (read-only proof). Record whether the stream client can connect over TLS with the profile CA and WITHOUT host name verification (decision D7 (a): host name is not verified; if it cannot be disabled, say so and what the options are). Record whether one `Environment` (one locator connection) serves many calls and how long a call takes (rough).
Out: production code, UI.

## Acceptance
- Assertions on outcomes (success with `firstOffset`, or the exact exception class), no printouts only; bounded value-based waits, no sleeps.
- Status written in this brief; spec "Management API facts" updated by the architect (the open question closed or narrowed).
- Outcome handling is decided in M2-03 either way: denied or failed means "unavailable" cells, never a failed list.

## Definition of done
Tests green twice with `--rerun-tasks`, no production change, Status filled.
