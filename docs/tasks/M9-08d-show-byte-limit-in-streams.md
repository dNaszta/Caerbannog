# The stream list and the MCP stream tools show the byte limit of a stream

Milestone: M9 (follow-up)    Depends on: M9-08 slice 1 (merged)    Size: S    Status: briefed (ready)

## Goal
A stream limited by `max-length-bytes` no longer looks unlimited: the Retention column, the stream detail and the MCP stream tools show the byte limit as well as the age, for retention set by arguments and by a policy alike.

## Why
M9-08 reads the byte limit into `StreamSummary.retention.maxLengthBytes` (and lets the policy win, S-14c-4) but `displayMaxAge`, the Retention column, the detail and the MCP `retention` print the age only (gap 1 of the M9-08 brief). A pre-existing gap that the policy step makes more visible: the planner's own "Keep at most (bytes)" cannot be seen afterwards. Spec A5.

## Owner decision (2026-11-17, option b)
The owner answered "b". One Retention cell shows the age, the byte limit, or both: `7D`, `1 MB`, `7D, 1 MB` (age first, comma and one space). The stream detail and the MCP stream tools gain an additive `maxLengthBytes` field. Rejected: (a) leave as is, (c) a new column. The segment size stays out. No product question is open.

## Scope
In:
- `core` (pure, unit-tested): a new `Retention.displayRetention()` next to `displayMaxAge()` in `RetentionDisplay.kt`, giving "" when neither is set, and a compact byte formatter. The app has NO byte formatter today (only counts are grouped), so the architect fixes the format: decimal units B, KB, MB, GB, TB (1000-based, so `1000000` is `1 MB`, as the scenarios say), at most one decimal, no trailing `.0` (`1500000` is `1.5 MB`, `999` is `999 B`). `displayMaxAge()` keeps its contract and its tests (M2-04, D9).
- `StreamRows`: `StreamRow.retention` uses `displayRetention()` (line 36); the super-stream partition roll-up (line 68, `distinct()` of the partitions' retentions) uses it too, so partitions that differ in bytes only are not shown as equal.
- `StreamsTable`: no new column; the cell text is what is copied (UI1), so copy gives `7D, 1 MB`.
- Stream detail (UI): the retention line shows the same text; the exact byte count goes where the existing detail layout already shows exact values (tooltip or detail row).
- `mcp-server` `McpServer.kt`: the list tool (about line 577) and `caerbannog_get_stream_details` (about line 622) set `retention` to the same text (`"none"` when neither limit is set, as today) and add `maxLengthBytes` (JSON number, `null` when none). Additive: no existing field changes name or type. Note that `retention` there is today the raw `maxAge` text; with an age only it becomes the compact display form (`604800s` becomes `7D`), the same text as the list. Say so in the commit message.
- `docs/ux/Streams.dc.html`: the Retention note (line 178) says the cell may read `7D`, `1 MB` or `7D, 1 MB`; add one example row with both; header unchanged.
- Scenarios (already written, tags `@m9-08d @needs-M9-08d`; the `needs` tag keeps them out of the default run until the steps exist, 2026-11-18; the step 2 test developer REMOVES `@needs-M9-08d` from the five scenarios and `and not @needs-M9-08d` from `RunStreamListAcceptanceTest` and `RunMcpAcceptanceTest` in the same commit as the step definitions, and deletes its row in `tests/bdd/README.md`): `03_stream_list.feature` (three) and `14_mcp_server.feature` (two). The two `@m9-08b` scenarios that assert the summary stay.

Out: segment size, the policy's name, a "set by policy" marker (decided against, 2026-11-16), any write path, any change to how the byte limit is read.

## Acceptance
- BDD (new, red first):
  - `03_stream_list.feature` / "A stream with a maximum size and no age shows the size as its retention", "A stream with an age and a maximum size shows both in one cell", "A stream with an age only still shows the age alone" (`@m9-08d @fake-broker`).
  - `14_mcp_server.feature` / "MCP server shows the maximum size of a stream as text and as a number", "MCP server gives a null maxLengthBytes for a stream without a size limit" (`@m9-08d`).
  - New step phrases to bind: `a stream {string} declared with "x-max-age" {string} and "x-max-length-bytes" {long}` and `... and no maximum size` (03); `the tool result includes stream {string} with maxLengthBytes {long}` and `... maxLengthBytes null` (14). Reuse the existing retention steps.
- Unit rows: `displayRetention()` (none, age only, bytes only, both, a seconds age with bytes, `0` bytes shown as `0 B`: the broker accepts `0` and its meaning is not measured, so show what was read); the byte formatter (999, 1000, 1500000, 1000000000, a very large Long); the `StreamRows` partition roll-up; a `StreamsTable` copy test.
- Existing `RetentionDisplayTest`, the `@m9-08b` scenarios and the MCP tests stay green.

## Design notes
`core` keeps the rule; `ui` and `McpServer.kt` only call it (composables stay thin). Do not copy the formatter into `mcp-server`. Fakes: `StreamListSteps` and `McpSteps` build the summary through `RetentionSources.merge`; no new broker call and no `data-rabbit` change.

## UX
`docs/ux/Streams.dc.html`, the streams table and the detail. States unchanged (empty, loading, error, locked); only the cell text and the detail line change. Keep existing `testTag`s.

## MCP
Decision (CLAUDE.md): existing tools `caerbannog_list_streams` and `caerbannog_get_stream_details`, additive field `maxLengthBytes` plus the `retention` text as above. Read-only GETs as today; no new tool, no write path, no secret involved.

## Open points
None for the product. Technical, the architect's call: decimal (1000-based) byte units, because the owner's example `1 MB` for `1000000` and the scenarios use them; revisit only if the owner asks for binary units.

## Definition of done
Tests first and passing (`:core:jvmTest`, `:ui:jvmTest`, `:mcp-server:test`, acceptance for `@m9-08d`); no unrelated changes. When built: the spec A5 sentence goes from "until `M9-08d` is built" to the present tense; `tests/bdd/README.md` gets `@m9-08d` in the M9 tag row and the `@m9-08b` text "owner question about showing it is open" is corrected.
