# M4-04: Real broker responsiveness gate and ephemeral read-only safety verification

Milestone: M4    Depends on: M4-03 (merged)    Size: M    Branch: `m4-04-gate-large-bodies-and-safety`

## Goal
Verify the Milestone M4 milestone gate `@gate-M4` and broker safety scenarios from `tests/bdd/features/07_payload_viewer_filter.feature` against a real RabbitMQ 4 broker with streams enabled:
1. **`@safety` Scenario ("Filtering is read-only on the broker"):**
   - Stream with messages and an existing consumer tracking offset.
   - Execute text / property filters and filter clearing via `MessageBrowserViewModel`.
   - Verify that filtering uses ephemeral consumers with `noTrackingStrategy()`, leaving stored offsets, consumer groups, and stream message counts strictly unaltered.
2. **`@gate-M4 @broker` Scenario ("Large bodies keep the screen responsive"):**
   - Populate a stream with 100 messages of 1 MB (1,048,576 bytes) each.
   - Browse the stream from `First` (full 100-message page).
   - Verify that page loading succeeds without OOM, messages are read and formatted with bounded display capping (preview capped to 64 KB, full payload expandable on demand), and message selection/inspection executes promptly without blocking UI responsiveness.

## Acceptance Criteria
1. Test suite in `data-rabbit` tagged `@Tag("broker")` verifies read-only safety during filtering (no tracking offsets created, existing offsets and message counts unchanged).
2. Gate test verifies browsing 100 x 1 MB messages from a real RabbitMQ 4 broker, confirming display capping limits memory allocation and preview generation time stays responsive (< 500 ms for inspection).
3. `./gradlew :data-rabbit:brokerTest` passes cleanly.
4. `docs/spec.md` updated with Milestone M4 marked accepted.

## Status
- **Accepted (2026-11-07):**
  - Created `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerPayloadViewerTest.kt` tagged `@Tag("broker")`.
  - Implemented `@safety` scenario: verified that stream filtering and clearing filters leaves existing tracking offsets, consumer groups, and stream message counts strictly unchanged on the real broker.
  - Implemented `@gate-M4 @broker` scenario: verified browsing 100 x 1 MB messages from a real RabbitMQ 4 broker, confirming display capping (64 KB) limits preview size, payload inspection executes swiftly (< 500 ms), and scrolling/selecting remains responsive (< 200 ms).
  - Both tests in `RealBrokerPayloadViewerTest` pass against RabbitMQ 4 with streams and TLS enabled.

