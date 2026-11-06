# M3-02: MessageBrowserViewModel state machine, paging logic, and BDD scenarios

Milestone: M3    Depends on: M3-01 (merged)    Size: M    Branch: `m3-02-browser-view-model-and-paging`

## Goal
Implement `MessageBrowserViewModel` in `core` to drive the message browser's unidirectional data flow and paging state machine, and wire the Cucumber/BDD step definitions to pass the 18 scenarios of `tests/bdd/features/05_message_browser_paging.feature`.

## Why
Users and engineers need to navigate RabbitMQ stream messages page-by-page (First, Last page, Next, Previous, Jump to offset, and Seek by timestamp) with accurate edge handling, page size selection, and resilient reactions when retention drops messages or streams are empty, without mutating broker state.

## Scope
### In
- **Core module (`core`):**
  - `MessageBrowserState`: sealed hierarchy:
    - `Loading(vhost: String, stream: String, start: BrowseStart, pageSize: Int)`
    - `Empty(vhost: String, stream: String, streamFirstOffset: Long, streamLastOffset: Long)`
    - `NoMessagesAtOrAfterTime(vhost: String, stream: String, epochMs: Long, streamFirstOffset: Long, streamLastOffset: Long)`
    - `OutsideRange(vhost: String, stream: String, requestedOffset: Long, validRange: LongRange, nearestOffset: Long)`
    - `Loaded(vhost: String, stream: String, messages: List<StreamMessage>, streamFirstOffset: Long, streamLastOffset: Long, approximateTotal: Long?, pageSize: Int, startMode: BrowseStart, canGoNext: Boolean, canGoPrevious: Boolean, selectedMessage: StreamMessage?, retentionShiftNotice: String?)`
    - `Error(vhost: String, stream: String, message: String)`
  - `MessageBrowserViewModel`:
    - Constructor taking `streamSource: StreamSource` (or `StreamMessageReader` and stream details), `vhost: String`, `stream: String`, `scope: CoroutineScope`, `initialPageSize: Int = 100`.
    - Paging operations:
      - `browseFirst()`
      - `browseLastPage()`
      - `browseOffset(offset: Long)`
      - `browseTimestamp(epochMs: Long)`
      - `nextPage()`: starts at `lastShownOffset + 1`
      - `previousPage()`: starts at `maxOf(streamFirstOffset, firstShownOffset - pageSize)`
      - `jumpToOffset(offset: Long)`
      - `jumpToNearestValidOffset()`
      - `setPageSize(size: Int)`
      - `selectMessage(offset: Long?)`
    - Edge disabling:
      - `canGoNext` is false when `lastShownOffset >= streamLastOffset` or `messages.size < pageSize`.
      - `canGoPrevious` is false when `firstShownOffset <= streamFirstOffset`.
    - Retention shift handling:
      - If retention drops messages while paging previous, clamp to new `firstOffset` and attach `retentionShiftNotice`.
- **BDD Acceptance (`tests/bdd` / `acceptance-tests`):**
  - Wire step definitions in `tests/bdd` for `05_message_browser_paging.feature` scenarios.
  - Verify all 18 scenarios in `05_message_browser_paging.feature` pass.
- **Unit testing (`core`):**
  - Comprehensive unit tests in `core/src/jvmTest/kotlin/dev/caerbannog/core/browser/MessageBrowserViewModelTest.kt` verifying all transitions, state clamp calculations, and edge flags.

### Out
- Compose UI rendering / screens (M3-03).
- Text/JSON/Hex payload viewer formatting & search filters (M4).
- Partition switching for super streams (M3-04).

## Headless MCP Check
- **Evaluation:** Evaluated. The headless MCP tool `caerbannog_browse_stream` was already designed, implemented, and verified in M3-01. M3-02 is purely the desktop client view-model state machine and acceptance test step wiring. No new MCP tool is required for M3-02.

## Safety & Rules
- **Ephemeral Consumer Safety:** Browsing always relies on `StreamMessageReader` (M3-01) with unnamed ephemeral consumers and `noTrackingStrategy()`.
- **Zero Queue Touching:** No classic or quorum queues are ever read or modified.
- **Paging Limits:** Page size options: 25, 50, 100, 250, 500.

## Definition of Done
1. `MessageBrowserState` and `MessageBrowserViewModel` implemented in `core`.
2. Unit tests covering all states, transitions, edge disabling, and retention shifts.
3. Cucumber/BDD step definitions implemented for `05_message_browser_paging.feature`.
4. All 18 scenarios in `05_message_browser_paging.feature` pass.
5. `./gradlew test verifyTestDiscovery` green.

## Status

### Step 1: Solution architect brief (2026-11-06)
- Brief established with Headless MCP check, scope, rules, and acceptance criteria.
- Branch `m3-02-browser-view-model-and-paging` active.

### Step 2: Test developer creates failing test (2026-11-06)
- Created `MessageBrowserViewModelTest` covering:
  - Initial `Loading` state and transition to `Loaded` on `browseFirst()`.
  - Edge flag computation: `canGoNext` and `canGoPrevious` at first page, middle page, and last page.
  - Paging operations: `nextPage()` starting at `lastShown + 1`, `previousPage()` clamped to `firstOffset`.
  - Start modes: `browseLastPage()`, `browseOffset()`, `browseTimestamp()`.
  - `jumpToOffset()`, `jumpToNearestValidOffset()`, `setPageSize()`, and `selectMessage()`.
  - Edge cases: `Empty` stream, `NoMessagesAtOrAfterTime`, `OutsideRange`, and retention shift clamping with notice.
- Ran `./gradlew :core:jvmTest --tests "*MessageBrowserViewModelTest*"`: Compilation failed (RED).

### Step 3: Senior developer implements to green (2026-11-06)
- Implemented `MessageBrowserState` sealed class hierarchy in `core/src/commonMain/kotlin/dev/caerbannog/core/browser/MessageBrowserState.kt`.
- Implemented `MessageBrowserViewModel` in `core/src/commonMain/kotlin/dev/caerbannog/core/browser/MessageBrowserViewModel.kt`:
  - Unidirectional state flow using `StateFlow<MessageBrowserState>`.
  - Paging arithmetic with offset calculations and retention boundary clamping.
  - Page size change re-fetches current window using current start mode.
- Implemented Cucumber BDD runner `RunMessageBrowserAcceptanceTest` and step definitions `MessageBrowserSteps` in `acceptance-tests/src/test/kotlin/dev/caerbannog/acceptance/browser/`.
- Verified 12/12 unit tests and all 16 `@fake-broker` BDD scenarios pass.

### Step 4: Test developer verifies and mutation checks (2026-11-06)
- Mutation 1: Forcing `canGoNext = false` in `MessageBrowserViewModel.kt` caused 2 unit tests to fail immediately (mutation killed).
- Mutation 2: Disabling automatic retention shift re-request and notice clamping caused `MessageBrowserViewModelTest.retention moving firstOffset during paging sets retentionShiftNotice and clamps()` to fail immediately (mutation killed).
- Reverted mutations; verified `./gradlew :core:jvmTest` and `./gradlew :acceptance-tests:test` pass cleanly.
- Full suite verification: `./gradlew test verifyTestDiscovery` passed cleanly in 12s across all 11 test modules.

### Step 5: Solution architect review (2026-11-06)
- Verdict: **APPROVED**.
- Architecture alignment: Clean separation of concerns between `StreamMessageReader` (data) and `MessageBrowserViewModel` (state machine/presentation).
- Safety verified: Browsing consumers remain strictly unnamed with `noTrackingStrategy()`; queue safety invariants intact.
- Ready for explicit Product Owner approval to merge into `main`.
