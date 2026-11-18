# The stream list and the MCP stream tools show a retention that a policy sets

Milestone: M9    Depends on: M9-00c (done)    Size: S

FOLDED INTO `M9-08` SLICE 1 (architect, 2026-11-17, loop step 1). This is no longer a task of its own: the exact scope, the acceptance and the live test (the single class `RealBrokerM9PolicyStepTest`) are in `docs/tasks/M9-08-policy-step.md`, "Slice 1". What changed against the text below: the pure rule is named `RetentionSources.merge`; `max-length-bytes` is read from the policy first (measured, S-14c-4), the other two keys keep the argument (unmeasured, stated); a scenario for the precedence is added in `03`; the `@m9-08b` tag stays. The text below is kept as the record of the facts.

NEW 2026-11-16, written at step 3 of `M9-00c`. No dependency on a planner task; it must be done BEFORE the retention block of `M9-08` is released (the planner saves retention as a policy, and without this task the stream list would show an empty Retention column for exactly the streams the planner configured).

## Goal
A stream whose retention is set by a policy shows that retention in the stream list and in the MCP stream tools, like a stream that was created with the retention as arguments.

## Why
M9-00c S-F1d (measured on 4.4.0): a policy `{max-age: 7D, max-length-bytes: 1000000}` on a stream shows in the queue's `effective_policy_definition`, but the stream's `arguments` stay `{x-queue-leader-locator: balanced, x-queue-type: stream}`. `ManagementJson.parseStreamDetail` and the list parser read only `arguments.x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`, so `retention.maxAge` is null (`RealBrokerM9SpikePolicyKeysTest`, test "S-F1d ..."). The spec (A5, "Streams and the response stream") promises retention by policy.

## Scope
In:
- `ManagementJson` (`data-rabbit`): the retention of a stream is read from `effective_policy_definition` (keys `max-age`, `max-length-bytes`, `stream-max-segment-size-bytes`) when the stream's own arguments do not hold the value. Which one wins when BOTH are present is NOT measured: do not decide it by guessing; first add a live row (a stream created with `x-max-age` 1D and then a policy `max-age` 7D, read `effective_policy_definition` and `arguments`, record the answer in the results file) and let the architect decide the precedence from it. Until then: both present is not a case the code may silently resolve; keep the arguments value (today's behaviour) and report it.
- The list rows of `GET /api/queues` on 4.x: whether a row carries `effective_policy_definition` is measured by the same new live test (3.13 does, M8-00 S-R); if the list row lacks it, the detail GET per stream is used only for the visible streams (the screen already makes one statistics call per visible stream); say which in the results file.
- `core.streams.Retention` stays as it is (three fields); the display (`RetentionDisplay`) is unchanged: no "set by policy" marker (Owner review list item 7 of `M9-00c`, DECIDED by the owner on 2026-11-16, "do arch suggest").
- The MCP stream tools (`caerbannog_list_streams`, the stream detail tool in `McpServer.kt`) read the same `StreamSummary`, so they gain the value without a schema change; the "none" text of `retention` is no longer wrong for such a stream.
Out: showing the policy name; editing retention; operator policies; classic or quorum queue limits.

## Acceptance
- BDD: `03_stream_list.feature` / "Retention set by a policy is shown like retention set when the stream was created" (outline, 2 rows; @m9-08b @fake-broker)
- BDD: `03_stream_list.feature` / "Retention set by a policy on a real stream is shown" (@m9-08b @broker)
- BDD: `14_mcp_server.feature` / "MCP server shows retention that a policy sets on a stream" (@m9-08b)
- Unit: `ManagementJsonStreamTest` rows for: arguments only; policy only (fixture `data-rabbit/src/test/resources/fixtures/m9-00/` from the spike's own answer: add `s-f1d-stream-detail-with-policy.json` captured by the live test); neither; policy with a key that is not a retention key (ignored).

## Design notes
The parser change is small and pure; no new request. The live test is the only new broker contact and is a `@broker` test of this task, not an edit of the spike classes.

## UX
`docs/ux/Streams.dc.html`: the Retention column and the stream detail; no new state.

## MCP
Evaluated: no new tool, no schema change. The result of the two existing read-only tools becomes correct for policy-set retention. Safety unchanged (read only, same GETs).

## Open points
- Precedence when a stream has retention both as argument and by policy: MEASURED by M9-14c S-14c-4 (`max-length-bytes` only, `docs/tasks/M9-14c-spike-results.md`): the policy decides in both directions and the argument still shows its declared value. Read the policy value first for `max-length-bytes`; the other keys stay unmeasured. Related, by design: the migration check (M9-14c) judges a stream step that declares retention as an argument by the broker's `arguments` alone, so a stream whose retention comes only from a policy is `Different` for such a step (it reads no `effective_policy_definition`).
- Wording: DECIDED (owner, 2026-11-16, item 7 of `M9-00c`): the column does not mark "set by policy".

## Definition of done
Tests written first and passing, no unrelated changes, spec updated (A5 sentence about the gap removed), the `@broker` scenario RUN or reported as not run.

## Measured answers (test developer, M9-08 step 2, 2026-11-17, RabbitMQ 4.4.0 through `rabbitmq:4-management`, `RealBrokerM9PolicyStepTest` 5 of 5 green)
1. `GET /api/queues/{vhost}?...&columns=<the list's columns today>` returns NO `effective_policy_definition` for a stream with a policy (the broker honours the column list exactly). With `effective_policy_definition` ADDED to the column list the same row carries it (`{"max-age":"7D","max-length-bytes":1000000}`). The detail GET carries it with or without a column list. So the stream list needs no detail call: add the column (`ManagementRequests.LIST_COLUMNS`), one request as before. Fixtures: `fixtures/m9-00/s-f1d-stream-detail-with-policy.json` and `s-f1d-stream-list-row-with-policy.json` (captured by the live class with `M9_08_CAPTURE_DIR`).
2. `alternate-exchange` set by a POLICY (`apply-to: exchanges`) acts on an exchange: a message published through the management API to an exchange with no matching binding is `routed: false` before the policy and `routed: true` after it (the alternate exchange is a fanout bound to a queue); an exchange with no policy still answers `routed: false`. The Exchanges target stays.
3. The `max-age` units `M`, `h` and `m`: `1M`, `1h` and `1m` each answered 201. `MaxAgeUnits.measured` is `Y`, `M`, `D`, `h`, `m`, `s` (with `7d`, `7`, `abc` still 400, S-F1c).
4. The list row of `GET /api/queues/{vhost}` with the columns the planner's reader uses carries `type` (`quorum`) and `policy` (the name of the policy that applies); the item `GET /api/policies/{vhost}/{name}` answers one policy that `ManagementJson.parsePolicies` reads (priority, apply-to, definition).

## Status
Folded into `M9-08` slice 1; reviewed with it on 2026-11-17 (architect, step 5): ACCEPTED WITH FOLLOW-UPS (see "Status" of `M9-08-policy-step.md`). Slice 1 is built (`RetentionSources.merge`, both parse sites of `ManagementJson`, `effective_policy_definition` added to `LIST_COLUMNS`, no detail fallback) and its scenarios in `03` and `14` run green; the `@broker` scenario is bound to `RealBrokerM9PolicyStepTest` (green on 4.4.0). It merges with the slice 2 commits, not alone. Open: showing the byte limit is `M9-08d` (owner decision); the precedence for `max-age` and the segment size is `M9-08e` row 1.

Status: done (merged with M9-08, 86ad0540, 2026-11-18).
