# M6-05: "Publish MQTT Message" dialog and Compose UI tests

Milestone: M6    Depends on: M6-04 (merged)    Size: M    Branch: `m6-05-mqtt-sender-ui`

## Goal
Implement the view model, UI dialog, and Compose test suite for sending MQTT test messages:
1. **Core View Model (`core`):**
   - `PublishMqttState`: tracks connection name, topic, QoS level (`MqttQos`), retain flag, payload text, publishing status, confirmation, and error text.
   - `PublishMqttViewModel`: manages validation (topic not blank, no `+` or `#` wildcards), payload dispatch to `MqttMessagePublisher`, and outcome handling.
2. **UI Dialog & Events (`ui`):**
   - `PublishMqttEvents`: interface for dialog interactions (`setTopic`, `setQos`, `setRetain`, `setPayload`, `publish`, `cancel`, `clearFeedback`).
   - `PublishMqttDialog`: Compose Multiplatform modal dialog:
     - Destination broker warning banner.
     - Topic input with live wildcard validation error.
     - QoS selector chips (QoS 0, QoS 1, QoS 2).
     - Retain message toggle switch.
     - Multiline payload editor.
     - Feedback alerts (confirmation, error).
     - Cancel and Publish action buttons.
3. **Topology Integration (`ui`):**
   - Add "Publish message" action on the MQTT tab in `TopologyControls` or `TopologyScreen`.
   - Host `PublishMqttDialog` when triggered.
4. **Verification & Tests:**
   - Unit tests for `PublishMqttViewModel` in `PublishMqttViewModelTest.kt`.
   - Desktop Compose UI tests in `PublishMqttDialogUiTest.kt`.

## Scope
### In
- `PublishMqttViewModel` and `PublishMqttState` in `core`.
- `PublishMqttEvents` and `PublishMqttDialog` in `ui`.
- Wiring into `TopologyControls` / `TopologyScreen`.
- Compose UI test suite in `PublishMqttDialogUiTest.kt`.

### Out
- MCP tool `caerbannog_publish_mqtt` (M6-06).

## Acceptance Criteria
1. The dialog renders topic, QoS selector (0, 1, 2), retain toggle, payload editor, and publish button.
2. Wildcards in the topic field (`+`, `#`) are flagged with an error and disable the publish button.
3. Successfully publishing displays the confirmation message and sends the configured QoS and retain flag.
4. Failed or refused publishes display the error text without leaking secrets.
5. All unit and UI tests pass.

## Status
- **Accepted (2026-11-08):** Complete on branch `m6-05-mqtt-sender-ui`. All unit tests (`PublishMqttViewModelTest`) and 10 Compose UI tests (`PublishMqttDialogUiTest`) passing; full UI test suite passing (353 tests green). Zero regressions.
