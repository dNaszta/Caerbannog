# MCP tools: 3.x compatibility report and shovel status

Milestone: M8    Depends on: M8-03, M8-08    Size: S

## Goal
Agents can request the compatibility report and read shovel status, and cannot create, remove or convert anything.

## Why
Architectural rule (spec "Model Context Protocol (MCP) server & architectural rule", M8 MCP evaluation).

## Scope
In: `caerbannog_analyze_3x_definitions`: input `{ "profile": string }` or `{ "file": string }` (exactly one; `file` must be a readable `.json` file up to a size cap), optional `"vhost"`; output the report JSON `caerbannog.compat-report/1`. `caerbannog_list_shovels`: input `{ "profile": string }`; output name, vhost, state, counts, never a URI. Both read-only, GET only, no password or hash in any field or error text.
Out: any create, remove, convert or apply tool. No MCP tool may return a password hash: report and any tool that returns migration or definitions content redact it; a migration apply tool, if it exists, refuses migrations that hold vhost, user or permission actions.

## Acceptance
- BDD: `16` / "The MCP report tool is read-only and returns no secrets", "No MCP tool can create or remove a shovel", "The MCP shovel status tool shows no address".
- Unit: tool list contains exactly the existing eleven plus these two (adjust if the migration tools of M5-04 have been added by then).

## Design notes
Reuse the stdio server patterns from `M-MCP-01`. The server's profile access stays as it is.

## UX
None.

## MCP
This brief is the MCP part. Not exposed, with reasons: review and conversion (human decision per item); shovel create and remove (moves data, typed confirmation, credentials).

## Open points
Note for the owner: the M5-04 brief names three migration MCP tools that `:mcp-server` does not register; reconcile separately.

## Definition of done
Tests first and passing, no secret in output, spec MCP table matches the code.
