# M4-01: Payload viewer core, formatting, and BDD scenarios

Milestone: M4    Depends on: M3-04 (merged)    Size: M    Branch: `m4-01-payload-viewer-core-and-formatting`

## Goal
Implement the core domain logic, formatters, and state model for inspecting stream message payloads and metadata in Milestone M4 (Payload Viewer and Filter):
1. Payload formatting and detection (`Text`, `Json`, `Hex`):
   - Reliable UTF-8 decoding with binary detection (invalid UTF-8 or unprintable ISO control characters fall back to Hex, avoiding garbled characters).
   - JSON validation and pretty-printing (indented, one field per line).
   - Invalid JSON detection when payload is malformed or invalid, displaying an error notice while keeping the Text view readable.
   - Byte-level Hex dump formatting.
2. Large body display capping:
   - Default display cap (e.g. 64 KB / 65,536 characters).
   - Truncated preview with an explicit "Show all" expansion state.
3. Properties and application properties presentation model:
   - Extract standard AMQP/Stream properties (e.g. `content-type`, `message-id`).
   - Extract user-defined application properties (labeled `<key> (app property)`).
4. Copy model:
   - Exact published body copy.
   - Offset copy.
5. Pass the 7 payload viewer BDD scenarios in `tests/bdd/features/07_payload_viewer_filter.feature` against fakes.

## Scope
### In
- **Core module (`core`):**
  - `PayloadViewKind`: enum or sealed type (`TEXT`, `JSON`, `HEX`).
  - `PayloadInspection`: model holding formatted text, selected view kind, available views, validation warnings (e.g. invalid JSON), whether content is capped, and full vs capped content.
  - `PayloadFormatter`:
    - `isBinary(payload: ByteArray): Boolean`
    - `formatText(payload: ByteArray): String?`
    - `formatJson(payload: ByteArray): JsonFormatResult` (Success with pretty printed JSON vs InvalidJson with error message)
    - `formatHex(payload: ByteArray): String`
    - `inspect(message: StreamMessage, requestedKind: PayloadViewKind? = null, capLimit: Int = DEFAULT_CAP_LIMIT, showAll: Boolean = false): PayloadInspection`
- **Acceptance tests (`acceptance-tests`):**
  - `RunPayloadViewerAcceptanceTest` suite filtering `@viewer and @fake-broker`.
  - `PayloadViewerSteps` implementing Gherkin steps for:
    - Pretty JSON body display
    - Text and Hex views
    - Binary body fallback to Hex
    - Invalid JSON warning with Text view fallback
    - Display capping and "Show all" action
    - Properties and application properties inspection
    - Copy offset and copy body buttons

### Out
- Client-side scan filter engine (task M4-02).
- Desktop Compose UI detail side sheet composables (task M4-03).
- Real broker responsiveness gate `@gate-M4` (task M4-04).

## MCP Evaluation (docs/spec.md:100)
- MCP tool `caerbannog_browse_stream` in `mcp-server` already returns message payloads.
- We will update `mcp-server`'s payload formatting to reuse the core `PayloadFormatter` so agents receive structured, properly formatted payloads (and clean hex for binary data).

## Acceptance Criteria
1. All 7 payload viewer scenarios in `tests/bdd/features/07_payload_viewer_filter.feature` pass against fakes.
2. Unit tests in `core` cover UTF-8 detection, invalid JSON handling, pretty printing, hex formatting, and display capping.
3. No mutation escapes test coverage.
4. `./gradlew test` passes with zero regressions across all modules.

## Status
- Reconciled 2026-11-17: this task is merged into `main` (ca8ffe6, 2026-11-06); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.
- **Step 1 (Brief):** Written and confirmed.
- **Step 2 (Tests first):** `PayloadFormatterTest` and `RunPayloadViewerAcceptanceTest` written; red state confirmed on missing domain classes.
- **Step 3 (Build):**
  - Added pure-Kotlin `kotlinx-serialization-json:1.7.3` to `core`.
  - Implemented `PayloadFormatter`, `PayloadInspection`, `PayloadViewKind`, `JsonFormatResult` in `core` commonMain with UTF-8 binary detection, pretty-printed JSON formatting, hex dump generation, display capping with "Show all", property extraction, and copy helpers.
  - Implemented `PayloadViewerSteps` in `acceptance-tests` for all 7 viewer scenarios.
  - Updated `mcp-server` to reuse `PayloadFormatter` for AI agent message payload inspection.
- **Step 4 (Verify):**
  - All 12 unit tests in `PayloadFormatterTest` pass.
  - All 7 BDD scenarios in `07_payload_viewer_filter.feature` (including both examples of the scenario outline) pass.
  - `McpServerTest` passes.
  - `./gradlew test` passes cleanly across all modules without regressions.
- **Step 5 (Review):** Accepted; ready to merge into `main`.
