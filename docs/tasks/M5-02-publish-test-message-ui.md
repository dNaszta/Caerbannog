# M5-02: Publish test message dialog and Compose UI tests

Milestone: M5    Depends on: M5-01 (merged)    Size: M    Branch: `m5-02-publish-test-message-ui`

## Goal
Implement the Compose Multiplatform UI components for publishing test messages according to `docs/ux/Publish.dc.html` and `tests/bdd/features/08_publish_test_message.feature`:
1. **Publish Dialog (`ui`):**
   - Scrim overlay dimming the app behind the dialog.
   - Title and description: `"Publish test message"` and `"Send one message to any stream, exchange or queue on this connection."`.
   - Segmented buttons for target kinds (`Stream`, `Exchange`, `Queue`).
   - Dynamic inputs based on target kind:
     - Stream: target input labeled `"Stream"`.
     - Exchange: target input labeled `"Exchange"` and `"Routing key"` input.
     - Queue: target input labeled `"Queue"`.
     - `message-id` and `content-type` inputs.
   - Monospace text area for payload body.
   - Application properties section with chips, deletion action, and add property form.
   - Real broker warning banner (`"This message goes to the real broker on <connection>. Publishing test messages is allowed for this user, but its consumers will receive it."`).
   - Connection pill displaying `"Connection: <connection>"`.
   - Cancel and Publish buttons; Publish is disabled when inputs are invalid, publishing is in-flight, or write permission is absent.
   - Feedback banners: confirmation when sent, error on broker refusal or failure, and no-write-permission notice when gated.
2. **Stream Screen App Bar Action (`ui`):**
   - Add `"Publish test message"` action button in the `StreamScreen` app bar styled as a tonal lead button with `AppIcon.Send`.
   - Disabled with tooltip/content-description when user has no write permission.
   - Triggers opening the publish dialog overlay.
3. **Application Wiring (`desktopApp`):**
   - Create `PublishEventsAdapter` adapting `PublishMessageViewModel` actions to the `PublishEvents` UI interface.
   - Wire `PublishMessageViewModel` and `PublishEventsAdapter` in `StreamWindowContent`.
4. **UI Tests (`ui`):**
   - Write comprehensive Compose Multiplatform UI tests in `PublishDialogUiTest.kt`.

## Scope
### In
- `ui`:
  - `PublishEvents.kt`: `PublishEvents` interface and `EmptyPublishEvents`.
  - `PublishDialog.kt`: `PublishTestMessageDialog` matching `Publish.dc.html`.
  - `StreamScreen.kt`: app bar action button and dialog integration.
  - `ConnectionsIcons.kt`: `AppIcon.Send`.
  - `PublishDialogUiTest.kt`: Compose UI unit tests.
- `desktopApp`:
  - `PublishEventsAdapter.kt`: adapter wiring `PublishMessageViewModel` to `PublishEvents`.
  - `Main.kt`: wire `PublishMessageViewModel` into `StreamWindowContent`.

### Out
- Core publishing engine and BDD feature runner (completed in M5-01).
- End-to-end broker offset verification (M5-06).

## Acceptance Criteria
1. Dialog renders matching `docs/ux/Publish.dc.html` with scrim, segments, inputs, warning banner, connection pill, and actions.
2. Target kind switching reveals `"Routing key"` input only for `Exchange`.
3. Application properties support addition and removal.
4. "Publish test message" button in `StreamScreen` is disabled when write permission is absent and provides explanation.
5. All UI tests in `PublishDialogUiTest` pass.
6. Full test suite (`./gradlew test`) passes with 0 failures.

## Status
- **Accepted (2026-11-07):**
  - Added `AppIcon.Send` icon mapping in `ConnectionsIcons.kt`.
  - Implemented `PublishEvents.kt` and `PublishDialog.kt` in `ui/publish/`.
  - Updated `StreamScreen.kt` to include the `"Publish test message"` app bar action button and overlay `PublishTestMessageDialog`.
  - Implemented `PublishEventsAdapter.kt` and wired dialog in `desktopApp/Main.kt`.
  - Added 12 Compose UI tests in `PublishDialogUiTest.kt` verifying all dialog sections, event dispatches, permission gating, and `StreamScreen` integration.
  - Full `./gradlew test` passes cleanly (1m 3s, 0 failures).
