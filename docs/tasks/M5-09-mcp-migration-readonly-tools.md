# M5-09: MCP tools to list and dry-run migrations (read-only)

Milestone: M5    Depends on: M5-08b    Size: S

## Goal
An MCP client can list the migrations of the user's migrations folder with their status for a connection profile, and see the dry run of one migration, without being able to apply, change or delete anything.

## Why
Owner decision 2026-11-09: MCP is read-only for migrations; apply stays a human action in the app. This replaces the three tools of the M5-04 brief (`caerbannog_list_migrations`, `caerbannog_dry_run_migration`, `caerbannog_apply_migrations`) by the first two. Spec "MCP evaluation".

## Scope
In (`:mcp-server`):
1. `caerbannog_list_migrations` (input `profile` (required)): output the migrations of the folder remembered in `settings.json`: version, description, status for that profile (`pending`, `applied`, `modified`), the action count; invalid files are listed as `invalid` with the fixed reason; no file content, no raw JSON, no argument values.
2. `caerbannog_dry_run_migration` (input `profile`, `version`): output the dry-run rows (`description` of each action, `allowed`, the fixed reason when blocked) using the profile's self-constraint level and virtual host and the declared permissions; `Declare user <name>` and nothing else for access actions (no hash, ever).
3. Both read the folder through `MigrationFolderStore` and the file repository, and the history through `MigrationHistoryRepository`, and write nothing; when no folder is chosen, the folder is missing or unreadable, or the history is unreadable, the tool answers a structured error with the fixed text (never file content, never an exception message). There is NO apply tool and no tool that writes, moves or deletes a file; a tool-list test asserts this.
Out: apply, import, export, edit (human in the app), any broker call.

## Acceptance
- BDD (`14_mcp_server.feature`, `@fake-broker`, written by the architect at step 1b of this task): "MCP server lists the migrations of the chosen folder", "MCP server dry-runs one migration without a hash", "MCP server reports an unchosen or unreadable migrations folder as a tool error", "No MCP tool applies or changes a migration"; scenario "No MCP tool applies a migration, so access actions can only be applied in the app" in `18` (already changed) runs here.
- Unit tests (`mcp-server`): tool schemas, list and dry-run on temp folders, the status per profile, the invalid file row, no raw JSON or argument value in any output (sentinel), the structured errors, the tool-list guard (no name containing `apply`, `delete`, `import`, `export`, `write`), the server never opens the history for writing.
- Mutation checks: (a) add an apply tool: the guard goes red; (b) put the raw JSON in the list output: the sentinel test goes red; (c) include a hash field: the access-action test goes red.

## Design notes
No new dependency; reads through the `M5-08b` ports and adapters (`data-rabbit`), the MCP server is a separate process that reads the same `settings.json` and history file read-only.

## UX
None.

## MCP
This is the MCP task: two read-only tools, schemas above; safety: no secrets, no writes, no broker calls, structured fixed errors.

## Open points
From `M5-08a` (final review, 2026-11-10): the dry run reasons now include "The action belongs to virtual host <v>; this connection is for <c>" (the name only when `echoableName` allows it); the MCP dry-run tool uses the same `MigrationRunner.dryRun(migration, hasConfigurePermission, controlLevel, vhost)` with the profile's virtual host (required parameter) and must not echo a name that `echoableName` refuses.
The MCP process and the app may run at the same time: both only read the history here, the app writes it atomically, so a torn read is not possible; a missing or briefly renamed file gives the structured error.

## Definition of done
Tests first and passing, the tool list has exactly the documented migration tools, no unrelated change, spec MCP table matches.

## Status
- Step 1 (architect): DONE 2026-11-09. Not started.
