# The stream list and the MCP stream tools show the byte limit of a stream

Milestone: M9 (follow-up)    Depends on: M9-08 slice 1 (merged)    Size: S    BLOCKED on one owner decision

## Goal
A stream limited by `max-length-bytes` no longer looks unlimited: the Retention column, the stream detail and the MCP stream tools show the byte limit as well as the age, for retention set by arguments and by a policy alike.

## Why
M9-08 reads the byte limit into `StreamSummary.retention.maxLengthBytes` (and lets the policy win, S-14c-4) but `RetentionDisplay`, the Retention column, the detail and the MCP `retention` print the age only (gap 1 of the M9-08 brief). A pre-existing gap that the policy step makes more visible: the planner's own "Keep at most (bytes)" cannot be seen afterwards.

## Owner decision (product, the only one)
What the Retention column shows. Options: (a) leave as is; (b) one cell with both, for example `7D`, `1 MB` or `7D, 1 MB` in the compact byte format the app already uses, and a `maxLengthBytes` field in the detail and the MCP tools; (c) a new column. Recommendation: (b). Nothing in this task starts until the owner answers; the segment size stays out.

## Scope (if b)
In: `RetentionDisplay` (core, pure), `StreamRows`, `StreamsTable`, the stream detail, `McpServer.kt` list and detail tools (`retention` text plus an additive `maxLengthBytes` number field, null when none), `docs/ux/Streams.dc.html`; scenarios in `03_stream_list.feature` and `14_mcp_server.feature` that SEE the byte value (the two `@m9-08b` scenarios that assert the summary stay).
Out: segment size, the policy's name, a "set by policy" marker (decided against, 2026-11-16).

## Acceptance
- BDD: new scenarios in `03` (a cell shows `1 MB`, both shows `7D, 1 MB`) and `14` (the tool's field), tags `@fake-broker`, tag `@m9-08d`; unit rows for `RetentionDisplay`.

## MCP
Existing tools, additive field only; read-only GETs as today; no write path.

## Open points
The owner's choice above.

## Definition of done
Tests first and passing; spec A5 sentence about the byte limit updated; `tests/bdd/README.md` tag row added.
