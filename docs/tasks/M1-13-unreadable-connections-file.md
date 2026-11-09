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
Owner decision 2026-11-09 (option B) fixes the behaviour. The architect copies the scenarios below into `tests/bdd/features/01_connections.feature` and `14_mcp_server.feature` at step 1 of this task (tags in the headings; `@fake-broker`). Fixed texts: reason "The file is not valid JSON" or "The file is nested too deep"; line "Your file has not been changed"; confirmation "Move connections.json to connections.json.unreadable-<timestamp> and start with an empty list? The old file is kept." where `<timestamp>` is the real name chosen at that moment (format `yyyyMMdd-HHmmss`, local time from an injected clock; if that name exists, `-2`, `-3`, and so on). Buttons: "Try again", "Show folder", "Start with an empty list" (opens the confirmation; the confirmation has "Move file and start" and "Cancel").

```gherkin
@negative @fake-broker
Scenario: The app starts with an unreadable connections file and says so
  Given the connections file contains text that is not valid JSON
  When I start the app
  Then the connections screen shows the path of the file and the reason "The file is not valid JSON"
  And it says "Your file has not been changed"
  And "Add connection" and "Connect" are disabled
  And the connections file still has exactly its original bytes

@negative @fake-broker
Scenario: A connections file nested too deep is treated like a corrupt one
  Given the connections file nests arrays ten thousand levels deep
  When I start the app
  Then the connections screen shows the reason "The file is nested too deep"
  And the connections file still has exactly its original bytes

@fake-broker
Scenario: Try again reads the file again
  Given the app shows that the connections file is unreadable
  And I fix the connections file so that it holds the profile "uat-eu"
  When I choose "Try again"
  Then the profile "uat-eu" is listed and the message is gone

@fake-broker
Scenario: Try again with the file still unreadable changes nothing
  Given the app shows that the connections file is unreadable
  When I choose "Try again"
  Then the same message is shown
  And the connections file still has exactly its original bytes

@fake-broker
Scenario: Show folder opens the folder of the file
  Given the app shows that the connections file is unreadable
  When I choose "Show folder"
  Then the folder that holds the connections file is opened
  And the connections file still has exactly its original bytes

@fake-broker
Scenario: Start with an empty list moves the file aside after a confirmation
  Given the app shows that the connections file is unreadable
  And the clock says 2026-11-09 14:30:05
  When I choose "Start with an empty list"
  Then I am asked "Move connections.json to connections.json.unreadable-20261109-143005 and start with an empty list? The old file is kept."
  When I confirm
  Then the file "connections.json.unreadable-20261109-143005" holds the original bytes
  And the connection list is empty and I can add a connection
  And nothing was deleted

@fake-broker
Scenario: Cancelling the confirmation changes nothing
  Given the app shows that the connections file is unreadable
  When I choose "Start with an empty list"
  And I cancel
  Then the connections file still has exactly its original bytes
  And the unreadable message is still shown

@negative @fake-broker
Scenario: The moved-aside name is never reused
  Given the app shows that the connections file is unreadable
  And a file "connections.json.unreadable-20261109-143005" already exists
  And the clock says 2026-11-09 14:30:05
  When I choose "Start with an empty list" and confirm
  Then the old file is moved to "connections.json.unreadable-20261109-143005-2"
  And the existing file "connections.json.unreadable-20261109-143005" is unchanged

@negative @fake-broker
Scenario: The file is never overwritten without that choice
  Given the app shows that the connections file is unreadable
  Then no way in the app adds, edits or deletes a connection until I try again successfully or move the file aside
  And the connections file still has exactly its original bytes

# in 14_mcp_server.feature
@negative
Scenario: MCP server reports an unreadable profiles file as a tool error
  Given the profiles file contains text that is not valid JSON and holds the word "SENTINEL-FILE-TEXT"
  When the client calls MCP tool "caerbannog_list_profiles"
  Then the tool result is an error with the text "The connection profiles file could not be read: The file is not valid JSON"
  And the result and the server's log contain no part of the file, not "SENTINEL-FILE-TEXT"
  And the response is not a JSON-RPC internal error
  And the profiles file still has exactly its original bytes
  And the server answers the next request
```
The same tool-error scenario holds for every tool that resolves a profile (one parameterised example row per tool, or a unit test over the tool list). No MCP tool moves, repairs or deletes the file.
Unit tests: JSON repository `load()` for corrupt, too deep, empty (is `Loaded(empty)`), missing (is `Loaded(empty)`) and valid files; `moveAside()` (name format, collision suffix, original bytes preserved, original path gone, no deletion, failure to rename gives a fixed-text error and leaves the file); view model transitions (Unavailable, retry success, retry failure, confirm, cancel); no secret or file content in any message, log or result. Mutation checks: view model rethrows (start-up scenario red); `moveAside` copies and deletes instead of renaming atomically is allowed only if the data survives; make it delete instead of move (data scenario red).

## Design notes
UI free of file code: the view model gets the typed result from the repository interface; the rename action is `moveAside(): MoveAsideResult` on a small interface (repository or file service) with a fake; it uses an atomic move, never a delete, and takes the timestamp from an injected clock. `Show folder` is a desktop-only port (`FolderOpener`) with a fake. Sealed state, no boolean flags. The reason texts are fixed constants.

## UX
`docs/ux/Main.dc.html` (Connections) has NO unreadable state, so the mockup needs a NEW state. This is step-1 work for this task (the architect draws it before the build; not written yet): an empty-state panel or error banner on the Connections screen with the file path, the fixed reason, the line "Your file has not been changed", the buttons "Try again", "Show folder", "Start with an empty list", and the confirmation dialog with the exact text above and "Move file and start" / "Cancel". Material 3; states: unreadable (new), empty, loaded; add and connect disabled in the unreadable state.

## MCP
No new tool. Existing profile tools change their failure answer only (see Scope 4). No MCP action repairs, moves or deletes the file.

## Open points / product decision
DECIDED by the owner on 2026-11-09: option B (message with path and fixed reason, "Try again", "Show folder", and an explicit "Start with an empty list" that renames the file to `connections.json.unreadable-<timestamp>`, never deletes it, after a confirmation that names the new file). Options A (message only) and C (in-app repair) were not chosen. No open product question remains. Open engineering detail: the timestamp format `yyyyMMdd-HHmmss` and the `-2` collision suffix are the architect's choice here, change only with a test.

## Definition of done
Tests first and passing, scenarios pass, file never changed without an explicit user action, no unrelated changes, `docs/spec.md` connection profiles section updated with the decision (step 1 of the task).

## Status
- Step 1a (architect, brief): DONE 2026-11-09.
- Owner decision 2026-11-09: option B. Recorded above; scenarios made exact; no wait left. The brief is ready for step 1b: the architect writes the scenarios into the feature files, adds the unreadable state to `docs/ux/Main.dc.html` and the spec line, then tests first.
- Not started (code).
