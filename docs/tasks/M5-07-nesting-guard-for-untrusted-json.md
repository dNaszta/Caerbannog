# Nesting guard for MigrationParser and other untrusted JSON entry points

Milestone: M5 (hardening)    Depends on: M8-01 (merged, brings `JsonNestingScan`)    Size: S

## Goal
No text that reaches the kotlinx JSON tree reader from outside the app can crash it with a `StackOverflowError`. Deeply nested input is rejected through the existing typed failure of each entry point, with a fixed message that does not echo the input. Run before M8-02 and M8-03 (they add more untrusted readers).

## Why
M8-01 measured that kotlinx-serialization 1.7.3 `parseToJsonElement` overflows the stack on about 10,000 nested brackets and that the usual `SerializationException` / `IllegalArgumentException` catches do not help (`StackOverflowError` is an `Error`). M8-01 fixed it with the iterative pre-scan `JsonNestingScan`. The audit below (grep on branch base c501ca8, main source sets) shows the same flaw in five more places, one of them in a shipped UI path (message bodies from the broker).

## Audit (state of c501ca8) and decisions

| # | File:line | Text parsed | Who controls it | Failure today | Decision | Limit |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `core/.../browser/PayloadFormatter.kt:118` (`formatJson`) | message body decoded as UTF-8 | any publisher to the stream (hostile possible) | catch is `Exception`, so `StackOverflowError` escapes and can take the UI thread down. Also `json.encodeToString(JsonElement)` at :122 recurses | GUARD (priority 1) | 128 |
| 2 | `core/.../migrations/MigrationParser.kt:51` | migration file the user imports | whoever made the file | `SerializationException`/`IllegalArgumentException` only: overflow escapes | GUARD | 64 |
| 3 | `data-rabbit/.../ManagementJson.kt:44` (`guarded`, used by every `parse*` of the stream, consumer, queue, MQTT and super-stream pages) | management API response body | the broker (broken or hostile broker in scope; queue arguments and policy definitions are user data echoed by the broker) | overflow escapes the three catches | GUARD in `guarded` (one place) | 128 |
| 4 | `data-rabbit/.../ManagementJson.kt:366` | bindings response body (MQTT subscriptions), parsed separately | the broker | overflow escapes | GUARD | 128 |
| 5 | `data-rabbit/.../ManagementErrors.kt:22` (`isNotManagementUser`) | 401 body | the broker | overflow escapes | GUARD; too deep means `false` | 64 |
| 6 | `data-rabbit/.../ManagementApiPermissionChecker.kt:89` | permissions response | the broker | overflow escapes | GUARD; too deep means `unreadable(BAD_RESPONSE, "malformed JSON")` | 64 |
| 7 | `data-rabbit/.../RabbitMqMessagePublisher.kt:144` | publish reply | the broker | catch is `Exception`: overflow escapes (message was already published, so the outcome would be lost) | GUARD; too deep keeps the existing `PublishOutcome.Success("Message published")` fallback | 64 |
| 8 | `mcp-server/.../McpServer.kt:361` (`decodeFromString<JsonRpcRequest>`, `params`/`id` are `JsonElement`) | one request line from the MCP client over stdio | any MCP client | catch is `Exception` at :425: overflow escapes `runBlocking` and kills the server process | GUARD; too deep gives JSON-RPC error `-32700` fixed message `Parse error` | 64 |
| 9 | `data-rabbit/.../JsonFileAppSettings.kt:49` | `settings.json` in the app folder | the user can hand-edit it | catch is `Exception`: overflow escapes at start-up | GUARD (cheap, same call shape); too deep means "unreadable, use defaults" as for corrupt JSON | 32 |
| 10 | `data-rabbit/.../JsonFileConnectionProfileRepository.kt:99` (`decodeFromString` into `@Serializable` DTOs, no `JsonElement`) | `profiles.json` | the app wrote it, the user can edit it | UNKNOWN: typed decoding skips unknown keys without the tree reader | PROBE, then decide: the test developer adds a 10,000-level probe test; if it passes today, OUT (reason: schema-typed, no tree), recorded in Status; if it overflows, guard like #9 | 32 |
| - | `core/.../migrate3x/DefinitionsParser.kt:48` | definitions file | whoever made it | already guarded (M8-01) | DONE, moves to the shared scan | 64 (unchanged) |
| - | `ManagementQueueMessageInspector`, `ManagementStreamCatalog`, `ManagementTopologyCatalog`, `ManagementMqttCatalog` | call `ManagementJson` | - | covered by #3 and #4 | no change | - |

Re-run `grep -rnE "parseToJsonElement|decodeFromString|JsonParser|Json\.parse"` (main source sets) at the start of the work; any hit not in this table gets a row and a decision in the Status block. Test sources and `build/` are ignored. Encoding (`encodeToString`) of trees the app built itself is out of scope.

### Limits and reasoning
The kotlinx reader overflowed at about 10,000 levels on the default stack; real stacks on coroutine or UI threads can be smaller, so every limit is set far below that and far above real data.
- 128 for broker data that may hold user-chosen structure (message bodies, queue arguments and policy definitions in listings): real JSON nests below about 20; 128 leaves room and stays about 80 times below the failure point.
- 64 for protocol-shaped text with a known shallow schema (migration files, permissions, errors, publish reply, MCP requests; the M8-01 definitions file already uses 64, valid ones reach about 37).
- 32 for the app's own small settings files.
Each limit is a named constant next to its parser (no magic numbers) with a one-line comment giving this reasoning.

## Scope
In:
1. **Move the scan** from `core/src/commonMain/kotlin/dev/caerbannog/core/migrate3x/JsonNestingScan.kt` to `core/src/commonMain/kotlin/dev/caerbannog/core/json/JsonNestingScan.kt`, package `dev.caerbannog.core.json`, now `public` (data-rabbit and mcp-server are other modules and call it). Behaviour is unchanged: iterative, honours quotes and escapes, `findTooDeep(text, maxDepth): TooDeep?` where `TooDeep(section: String?)` keeps the top-level-key detail for the definitions parser. The old signature without `maxDepth` goes away; `DefinitionsParser` passes its own `MAX_TEXT_DEPTH = 64`. `MAX_TEXT_DEPTH` stays where it is (package `migrate3x`, `internal`), because it is the definitions file's limit, not a global one.
2. **Shared entry point** in the same file or `UntrustedJson.kt` in `dev.caerbannog.core.json`:
   ```kotlin
   sealed interface UntrustedJson {
       data class Parsed(val element: JsonElement) : UntrustedJson
       data object TooDeep : UntrustedJson
       data object NotJson : UntrustedJson
   }
   fun parseUntrustedJson(text: String, maxDepth: Int, json: Json = Json): UntrustedJson
   ```
   It runs the scan, then `json.parseToJsonElement` with the narrow catch (`SerializationException`, `IllegalArgumentException`), never `Throwable`, never `StackOverflowError`. Call sites that need the tree (#1 to #7, #9) use it and keep their own mapping; #8 (MCP, typed decode) calls `JsonNestingScan.findTooDeep(line, 64)` first and only then `decodeFromString`. Sealed result, not a nullable, because #1 shows a different message for "too deep" and "not JSON". No new dependency (`core` keeps only the kotlinx-serialization-json 1.7.3 tree API; `data-rabbit` and `mcp-server` already depend on `core`: confirm in their build files, no build change expected).
3. **Per call site behaviour** (all messages fixed, nothing from the input echoed):
   - #1 `formatJson`: `JsonFormatResult.Invalid("The body is nested too deeply to format")` for too deep; the existing `Invalid` messages stay for not JSON and non-UTF-8. The Text and Hex views are independent of `formatJson` and keep working. Valid JSON up to the limit formats exactly as today.
   - #2 `MigrationParser.parse`: `MigrationValidationResult.Invalid("the file is nested too deep")`; not-JSON message unchanged.
   - #3, #4 `ManagementJson`: too deep is `CatalogResult.Unexpected` (the existing "unexpected response" result). For #4 a too-deep bindings body means "no subscription data", the same as the existing catch of a bad bindings body there (read it and keep its behaviour, do not widen).
   - #5 to #7, #9: as in the table.
   - #8 MCP: response `{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}`, process keeps running and answers the next request. The generic `-32603` path and its `e.message` echo stay as they are (out of scope, but do not route the too-deep case through it).
4. **Tests moved or edited for the refactor** (allowed refactor, not a weakening; assertions unchanged): the M8-01 tests that import or reference the scan or `MAX_TEXT_DEPTH` (`DefinitionsDepthLimitsTest`, `DefinitionsParserNestingTest`, and any test touching `JsonNestingScan`) change only their imports/call signature. They must stay green with the same assertions.
Out: new dependencies, changing the JSON library, catching `StackOverflowError`, changing any other message text, size limits on bodies (separate concern), the `-32603` echo of `e.message`, guarding `encodeToString` of app-built trees.

## Acceptance
- BDD (written by the architect in this step, all `@fake-broker` through the existing suites, step glue to be written by the test developer):
  - `tests/bdd/features/07_payload_viewer_filter.feature` / "A body nested ten thousand levels deep does not crash the viewer" (`@negative @fake-broker`; new steps: body of N nested JSON arrays, "the JSON view tells me the body is nested too deeply to format", the Hex view still shows the body, "the app is still running" which in the fake means the call returned a result instead of throwing).
  - `tests/bdd/features/10_topology_migrations.feature` / "Invalid migration files are rejected with a clear reason", new example row "is nested ten thousand levels deep" with the message "the file is nested too deep" (the step `I import a migration file that (.*)` needs one more branch).
  - `tests/bdd/features/14_mcp_server.feature` / "MCP server answers a request nested ten thousand levels deep with a parse error" (`@negative`, suite filter `@fake-broker` from the file tag): error code -32700, message `Parse error`, nothing of the request in the message, the next valid request is answered.
  - Management API responses (#3 to #7) and settings (#9) have no user-visible scenario of their own (internal robustness behind existing typed results); they are covered by unit tests only.
- Unit tests, each with a 10,000-level body (`"[".repeat(10_000) + "]".repeat(10_000)` and an object-nesting variant) and each asserting: the call returns the typed failure, does not throw (a `StackOverflowError` fails the JUnit test by itself, do not catch it in the test), the result text does not contain the input:
  1. `core` `jvmTest`: scan (moved): at the limit passes, one over fails, brackets inside strings and escapes do not count, 1 MB of `[` is found without recursion; `parseUntrustedJson` gives `Parsed`, `TooDeep`, `NotJson` for the three cases.
  2. `PayloadFormatterTest` (#1): 10,000 levels gives `Invalid("The body is nested too deeply to format")`; 128 levels still `Valid`; 129 `Invalid`; ordinary JSON output byte-identical to today (existing tests stay green unchanged).
  3. `MigrationParser` test (#2): 10,000 levels gives `Invalid("the file is nested too deep")`; a real migration file still `Valid`.
  4. `data-rabbit` tests: `ManagementJson` with one representative parser per shape (stream page, queue page, MQTT clients with a deep connections body AND a deep bindings body) gives `Unexpected`; `isNotManagementUser` false; permission checker `BAD_RESPONSE`; publisher keeps `Success("Message published")` (fake HTTP reply with a deep body); settings file with a deep body gives defaults and does not throw; the profile repository probe (#10) with its decision recorded.
  5. `mcp-server` test: `handleRequest` with a 10,000-level `params` returns the `-32700` error and a following `ping` is answered.
  6. Existing behaviour unchanged for valid JSON: the full existing suites of the touched classes stay green with no edited assertion.
- Mutation checks recorded in the Status block: (a) remove the guard from #1, the PayloadFormatter unit test and the 07 scenario go red (by `StackOverflowError`); (b) remove it from #8, the MCP test goes red; (c) raise a limit above the tested boundary, the boundary test goes red.
- `verifyTestDiscovery` green; full suite once at the end.

## Design notes
Single shared iterative scan, no recursion in the guard. `core/json` has no JVM-only types. UI code never calls the scan; guards sit in the parser/formatter in `core` and in the `data-rabbit`/`mcp-server` call sites. Where a site already maps parse failures to a typed result, add the third case there, no new result types except `UntrustedJson`. The guard runs on the text as received; for #1 on the trimmed text that is parsed.

## UX
No new screen. The JSON view of the payload viewer shows the new fixed warning text instead of the formatted body (same place and style as the existing "not valid JSON" text, `docs/ux/` viewer screen); Text and Hex tabs unchanged.

## MCP
No new tool. Hardening of request decoding only (row #8); safety rules of `14_mcp_server.feature` unchanged.

## Open points
None that need a product decision. The #10 probe decides guard or out. The limits (128/64/32) are engineering choices with the reasoning above; change them only with a test.

## Definition of done
Tests written first and failing for the right reason (`StackOverflowError`, or the wrong result), then passing; audit table in this brief updated at the end with the final decision per call (including #10); no unrelated changes; M8-01 tests green with unchanged assertions; `tests/bdd/README.md` untouched (no new tag).

## Status
- Step 1 (architect, brief and scenarios): DONE 2026-11-09. Round 0. Branch `m5-07-nesting-guard-for-untrusted-json` (worktree `.claude/worktrees/m5-07`, base c501ca8). Audit re-run on the base; table above is the current state (10 call sites plus the already guarded definitions parser). Scenarios written: 07 (new), 10 (new example row), 14 (new). No product decision.
