# M3-03: Message browser screen, table, controls, and navigation

Milestone: M3    Depends on: M3-02 (merged)    Size: L    Branch: `m3-03-browser-screen-and-navigation`

## Goal
Implement the Compose Multiplatform UI for the Message Browser on `StreamScreen` matching `docs/ux/Browser.dc.html`, including the control card (start modes, page size, previous/next paging buttons), the messages table (offset, timestamp, properties, body preview), state displays (empty, outside range with clamp jump, loading, error), and wire navigation from the stream list, super stream partitions, and consumer rows ("Jump to offset").

## Why
Users need a visual interface to page through RabbitMQ stream messages in desktop Caerbannog, inspect message metadata, navigate back and forth, and jump directly to consumer lag positions without touching broker state.

## Scope
### In
- **UI module (`ui`):**
  - Update `StreamScreen.kt`:
    - Two tabs: `Messages` (index 0, default selected) and `Publishers and consumers` (index 1).
    - Support tab selection and switching.
  - Implement `MessageBrowserView.kt` (in `dev.caerbannog.ui.streams`):
    - **Control Card (`Browser.dc.html` lines 37-48):**
      - Start mode segmented button: `First`, `Last page`, `Offset`, `Time`.
      - Offset input field (when Offset is active) with apply/jump.
      - Page size dropdown selector: `25`, `50`, `100`, `250`, `500`.
      - Paging buttons: `Previous` and `Next` (disabled when `canGoPrevious` / `canGoNext` is false).
      - Status text: `Offsets X to Y of about Z` and retention shift notice banner when retention truncated the requested offset.
    - **Messages Table (`Browser.dc.html` lines 54-109):**
      - Scrollable table with columns: `Offset` (right-aligned mono), `Timestamp` (formatted local/UTC date-time), `Properties` (content-type badges/pills), `Body` (monospace preview).
      - Selectable rows: clicking a row updates `selectedMessage`.
    - **State displays:**
      - Loading state: centered progress indicator.
      - Empty stream: "This stream has no messages yet".
      - No messages at or after timestamp: message indicating no messages found at or after the given time.
      - Outside range: notice showing valid range and button to "Jump to nearest valid offset".
      - Error: message and retry / back option.
    - **Consumer jump integration:**
      - In `StreamClientsTables.kt`, add "Jump to offset in browser" or "View in browser" action on consumer rows.
- **Composition & Navigation (`desktopApp`):**
  - Extend `AppNavigation` to support `openStream(name: String, initialOffset: Long? = null)`.
  - Wire `MessageBrowserViewModel` in `CompositionRoot` or factory to supply `StreamScreen`.
  - Wire consumer jump action to navigate to `Messages` tab and invoke `browseOffset(consumerOffset)`.
- **BDD Acceptance (`acceptance-tests`):**
  - Un-skip `@needs-M3` in `tests/bdd/features/04_publishers_consumers.feature`: "Jump from a consumer to its offset in the browser".
  - Un-skip `@needs-M3` in `tests/bdd/features/06_super_streams.feature`: "Browse one partition".
  - Implement steps to assert navigation and offset start.
- **UI Unit Testing (`ui`):**
  - Comprehensive UI tests in `ui/src/jvmTest/kotlin/dev/caerbannog/ui/streams/`:
    - Controls render correctly and trigger view-model paging operations.
    - Previous/Next button enabled/disabled states match `canGoPrevious` / `canGoNext`.
    - Table renders rows with correct offsets and content.
    - Empty, outside range, and loading states render accurately.

### Out
- M4: Text/JSON/Hex payload detail sheet and bounded scan/filter bar (`Browser.dc.html` lines 49-53 and lines 111-130).
- M5: Publish test message dialog.

## Headless MCP Check
- **Evaluation:** Evaluated. The headless MCP tool `caerbannog_browse_stream` was designed and delivered in M3-01. M3-03 is purely Desktop Compose UI and navigation wiring. No new MCP tool is required.

## Safety & Rules
- Browsing consumers remain strictly unnamed with `noTrackingStrategy()`.
- Zero reading or purging of quorum/classic queues.
- UI operations must remain non-blocking on the UI dispatcher.

## Definition of Done
1. `StreamScreen` renders `Messages` tab by default with full paging controls and table matching `Browser.dc.html`.
2. Paging controls (First, Last page, Offset, Time, Page size, Next, Previous) function reactively.
3. Consumer jump opens `Messages` tab at the consumer's offset.
4. Partition click opens `Messages` tab on that partition stream.
5. All BDD scenarios for consumer jump and partition browse pass.
6. UI tests verify all controls, states, and disabling conditions.
7. Mutation testing verified (mutations killed).
8. `./gradlew test verifyTestDiscovery` green.

## Status

### Step 1: Solution architect brief (2026-11-06)
- Brief established with Headless MCP check, scope, rules, and acceptance criteria.
- Branch `m3-03-browser-screen-and-navigation` created.

### Step 2: Test developer creates failing test (2026-11-06)
- Authored Compose UI tests in `MessageBrowserUiTest` verifying:
  - Start mode segmented button rendering and interaction.
  - Next and Previous button enablement according to paging flags.
  - Messages table row rendering, offset/body format, and selectable row clicks.
  - Empty stream display, outside range state with jump-to-nearest button, and retention shift notices.
  - StreamScreen tab rendering and switching between `Messages` and `Publishers and consumers`.
- Verified compilation failure (RED) against missing UI interfaces.

### Step 3: Senior developer implements to green (2026-11-06)
- Implemented `MessageBrowserEvents` and default no-op adapter in `ui/src/commonMain/kotlin/dev/caerbannog/ui/streams/MessageBrowserEvents.kt`.
- Implemented `MessageBrowserView` in `ui/src/commonMain/kotlin/dev/caerbannog/ui/streams/MessageBrowserView.kt` matching `Browser.dc.html`:
  - Segmented start mode buttons (`First`, `Last page`, `Offset`, `Time`).
  - Offset input with jump button.
  - Page size dropdown (`25`, `50`, `100`, `250`, `500`).
  - `Previous` and `Next` buttons with edge disabling and status text.
  - Scrollable messages table with monospace offsets, formatted timestamps, properties badges, and payload preview.
  - State cards for Loading, Empty, NoMessagesAtOrAfterTime, OutsideRange, and Error.
- Updated `StreamScreen.kt` to render both `Messages` and `Publishers and consumers` tabs with tab switching.
- Implemented `MessageBrowserEventsAdapter` in `desktopApp` and wired `Main.kt` and `AppNavigation.kt` for offset jumps.
- Un-skipped and wired `@needs-M3` BDD scenarios across `02_permission_check.feature`, `04_publishers_consumers.feature`, and `06_super_streams.feature`.
- Verified unit and acceptance tests passed.

### Step 4: Test developer verifies and mutation checks (2026-11-06)
- Mutation 1: Changed "Last page" segmented button to invoke `events.browseFirst()` instead of `browseLastPage()`. `MessageBrowserUiTest.controls render start modes and trigger browse operations()` failed immediately (mutation killed).
- Mutation 2: Hardcoded Previous button `enabled = true` ignoring `state.canGoPrevious`. `MessageBrowserUiTest.next and previous buttons respect enabled states and invoke paging()` failed immediately (mutation killed).
- Restored code and confirmed all 7 tests in `MessageBrowserUiTest` passed.
- Full suite `./gradlew test verifyTestDiscovery` green across all modules in 1m 4s.

### Step 5: Solution architect review (2026-11-06)
- Verdict: **APPROVED**.
- Implementation matches `docs/ux/Browser.dc.html`, preserves unnamed consumer safety invariants, and enables consumer offset navigation.
- Ready for explicit Product Owner approval to merge into `main`.
