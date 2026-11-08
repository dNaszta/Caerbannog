# M6-04: MQTT publisher engine (QoS 0/1/2, Retain) with BDD scenarios

Milestone: M6    Depends on: M6-03 (merged)    Size: M    Branch: `m6-04-mqtt-sender-engine`

## Goal
Implement the core domain, test doubles, data layer, and acceptance tests for publishing test messages over MQTT:
1. **Core Domain (`core`):**
   - `MqttQos` enum: `AT_MOST_ONCE(0)`, `AT_LEAST_ONCE(1)`, `EXACTLY_ONCE(2)` with `fromInt(value: Int)`.
   - `PublishMqttRequest` data model: `topic: String`, `qos: MqttQos`, `retain: Boolean`, `payload: ByteArray`.
   - `MqttMessagePublisher` interface (`publish(request: PublishMqttRequest): PublishOutcome`) and `UnavailableMqttMessagePublisher`.
   - Wire `mqttPublisher: MqttMessagePublisher` into `HeldConnection`, `StreamSource`, and `ActiveConnection`.
   - Topic validation rules: topic cannot be blank; topic cannot contain MQTT wildcard characters (`+` or `#`).
2. **Core Testing (`core-testing`):**
   - `FakeMqttMessagePublisher`: in-memory test double tracking published messages, with refusal/failure injection support.
   - Wire `FakeMqttMessagePublisher` into `FakeConnectionGateway.Held`.
3. **Data Layer (`data-rabbit`):**
   - Add `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` to `data-rabbit`.
   - Implement `RabbitMqMqttPublisher`:
     - Connects via TCP or TLS (using `TlsSupport.sslContextOf`) to the profile's host and `mqttPort`.
     - Executes message publish with topic, QoS (0, 1, or 2), retain flag, and payload.
     - Translates MQTT / socket / authentication exceptions into safe `PublishOutcome.Refused` or `PublishOutcome.Failed` outcomes without leaking credentials.
     - Ensures client connections and resources are cleaned up immediately.
   - Wire `RabbitMqMqttPublisher` into `RabbitMqConnectionGateway` and `RabbitMqHeldConnection`.
4. **Verification & Tests (`core`, `data-rabbit`, `acceptance-tests`):**
   - Unit tests in `core` verifying validation and outcome mapping.
   - Unit tests in `data-rabbit` testing `RabbitMqMqttPublisher` with mock / simulated MQTT server and TLS / socket error handling.
   - Cucumber acceptance feature and step definitions verifying publishing scenarios across QoS 0, 1, 2, and retain flag.

## Scope
### In
- `MqttQos`, `PublishMqttRequest`, and `MqttMessagePublisher` in `core`.
- `HeldConnection` and `StreamSource` wiring.
- `FakeMqttMessagePublisher` in `core-testing`.
- `RabbitMqMqttPublisher` in `data-rabbit`.
- Acceptance test scenario for MQTT publishing in `acceptance-tests`.

### Out
- Compose UI Dialog for MQTT Publishing (M6-05).
- MCP tool `caerbannog_publish_mqtt` (M6-06).

## Acceptance Criteria
1. `PublishMqttRequest` supports QoS 0, 1, and 2, and the `retain` flag.
2. Publishing to blank topics or topics containing `+` or `#` wildcards returns `PublishOutcome.Refused`.
3. `RabbitMqMqttPublisher` connects to `tcp://` or `ssl://` using the profile's configured `mqttPort`.
4. No credentials or secret keys are exposed in log messages or outcome descriptions.
5. All unit, integration, and BDD acceptance tests pass.

## Status
- **Accepted (2026-11-08):** Complete on branch `m6-04-mqtt-sender-engine`. All unit tests (`MqttMessagePublisherTest`, `RabbitMqMqttPublisherTest`) and Cucumber BDD scenarios (`RunMqttPublishingAcceptanceTest`) green. Zero regressions across core, data-rabbit, acceptance-tests, and ui.
