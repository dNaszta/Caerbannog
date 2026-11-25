# M10-08: Read-only MCP tools for the Access view

Milestone: M10    Depends on: M10-02 (summary), M10-04 (check), M10-06 and M10-07 (clients); each tool lands after its slice    Size: M

## Goal
`:mcp-server` offers `caerbannog_get_access_summary`, `caerbannog_check_access` and `caerbannog_list_clients`, read only, through the same gateway, matcher and `SecretGuard` as the screen, and no tool that changes access, closes a connection, reads a queue message or touches a Shovel.

## Why
Spec "Access view (M10)", "MCP evaluation" (decision of 2026-11-25, owner confirmed AQ9 on 2026-11-25), "Model Context Protocol (MCP) server & architectural rule". Safety: no secret leakage, no changes.

## Scope
In: the three tools with the schemas of the spec table; bounds (`limit` default 200 rows, maximum 500, `omittedRows` says how many were left out; a documented cap on summary size); every output through `SecretGuard` with `hiddenValues`; `check_access` rejects an action outside `publish`, `read`, `configure` with the list of allowed actions; the profile's TLS setting as saved, "not encrypted" reported for a plain profile, no fallback; a tool that lacks rights returns `{readable: false, reason}` and tries nothing else; broker error bodies are never passed through; the MCP initialize version stays as decided by the owner (not part of this task).
Out: any tool for part 3 beyond the sign-in method in the summary; any write tool; a tool listing raw connections.

## Acceptance
- BDD (`25_access_mcp.feature`, tag `@m10-08`): all scenarios, among them "The tools list has the three Access tools and no tool that changes access" (@smoke), "The summary names the user, tags, virtual hosts and sign-in method", "Permissions are returned only when the user may read them", "Permissions of an administrator are returned with their source", "The access check answers as a prediction", "The access check rejects an unknown action", "The client list is grouped by user and bounded", "No tool output contains a token, a password or a secret-looking value" (outline over the three tools), "A profile without TLS is used as saved and reported as not encrypted", "A broker error text is not passed on if it carries a secret"; "The client list for one queue or stream shows publishers and consumers" is `@needs-spike` until S-G.
- The existing tool-list scenarios of `14_mcp_server.feature` still pass (no queue message tool, no Shovel create or remove tool); the `14` scenario that lists forbidden tools is extended by the architect with the new forbidden names.
- Unit: schema tests, the bound tests, the hostile-value test for every tool.

## Design notes
`mcp-server` depends on `core` and the `data-rabbit` gateway already used by the other tools; the guard and the matcher live in `core`, so the server adds no redaction code of its own. Same registration style as `caerbannog_list_shovels` and `caerbannog_list_mqtt_clients`.

## UX
None (headless).

## MCP
This task. Tool names, inputs and outputs: `docs/spec.md` "Access view (M10)", "MCP evaluation".

## Open points
The default `limit`. (AQ9 is decided.) Changed by M10-00: `caerbannog_get_access_summary` reports the sign-in as `{tokenUser: true|false, oauth2PluginLoaded: true|false|unknown, note}` and never "oauth2 backend in use"; for a token user `permissions` is `{readable: false, reason: "token user: permissions come from the token"}`. `caerbannog_list_clients` returns queue publishers only as `{exchange, user}` when the broker has detailed rates and says `publishersAvailable: false` otherwise; it carries `sharedNameWarning` for users such as `unknown`. Every free-text field (including `user_provided_name`) passes `SecretGuard`. Facts: `docs/tasks/M10-00-spike-results.md`, spec "Measured facts of M10-00".

## Definition of done
Tests first and passing; `docs/spec.md` MCP section and README updated if names change.
