# M4-02: Bounded client-side stream scanner and filter engine

Milestone: M4    Depends on: M4-01 (merged)    Size: M    Branch: `m4-02-filter-scan-engine`

## Goal
Implement a bounded client-side stream scanner and filtering engine in `core` that:
1. Filters stream messages client-side by text query (substring search across payload body or hex representation), standard AMQP/Stream properties, or application properties (`key=value`).
2. Enforces a strict scan limit (default 10,000 messages) to prevent memory exhaustion and UI thread freezes.
3. Provides live scan progress reporting (number of messages scanned, matches found).
4. Emits a clear notice when the scan limit is reached (`"Scan limit of 10,000 reached; remaining messages were not searched"`).
5. Supports cooperative immediate cancellation (`"Cancel scan"`), preserving all matches found prior to cancellation.
6. Guarantees broker safety: scanning is strictly read-only, using ephemeral consumers (`noTrackingStrategy()`) with zero message publication, acknowledgement, deletion, or offset manipulation.
7. Exposes filter parameters to MCP tool `caerbannog_browse_stream`.
8. Passes all filter scenarios in `tests/bdd/features/07_payload_viewer_filter.feature`.

## Scope
### In
- **Core module (`core`):**
  - `MessageFilterCriteria`:
    - `None`
    - `Text(query: String)`: matches decoded text body or hex string.
    - `Property(name: String, value: String)`: matches standard message property.
    - `ApplicationProperty(name: String, value: String)`: matches user-defined application property.
  - `ScanProgress`:
    - `scannedCount: Int`
    - `matchCount: Int`
    - `isRunning: Boolean`
    - `limitReached: Boolean`
    - `scanLimit: Int`
    - `notice: String?`
  - `StreamScanner`:
    - Sequentially queries pages via `StreamMessageReader` starting from a given offset up to `scanLimit` (10,000).
    - Checks predicate against each `StreamMessage`.
    - Returns `Flow<ScanProgressUpdate>` with cooperative cancellation support.
  - `MessageBrowserViewModel` extensions / integration:
    - `fun filter(criteria: MessageFilterCriteria)`
    - `fun cancelScan()`
    - `fun clearFilter()`
    - `filterProgress: StateFlow<ScanProgress?>`
    - In `Loaded` state, `displayedMessages` reflects filter results when filter is active.
- **Acceptance tests (`acceptance-tests`):**
  - Remove `@needs-M4-02` tag from filter scenarios in `07_payload_viewer_filter.feature`.
  - Implement step definitions in `PayloadViewerSteps.kt` (or `MessageFilterSteps.kt` in `dev.caerbannog.acceptance.viewer`) for:
    - Text filtering within bounded scan
    - Application property filtering
    - 10,000 scan limit cutoff
    - Instant scan cancellation
- **MCP server (`mcp-server`):**
  - Add optional `filterText` and `appProperty` parameters to `caerbannog_browse_stream`.

### Out
- Desktop Compose UI filter bar composable and scan limit chip (task M4-03).
- Real broker responsiveness gate `@gate-M4` (task M4-04).

## Acceptance Criteria
1. All filter scenarios in `tests/bdd/features/07_payload_viewer_filter.feature` pass against fakes.
2. Unit tests in `core` verify predicate matching (text, properties, app properties), 10,000 message ceiling, cooperative cancellation, and progress updates.
3. No broker mutations occur during scan operations.
4. `./gradlew test` passes cleanly with zero regressions.

## Status
- **Step 1 (Brief):** Written and confirmed.
- **Step 2 (Tests first):** `StreamScannerTest` written; red state confirmed on missing domain classes.
- **Step 3 (Build):**
  - Implemented `StreamScanner`, `MessageFilterCriteria`, `ScanProgressUpdate` in `core` commonMain.
  - Extended `MessageBrowserViewModel` with `filter()`, `cancelScan()`, `clearFilter()`, and `scanProgress` flow.
  - Extended `MessageBrowserState.Loaded` with `filterNotice` and `isFilterActive`.
  - Implemented BDD step definitions in `PayloadViewerSteps.kt` for text filtering, application property filtering, 10,000 limit cutoff, and immediate scan cancellation.
  - Updated `mcp-server` `caerbannog_browse_stream` with optional `filterText`, `appPropertyKey`, `appPropertyValue` arguments and unit test.
- **Step 4 (Verify):**
  - All 5 unit tests in `StreamScannerTest` pass.
  - All 11 BDD scenarios in `07_payload_viewer_filter.feature` (7 viewer + 4 filter) pass against fakes.
  - `McpServerTest` passes including filter check.
  - Full `./gradlew test` passes across all modules without regressions.
- **Step 5 (Review):** Accepted; ready to merge into `main`.
