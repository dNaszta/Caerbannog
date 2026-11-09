# Unreadable connections.json at start-up

Milestone: M1 (hardening of connection profiles; follow-up of M5-07)    Depends on: M5-07 (merged)    Size: M

## Goal
When `~/.caerbannog/connections.json` cannot be read (corrupt JSON, or nested too deep as M5-07 now detects), the desktop app starts anyway and shows a clear message with the file path and a way to keep the file; the file is never overwritten or deleted without an explicit user choice. The MCP server answers its profile tools with a fixed, structured error instead of an internal-error echo.

## Why
M5-07 made a too-deep file behave exactly like a corrupt one: `JsonFileConnectionProfileRepository.list()` throws `SerializationException`, and `add`, `update`, `delete` throw too, so nothing is overwritten (good). Nobody handles that exception, so the user sees a crash or an internal error (finding below). Spec: connection profiles are the user's data; a failure must not lose it. Task id note: numbered M1-13 (M1-08 is the keychain test opt-in) because the file belongs to connection profiles (M1-02).

## Finding (by reading the code at base c501ca8 plus M5-07; not run)
- Desktop: `ConnectionsViewModel` (core, `ConnectionsViewModel.kt:29`) calls `repository.list()` in its property initialiser. `createAppComposition()` (`CompositionRoot.kt`) builds it, and `main()` calls that inside `remember { ... }` in `application {}` (`Main.kt:66`). No `try/catch` around it and no uncaught-exception handler in `desktopApp`. Result: the exception escapes during composition and the app dies at start-up with a stack trace and no window or message. The file is untouched. Many later handlers also call `repository.list()` (`ConnectionsViewModel.kt` lines 160 to 333), so a file that becomes unreadable while the app runs throws inside an event handler.
- MCP server: `Main.kt` builds the repository without reading it, so it starts. Every profile tool calls `profileRepository.list()` inside `handleParsedRequest`, whose `catch (e: Exception)` answers JSON-RPC `-32603` with `"Internal error: ${e.message}"` and logs `e.message` to stderr. No crash, but the message echoes the exception text; for a corrupt file the kotlinx message can contain a snippet of the file (profile data: host names and user names, no passwords, since the file holds none).
- Silent empty list: does not happen anywhere (good, and M5-07 tests pin it).

## Scope
In:
1. `core`: a typed way to ask the repository for the list without exceptions, for example `ConnectionProfileRepository.load(): ProfilesLoadResult` = `Loaded(profiles)` | `Unreadable(reason: String)` where `reason` is a fixed text ("The file is not valid JSON" / "The file is nested too deep") and never file content; `list()` stays for existing callers or is implemented on top of it. The JSON repository maps `SerializationException` and read errors to `Unreadable`; fakes implement the new method.
2. `ConnectionsViewModel` starts in a state `ProfilesUnavailable(path, reason)` instead of throwing; in that state add, edit, delete, connect are disabled. Event handlers that call `list()` later get the same state instead of an exception.
3. `desktopApp`: the Connections screen shows the message (path, fixed reason, "Your file has not been changed") and the actions chosen by the product decision below.
4. `mcp-server`: `caerbannog_list_profiles` and every tool that resolves a profile return `isError = true` with the fixed text "The connection profiles file could not be read" (plus the fixed reason) as a tool result, not `-32603`; nothing from the file or the exception message is echoed; the stderr log line is fixed text too.
Out: repairing the JSON, a backup scheme, a settings.json equivalent (it already falls back to defaults and the next write replaces it by design), changing the file format.

## Acceptance
Scenarios to be written by the architect at step 1 of this task (names proposed), in `tests/bdd/features/01_connections.feature` (`@fake-broker`) and `14_mcp_server.feature`:
- "The app starts with an unreadable connections file and says so" (@negative): window state shows the path and reason, add and connect are disabled, the file bytes are unchanged.
- "A connections file nested too deep is treated like a corrupt one" (@negative).
- "Keeping the unreadable file" (per the decision): the offered actions behave as chosen; with "start fresh" the old file is renamed, not deleted, and the new list is empty and writable.
- "MCP server reports an unreadable profiles file as a tool error" (@negative): `isError` true, fixed text, no file content in the result or in stderr, the server keeps running.
Unit tests: JSON repository `load()` for corrupt, too deep, empty, missing and valid files; view model state transitions; no secret or file content in any message. Mutation check: make the view model rethrow, the start-up test goes red.

## Design notes
UI free of file code: the view model gets the typed result from the repository interface; the rename action (if chosen) is a repository or file-service method behind an interface with a fake. Sealed state, no boolean flags. The reason texts are fixed constants.

## UX
`docs/ux/Main.dc.html` (Connections) has no unreadable state. Step 1 of this task adds a mockup state: banner or empty-state panel with path, reason, buttons. Material 3, states: unreadable (new), empty, loaded.

## MCP
No new tool. Existing profile tools change their failure answer only (see Scope 4). No MCP action repairs, moves or deletes the file.

## Open points / product decision (needs the owner)
What may the app offer when the file is unreadable?
- Option A: message only (path, reason, "file not changed"), plus "Try again" and "Show folder". The user fixes the file by hand. Safest, least work; the app is unusable for profiles until then.
- Option B (recommended): A plus an explicit "Start with an empty list" button that RENAMES the file to `connections.json.unreadable-<timestamp>` (never deletes it), after a confirmation naming the new file name; then the app works and the user can recover the old file by hand. Gives a way out without data loss.
- Option C: also an in-app JSON repair. Out of proportion; not recommended.
Blocked until decided: the actions in Scope 3, the scenario "Keeping the unreadable file" and the UX state. Scope 1, 2 and 4 are not blocked.

## Definition of done
Tests first and passing, scenarios pass, file never changed without an explicit user action, no unrelated changes, spec section on connection profiles updated with the decision.

## Status
- Step 1 (architect, brief): DONE 2026-11-09. Not started. Waiting for the owner's choice among A, B, C (recommend B).
