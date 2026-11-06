# M4-03: Message browser detail side sheet, viewer tabs, filter bar, and UI tests

Milestone: M4    Depends on: M4-02 (merged)    Size: L    Branch: `m4-03-viewer-and-filter-ui`

## Goal
Implement the Compose Multiplatform UI components for inspecting message payloads and filtering stream messages in Desktop Caerbannog according to `docs/ux/Browser.dc.html`:
1. **Filter bar (`Browser.dc.html` lines 49-53):**
   - Search text field with placeholder `"Filter by text, property or application property"`.
   - Scan limit indicator chip (`"Scan limit: 10,000"`).
   - `"Cancel scan"` button (enabled while scan is actively running).
   - Status text displaying messages scanned / scan limit notice.
   - Clear filter action.
2. **Message details side sheet (`Browser.dc.html` lines 111-130):**
   - Sheet container displayed on the right when a row is selected.
   - Header with `"Message at offset <offset>"` and close button.
   - Tabs: `Text`, `JSON`, `Hex` with active indicator.
   - Code preview box displaying formatted body:
     - Indented JSON for JSON payloads.
     - Formatted text for valid text payloads.
     - Hex dump for binary payloads or when Hex tab is chosen.
     - Warning banner if JSON is malformed.
   - Display capping for large bodies:
     - Shows truncated preview when payload exceeds cap limit.
     - Explicit `"Show all"` action button/banner to view full body.
   - Key-value table for message properties:
     - `Timestamp`: formatted date-time.
     - Standard AMQP/Stream properties (e.g. `content-type`, `message-id`).
     - Application properties labeled `<key> (app property)`.
   - Action buttons:
     - `"Copy body"` button with clipboard copy feedback.
     - `"Copy offset"` button with clipboard copy feedback.
3. **Compose UI Tests:**
   - Expand `MessageBrowserUiTest` in `ui` to test:
     - Filter input and scan cancellation trigger.
     - Detail sheet opens on row selection and closes on close button.
     - Switching between `Text`, `JSON`, and `Hex` tabs.
     - Display capping and `"Show all"` action.
     - Copy actions.

## Scope
### In
- **UI module (`ui`):**
  - Update `MessageBrowserEvents.kt` with `filter()`, `cancelScan()`, `clearFilter()`.
  - Update `MessageBrowserView.kt`:
    - Add `FilterBar` composable.
    - Add `MessageDetailsSheet` composable with tabs, code preview, properties list, copy buttons, and "Show all".
    - Two-pane layout when a message is selected (messages table on left, details sheet on right).
  - Update `MessageBrowserUiTest.kt` with comprehensive UI tests for detail sheet, tabs, cap expansion, and filter controls.
- **Desktop application (`desktopApp`):**
  - Update `MessageBrowserEventsAdapter.kt` to forward filter and scan events to `MessageBrowserViewModel`.

### Out
- Real broker responsiveness gate `@gate-M4` (task M4-04).

## Acceptance Criteria
1. Message detail sheet appears when a table row is selected and closes when close button is clicked.
2. `Text`, `JSON`, and `Hex` tabs switch views cleanly.
3. Bodies exceeding display cap show truncated view and expand completely on `"Show all"`.
4. Properties and application properties render in the key-value section.
5. Filter bar renders input, scan limit chip, and cancel button, triggering filter/cancel events.
6. All unit and Compose UI tests pass.

## Status
- **Accepted (2026-11-07):**
  - Implemented `FilterBar` composable in `MessageBrowserView.kt` with text field, scan limit chip, cancel button, and scan progress status text.
  - Implemented `MessageDetailsSheet` with `Text`, `JSON`, `Hex` tabs, code preview, copy actions (`Copy body`, `Copy offset`), truncation and `Show all` toggle, and property key-value table.
  - Added material icons in `ConnectionsIcons.kt` (`FilterList`, `ContentCopy`, `Tag`, `Clear`).
  - Added event methods in `MessageBrowserEvents.kt` and wired them in `desktopApp/MessageBrowserEventsAdapter.kt`.
  - Added 4 Compose UI tests in `MessageBrowserUiTest.kt`.
  - All tests passing (`./gradlew test`).

