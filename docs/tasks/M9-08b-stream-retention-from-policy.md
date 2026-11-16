# The stream list and the MCP stream tools show a retention that a policy sets

Milestone: M9    Depends on: M9-00c (done)    Size: S

NEW 2026-11-16, written at step 3 of `M9-00c`. No dependency on a planner task; it must be done BEFORE the retention block of `M9-08` is released (the planner saves retention as a policy, and without this task the stream list would show an empty Retention column for exactly the streams the planner configured).

## Goal
A stream whose retention is set by a policy shows that retention in the stream list and in the MCP stream tools, like a stream that was created with the retention as arguments.

## Why
M9-00c S-F1d (measured on 4.4.0): a policy `{max-age: 7D, max-length-bytes: 1000000}` on a stream shows in the queue's `effective_policy_definition`, but the stream's `arguments` stay `{x-queue-leader-locator: balanced, x-queue-type: stream}`. `ManagementJson.parseStreamDetail` and the list parser read only `arguments.x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`, so `retention.maxAge` is null (`RealBrokerM9SpikePolicyKeysTest`, test "S-F1d ..."). The spec (A5, "Streams and the response stream") promises retention by policy.

## Scope
In:
- `ManagementJson` (`data-rabbit`): the retention of a stream is read from `effective_policy_definition` (keys `max-age`, `max-length-bytes`, `stream-max-segment-size-bytes`) when the stream's own arguments do not hold the value. Which one wins when BOTH are present is NOT measured: do not decide it by guessing; first add a live row (a stream created with `x-max-age` 1D and then a policy `max-age` 7D, read `effective_policy_definition` and `arguments`, record the answer in the results file) and let the architect decide the precedence from it. Until then: both present is not a case the code may silently resolve; keep the arguments value (today's behaviour) and report it.
- The list rows of `GET /api/queues` on 4.x: whether a row carries `effective_policy_definition` is measured by the same new live test (3.13 does, M8-00 S-R); if the list row lacks it, the detail GET per stream is used only for the visible streams (the screen already makes one statistics call per visible stream); say which in the results file.
- `core.streams.Retention` stays as it is (three fields); the display (`RetentionDisplay`) is unchanged unless the owner wants "set by policy" shown (Owner review list item 7).
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
- Precedence when a stream has retention both as argument and by policy: unmeasured, see Scope.
- Wording: whether the column marks "set by policy" (Owner review list item 7); default: no marker.

## Definition of done
Tests written first and passing, no unrelated changes, spec updated (A5 sentence about the gap removed), the `@broker` scenario RUN or reported as not run.
