# Model Context Protocol (MCP) stdio server for Caerbannog

Milestone: Tooling / M2 Extension    Depends on: M1-02, M2-03, M2-08a    Size: M

## Goal

A headless subproject `:mcp-server` that implements an MCP (Model Context Protocol) server communicating over `stdio`, exposing Caerbannog's connection profiles, stream inspection, and topology data as typed AI tools for Antigravity, Claude Code, and other AI agents.

## Why

Enables AI developer agents and assistants to inspect RabbitMQ streams and quorum queue topology directly during testing, debugging, and development, while strictly enforcing Caerbannog's safety guardrails (no queue message reading, no plaintext outside the profile TLS switch, secrets never exposed).

## Scope

In:
- New Gradle subproject `:mcp-server` with `application` plugin (`dev.caerbannog.mcp.MainKt`).
- Stdio transport: JSON-RPC 2.0 / MCP over standard input and standard output. Diagnostic and debug logs sent strictly to `System.err` so `System.out` is never corrupted.
- Standard MCP protocol handshake:
  - `initialize` (declares protocol version `2024-11-05`, server info `caerbannog-mcp`, capabilities `tools`).
  - `notifications/initialized` (acknowledgment).
  - `ping` (heartbeat response `{}`).
- Tools exposed:
  1. `caerbannog_list_profiles`: List configured connection profiles (name, host, vhost, useTls, topologyControlLevel, declaredPermissions; never passwords or secret tokens).
  2. `caerbannog_check_connection`: Run reachability and permission check on a specified profile name, returning the reachability state, visibility level, and user tags.
  3. `caerbannog_list_streams`: List streams for a profile and virtual host (stream name, messages count, first/last offset, retention).
  4. `caerbannog_get_stream_details`: Detailed statistics for a stream (node, leader, replica count, retention, message count).
  5. `caerbannog_list_queues`: List queues in a vhost (name, type, messages count, leader, state).
  6. `caerbannog_list_exchanges`: List exchanges in a vhost (name, type, durable, internal).
  7. `caerbannog_list_bindings`: List bindings in a vhost (source exchange, destination, destination type, routing key).
- Safety enforcement:
  - Strict prohibition: no queue message read, get, or purge tool exists.
  - Ephemeral and read-only: stream and topology calls use existing `:core` catalogs without mutating broker state.
  - Secrets safety: Passwords and tokens from the keychain are in-memory only and never returned in tool responses.
- Antigravity & Claude configuration:
  - Ready-to-use configuration snippet for `~/.gemini/config/mcp_config.json` and `.claude.json`.

Out:
- Queue message reading or purging (strictly forbidden by Caerbannog architecture).
- Topology mutations (creating/deleting queues, exchanges, bindings) — deferred to M5.
- Publishing test messages — deferred to M5.
- Streamable HTTP or SSE remote transport (stdio transport is the priority; remote HTTP can be added in a future follow-up).

## Acceptance

- BDD feature: `tests/bdd/features/14_mcp_server.feature` with scenarios:
  - "MCP server initialize handshake and capabilities"
  - "MCP server lists available tools and never offers queue message reading"
  - "MCP server lists configured connection profiles without revealing secrets"
  - "MCP server checks a connection profile"
  - "MCP server lists streams for a profile"
  - "MCP server lists queues and exchanges for a profile"
  - "MCP server handles unknown tool or missing profile with a structured error"
- Unit and integration tests in `:mcp-server` testing:
  - JSON-RPC serialization, deserialization, and request routing.
  - Stdio runner with fakes from `:core-testing`.
  - Tool output formats and error conversions.
- Stdout hygiene: zero log or debug lines leak onto `System.out`.

## Design notes

- Transport & JSON-RPC:
  - Uses `kotlinx.serialization` for typed MCP request/response/tool schemas matching the MCP 2024-11-05 spec.
  - Implements stdio line-delimited JSON-RPC framing (or `io.modelcontextprotocol:kotlin-sdk-server-jvm`).
- Module structure:
  - Root `settings.gradle.kts` adds `:mcp-server`.
  - `:mcp-server/build.gradle.kts` depends on `:core` and `:data-rabbit`, with test dependency on `:core-testing`.
- Execution:
  - `./gradlew :mcp-server:run --quiet` runs the server.

## UX

CLI / Headless only. No UI impact on Compose desktop application.

## Open points

None.

## Definition of done

- Subproject `:mcp-server` compiles and runs via Gradle.
- Feature scenarios in `14_mcp_server.feature` pass.
- Unit and integration tests pass.
- Verification and architect review completed.
- Status and README row updated.

## Status

Step 1: brief written by solution-architect (2026-11-06). Ready for step 2 (test-developer).
Branch: `m-mcp-01-stdio-server`
Round: 0
Verdict: in progress
