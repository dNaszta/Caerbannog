# MCP tools: 3.x compatibility report and shovel status (read only)

Milestone: M8    Depends on: M8-03, M8-08 (merged)    Size: S

LEAN (owner, 2026-11-11): two read-only tools and nothing else.

## Goal
An agent can read the 3.x compatibility report (one page at a time) and the status of Shovels on a 4.x connection. It cannot create, remove, convert, forward or apply anything, and it never sees a password, a hash or a Shovel address.

## Why
Spec "MCP evaluation (architectural rule)": the compatibility report is exposed read-only; Shovel create and remove are NOT; status is read-only and never returns a URI.

## What exists now (main `1418028` plus M8-12b)
`:mcp-server` registers eleven tools (`caerbannog_list_profiles`, `check_connection`, `list_streams`, `get_stream_details`, `browse_stream`, `list_queues`, `list_exchanges`, `list_bindings`, `publish_test_message`, `list_mqtt_clients`, `publish_mqtt`); the migration tools of M5-09 are NOT registered yet. `McpServer` gets its broker access through injected provider lambdas per profile and vhost (no gateway is held). Core has `DefinitionsParser`, `CompatibilityAnalyzer`, `CompatibilityReportJson.encode(report)` (whole report), `DefinitionsSource`/`analyseLive`, `ShovelGateway` (four operations; must NOT be given to the MCP server), `ShovelStatus` (no address field).

## Scope
In:
1. **`caerbannog_analyze_3x_definitions`**. Input `{ "profile"?: string, "file"?: string, "limit"?: int, "offset"?: int }`; exactly one of `profile` (a saved 3.x connection: opened read only, the report built with `analyseLive` through the profile's `DefinitionsSource`) or `file` (an absolute path to a readable `.json` definitions file, at most the 64 MB cap of the screen; read through the same loader). A per-vhost export is refused with the fixed text of the screen (the MCP tool cannot ask for a vhost name). Output: the summary (`caerbannog.compat-report/1` header, source, counts, message-loss queues) plus ONE page of findings: `limit` (default 50, at most 200) and `offset`, the total finding count, and a response size cap (cap 256 KB; when the page would exceed it the page is cut and says so). New small core function `CompatibilityReportJson.encodePage(report, limit, offset)` (pure; the same finding JSON as `encode`, no new fields). Findings never hold a hash (the model has none), guest and user rows say "password hash hidden" as in the report.
2. **`caerbannog_list_shovels`**. Input `{ "profile": string }` (a 4.x connection). Output: for every shovel name, vhost, state, `forwarded`, `remaining`, the scrubbed `reason` when terminated; NEVER an address, a URI or a credential. A user without the monitoring or administrator tag gets the fixed text "The user cannot read the shovel status and needs the monitoring tag". New read-only core interface `ShovelStatusReader { suspend fun list(): ShovelListOutcome }` (`Listed(List<ShovelStatus>)`, `NotPermitted`, `PluginsOff`, `Unreachable`, `Failed(reason)`), implemented in `data-rabbit` by a small class over the same `GET /api/shovels` mapping as `ManagementShovelGateway` (share the status mapping function; the path is already on `ShovelPathAllowList`). It is a SEPARATE interface: `ShovelGateway` (create, remove) never reaches `:mcp-server`.
3. Wiring: `McpServer` gets two optional providers (`definitionsSourceProvider: ((ConnectionProfile) -> DefinitionsSource)?`, `shovelStatusProvider: ((ConnectionProfile, String) -> ShovelStatusReader)?`) like the existing ones; `Main.kt` builds them from `data-rabbit` (`ManagementThreeXReads` behind `LiveDefinitionsSource`, the new status reader). Tools missing a provider are not listed (as today).
4. Safety guards (tests): the tool list contains exactly the existing eleven plus these two (13); no tool name contains create, remove, delete, apply, forward, convert, accept; a source scan asserts `mcp-server/src/main` does not mention `ShovelGateway`, `ForwardingService`, `ManagementShovelGateway` or `expose()`; a sentinel hash and a sentinel Shovel password in the fakes never appear in any tool output or error text; only read requests are sent to the 3.x profile (the fake records them).
Out: any create, remove, convert, forward or apply tool; the migration list and dry-run tools of M5-09 (separate, still open; their dry-run output of a `declareUser` shows no hash, M8-12a).

## Acceptance
- BDD `16`, tags `@m8-10 @mcp @fake-broker`, bound in the existing suite `RunMcpAcceptanceTest` (`McpSteps`; extend its feature selection to `16` for these tags): "The MCP report tool is read-only and returns no secrets", "No MCP tool returns a password hash", "No MCP tool can create or remove a shovel", "The MCP shovel status tool shows no address", and the new "The MCP report tool returns one page of findings and says how many there are".
- Unit: `McpServerTest` (tools list of 13, input validation: neither or both of `profile` and `file`, a relative path, a non-json file, a too large file, an unreadable profile, `limit` above 200 clamped), `encodePage` tests in core (page boundaries, offset past the end, deterministic order, the page and the full report agree), the status reader mapping in data-rabbit (the existing shovel mapping tests plus `PluginsOff` and `NotPermitted`).
- No live test (the gateway and reads are covered by `M8-08` and `M8-02b`); the gate `M8-11` does not use MCP.

## Design notes
- No new dependency: JSON is `kotlinx-serialization-json` (already in `:mcp-server`), tests use the existing `core-testing` fakes (`FakeThreeXReads`, `DefinitionsFiles`, `FakeShovelGateway` is NOT used; a fake `ShovelStatusReader` is added to `core-testing`).
- Errors are fixed texts; no exception message, path or body is echoed (a file path may be echoed only through the existing echo rule).
- The file tool reads any `.json` the process can read; its output is only the compatibility report (names from the file, no secrets) and any other content is refused with a fixed text, so it cannot be used to read a file.

## UX
None.

## MCP
This brief is the MCP part. Not exposed, with reasons: review and conversion (a human decision per item), Shovel create and remove (moves data, typed confirmation, credentials), forwarding, applying any migration.

## Owner decisions
None. (The `file` input was decided on 2026-11-09 and is safe because the output is only the report; if the owner prefers the lean variant, `profile` only, it is a deletion of one input.)

## Definition of done
Tests first and passing, no secret in any output, spec MCP table matches the code (13 tools).

## Status
- Step 1 (architect, 2026-11-11): made exact against main `1418028` plus M8-12b; two scenarios tagged and one added (paging) in `16`. Not started; independent of the gate.
