# Nesting guard for MigrationParser and other untrusted JSON entry points

Milestone: M5 (hardening)    Depends on: M8-01 merged (it brings `JsonNestingScan`)    Size: S

## Goal
No text that reaches `Json.parseToJsonElement` or `decodeFromString` from outside the app can crash the app with a `StackOverflowError`: deeply nested input is rejected through the existing typed error of each entry point. Run this before M8-02 and M8-03 (they add more untrusted JSON readers).

## Why
M8-01 measured that kotlinx-serialization 1.7.3 `parseToJsonElement` overflows the stack on about 10,000 nested brackets, and that `SerializationException` and `IllegalArgumentException` catches do not help (`StackOverflowError` is an `Error`). The M8-01 fix is an iterative pre-scan `JsonNestingScan` (`core`, package `dev.caerbannog.core.migrate3x`, `internal`). The same flaw is probably present elsewhere. Spec: "TDD and BDD are requirements" and the untrusted-input stance of M8-01.

## Scope
In:
1. Make the scan shareable: move it to a neutral `core` package (for example `dev.caerbannog.core.json`), keep it iterative and `internal` to `core` unless a module needs it (then public, small API: `JsonNestingScan.exceeds(text, maxDepth)`); keep the M8-01 "top-level key" detail as a separate function or a result field so `DefinitionsParser` behaviour and its tests do not change.
2. Audit every call of `parseToJsonElement`, `Json.parse...` and `decodeFromString` on text not produced by the app, and guard each with a depth limit chosen for its schema (state the limit and why in a comment). Starting list from a grep on 2026-11-09 (re-run the grep, do not trust this list):
   - `core/.../migrations/MigrationParser.kt:51` (user-chosen migration file; limit small, the format is shallow).
   - `core/.../browser/PayloadFormatter.kt:118` `formatJson` (message bodies from the broker; it catches `Exception`, so a `StackOverflowError` would escape and take the UI down. Highest priority). Result: `JsonFormatResult.Invalid` with the existing message, and the raw view stays available.
   - `data-rabbit`: `ManagementJson.kt:44` and `:366`, `ManagementErrors.kt:22`, `ManagementApiPermissionChecker.kt:89`, `RabbitMqMessagePublisher.kt:144` (broker responses; a hostile or broken broker is in scope), the typed result of each is the existing "unexpected response" case.
   - `mcp-server/.../McpServer.kt:361` (`decodeFromString<JsonRpcRequest>` from the MCP client; a deep `params` must give a JSON-RPC parse error, not a crash).
   - `JsonFileAppSettings.kt:49` and `JsonFileConnectionProfileRepository.kt:99` (local files the app wrote; guard only if cheap, otherwise record "own file, not guarded" in the Status block).
3. A deep-nesting test for each guarded entry point (10,000 levels of `[`, and exactly at and over the limit where the limit is meaningful), asserting the typed rejection and no `StackOverflowError`.
Out: new dependencies, changing the JSON library, catching `StackOverflowError`, changing any message text, a size limit on bodies (separate concern).

## Acceptance
- BDD: none new needed for most entry points (internal robustness). For `PayloadFormatter` add one `@fake-broker` scenario in the payload viewer feature in the existing style: "A message body nested ten thousand levels deep shows as not valid JSON and the app stays responsive" (the architect writes it in `tests/bdd/features/` at step 1 of this task if the feature file for M4 has no fit).
- Unit tests: the per-entry-point deep-nesting tests above; the existing `DefinitionsParserNestingTest` and `DefinitionsDepthLimitsTest` stay green unchanged.
- Mutation check recorded: remove the guard from one entry point, its test goes red.

## Design notes
Single shared iterative scan; no recursion anywhere in the guard. Brackets inside strings and escapes are honoured (as in M8-01). Limits are named constants next to each parser. UI code stays free of the scan; the guard sits in the parser or formatter in `core` or in the `data-rabbit` JSON helper.

## UX
None. Existing error states are reused.

## MCP
None for new tools. The MCP server request decoding is hardened only (see scope item 2).

## Open points
Whether a hostile MCP client over stdio is in the threat model (it is cheap to guard, so guard it). Local settings files are the app's own output: guard only if trivial.

## Definition of done
Tests written first and failing for the right reason (`StackOverflowError`), then passing; audit list with decision per call recorded in the Status block; no unrelated changes; spec untouched unless a limit becomes a documented rule.

## Status
- Step 1 (architect, brief): DONE 2026-11-09. Not started.
