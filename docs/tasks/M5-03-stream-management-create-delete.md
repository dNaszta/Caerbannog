# M5-03: Create and delete streams with typed confirmation

Milestone: M5    Depends on: M5-02 (merged)    Size: M    Branch: `m5-03-stream-management-create-delete`

## Goal
Implement stream creation and deletion capabilities across core, data-rabbit, UI, and BDD acceptance tests according to `tests/bdd/features/12_stream_management.feature` and `docs/ux/Streams.dc.html`:
1. **Core Domain (`core`):**
   - `StreamManager` interface with `createStream(vhost: String, name: String)` and `deleteStream(vhost: String, name: String)`.
   - `StreamManagementResult` (`Success`, `Refused(reason: String)`, `Failed(message: String)`).
   - Validation logic for stream name: non-blank, non-duplicate.
   - Permission gating: creating and deleting streams requires `configure` permission (topology unlocked).
2. **Test Double (`core-testing`):**
   - `FakeStreamManager` implementing `StreamManager` for unit and acceptance tests.
3. **Data Layer (`data-rabbit`):**
   - `RabbitMqStreamManager` implementing `StreamManager` using RabbitMQ Stream Client `environment.streamCreator().stream(name).create()` and `environment.deleteStream(name)`.
   - Wire into `RabbitMqHeldConnection` and `StreamSource`.
4. **View Model & Management State (`core`):**
   - Manage Create Stream dialog state and Delete Stream dialog state (with typed confirmation and consumer warning).
5. **UI Components (`ui`):**
   - `CreateStreamDialog`: input for stream name, validation error banner, Cancel and Create buttons.
   - `DeleteStreamDialog`: destructive dialog with consumer count warning banner, typed confirmation text input, disabled Confirm button until name matches, Cancel button.
   - Wire dialogs into `StreamsScreen` and row actions / app bar.
6. **BDD & UI Tests:**
   - Implement `RunStreamManagementAcceptanceTest` and `StreamManagementSteps` covering scenarios in `12_stream_management.feature`.
   - Implement Compose UI tests for Create Stream and Delete Stream dialogs.

## Scope
### In
- `core`:
  - `StreamManager`, `StreamManagementResult`.
  - Wire into `HeldConnection` and `StreamSource`.
  - Create and delete stream validation and state logic.
- `core-testing`:
  - `FakeStreamManager`.
- `data-rabbit`:
  - `RabbitMqStreamManager` using Stream Client / HTTP API.
  - Wire into `RabbitMqHeldConnection`.
- `ui`:
  - `CreateStreamDialog` and `DeleteStreamDialog`.
  - Wire into `StreamsScreen`.
  - Compose UI tests.
- `acceptance-tests`:
  - `RunStreamManagementAcceptanceTest` and `StreamManagementSteps`.

### Out
- Topology migrations (M5-04).
- Broker gate packaging test (M5-06).

## Acceptance Criteria
1. BDD scenarios in `tests/bdd/features/12_stream_management.feature` tagged `@fake-broker` pass.
2. Creating a stream validates name (required, non-duplicate) and creates on broker.
3. Deleting a stream requires exact name typed to confirm.
4. Deleting a stream with connected consumers warns about the count.
5. Missing configure permission disables create and delete actions with explanation.
6. All Compose UI tests and `./gradlew test` pass.

## Status
- **Accepted (2026-11-07):** Completed and verified on branch `m5-03-stream-management-create-delete`. All 11 BDD scenarios in `12_stream_management.feature` pass, all 11 Compose UI tests in `StreamManagementUiTest.kt` pass, 10 ViewModel tests pass, and full `./gradlew test` passes cleanly.
