# M5-06: Milestone M5 gate and packaging verification

Milestone: M5    Depends on: M5-01..M5-05 (merged)    Size: M    Branch: `m5-06-gate-packaging-and-broker`

## Goal
Verify Milestone M5 capabilities against a real RabbitMQ 4 broker (`@gate-M5`) and verify desktop distribution packaging:
1. **Real Broker Stream Management (M5-03):**
   - Create a stream via `StreamManager` with retention settings (`x-max-age`).
   - Verify the stream is created and reports correct retention.
   - Delete the stream with typed confirmation and verify it is removed from the broker.
2. **Real Broker Test Message Publishing (M5-01 / M5-02):**
   - Publish a message to a stream using `RabbitMqMessagePublisher`.
   - Verify the message is delivered, offsets increment, and the message content is readable.
   - Publish a message to an exchange with routing key to a queue.
3. **Real Broker MQTT Publishing & Active Client Tracking (M6 & M5 Gate):**
   - Publish MQTT messages with QoS (0, 1, 2) and Retain flag to real broker.
   - Connect active MQTT subscriber and verify session, QoS, and topic subscription discovery via Management API.
   - Test both plain (1883) and TLS (8883) with hostname verification.
4. **Real Broker Topology Migrations (M5-04):**
   - Run declarative topology migrations (`TopologyMigrationRunner`) against the real broker.
   - Verify exchanges, queues, and bindings are created in the target vhost.
5. **Desktop Packaging Verification:**
   - Execute `./gradlew :desktopApp:packageDmg` on macOS to ensure the DMG installer builds cleanly without dependency or packaging errors.

## Scope
### In
- Real broker integration test in `data-rabbit` (`RealBrokerM5GateTest.kt` tagged `@Tag("broker")`).
- Execution of packaging task `:desktopApp:packageDmg`.
- Update `docs/spec.md` and `docs/tasks/README.md`.

### Out
- New product features (this is the milestone completion and gate task).

## Acceptance Criteria
1. `RealBrokerM5GateTest` in `data-rabbit` passes against the real broker (`./gradlew :data-rabbit:brokerTest`).
2. `./gradlew :desktopApp:packageDmg` completes successfully and produces the `.dmg` installer.
3. All unit, UI, and BDD acceptance test suites pass.
4. Milestone M5 is marked completed and accepted.

## Status
- **Accepted (2026-11-08):** Branch `m5-06-gate-packaging-and-broker`.
  - All 5 gate tests in `RealBrokerM5GateTest.kt` passed against live broker (`./gradlew :data-rabbit:brokerTest`):
    1. Stream creation, catalog listing, and deletion over stream protocol.
    2. Message publication to stream with properties and browsing back with offset progression.
    3. Plain MQTT (port 1883) QoS 1 / Retain publish with active client session discovery.
    4. TLS MQTT (port 8883) QoS 2 publish with CA validation.
    5. MQTT-to-stream integration: publishing on MQTT topic routed through `amq.topic` into stream queue, browseable and verifiable via Stream Message Reader.
  - Native desktop packaging verified: `./gradlew :desktopApp:packageDmg` produced `desktopApp/build/compose/binaries/main/dmg/Caerbannog-1.0.0.dmg` (125 MB).
