# M5-01: Publish test message engine, models, and BDD scenarios

Milestone: M5    Depends on: M4 (merged)    Size: M    Branch: `m5-01-publish-test-message-core`

## Goal
Implement the core domain models and publishing engine for sending single test messages to streams, exchanges, and queues according to `tests/bdd/features/08_publish_test_message.feature` and `docs/ux/Publish.dc.html`:
1. **Target Models & Requests (`core`):**
   - Target kinds: `Stream`, `Exchange`, `Queue`.
   - `PublishTarget(kind: PublishTargetKind, name: String, routingKey: String? = null)`.
   - `PublishMessageRequest`: target, body (bytes / string), standard properties (`messageId`, `contentType`), and application properties (`Map<String, String>`).
   - `PublishOutcome`: `Success(message: String)`, `Refused(reason: String)`, `Failed(message: String)`.
2. **Publish Engine (`MessagePublisher`):**
   - `MessagePublisher` interface in `core` and in-memory `FakeMessagePublisher` in `core-testing`.
   - Permission enforcement: publishing requires `write` permission on the active virtual host; refused or disabled if write permission is absent.
   - Guard against sending when cancelled or without write permission.
3. **Data Layer (`data-rabbit`):**
   - Stream publishing via RabbitMQ Stream Client producer:
     - Sends AMQP 1.0 message to specified stream using `Environment.producerBuilder().stream(target.name).build()`.
     - Maps `messageId`, `contentType`, and application properties.
   - Exchange & Queue publishing via RabbitMQ Management API HTTP POST:
     - Exchange: `POST /api/exchanges/{vhost}/{name}/publish` with `{"routing_key": routingKey, "payload": body, "payload_encoding": "string", "properties": {...}}`.
     - Queue: published to default exchange `""` with `routing_key = queueName`.
4. **View Model (`PublishMessageViewModel` in `core`):**
   - Drives dialog state: target kind, target name, routing key, properties, application properties, body, validation state, broker warning notice (`"This message goes to the real broker on <profile>"`), publish execution, and feedback banner.
5. **BDD Scenarios & MCP Evaluation:**
   - Implement step definitions and suite runner in `acceptance-tests` for `08_publish_test_message.feature`.
   - Evaluate and implement MCP tool `caerbannog_publish_test_message` with write permission safety and unit tests.

## Scope
### In
- `core`:
  - `PublishTargetKind`, `PublishTarget`, `PublishMessageRequest`, `PublishOutcome`.
  - `MessagePublisher` interface.
  - `PublishMessageViewModel` and `PublishMessageState`.
- `core-testing`:
  - `FakeMessagePublisher`.
- `data-rabbit`:
  - `RabbitMqMessagePublisher` implementing `MessagePublisher` over Stream Client and Management API HTTP POST.
- `acceptance-tests`:
  - `RunPublishTestMessageAcceptanceTest` and `PublishTestMessageSteps.kt`.
- `mcp-server`:
  - Tool `caerbannog_publish_test_message` with permissions validation.

### Out
- Compose Multiplatform UI Dialog layout and Compose UI tests (Task M5-02).
- Real broker `@broker` offset verification (Task M5-06).

## Acceptance Criteria
1. BDD scenarios in `tests/bdd/features/08_publish_test_message.feature` tagged `@fake-broker` pass.
2. Publishing to streams, exchanges, and queues validates targets and passes standard & application properties.
3. Write permission is strictly required; attempts to publish without write permission fail safely with a descriptive refusal.
4. MCP tool `caerbannog_publish_test_message` exposes publishing with validation.
5. All fast tests (`./gradlew test`) pass.

## Status
- **Accepted (2026-11-07):**
  - Created domain models in `core/publish/PublishModel.kt` (`PublishTargetKind`, `PublishTarget`, `PublishMessageRequest`, `PublishOutcome`, `MessagePublisher`).
  - Created `PublishMessageViewModel` in `core/publish/PublishMessageViewModel.kt` handling target selection, properties, body, write permission gate, warning banner, and cancellation.
  - Implemented `FakeMessagePublisher` in `core-testing`.
  - Implemented `RabbitMqMessagePublisher` in `data-rabbit` over native stream producer for streams and Management API HTTP POST for exchanges and queues (`amq.default`).
  - Implemented `PublishTestMessageSteps.kt` and `RunPublishTestMessageAcceptanceTest.kt`: 8 BDD scenarios in `08_publish_test_message.feature` passing.
  - Added MCP tool `caerbannog_publish_test_message` with write permission / topology level guardrails and unit tests in `McpServerTest.kt`.
  - Added 10 unit tests in `PublishMessageViewModelTest.kt`.
  - Full test suite `./gradlew test` green (0 failures).

