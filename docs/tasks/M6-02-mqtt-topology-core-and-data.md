# M6-02: Management API MQTT parser, MqttCatalog, and TopologyViewModel MQTT tab

Milestone: M6    Depends on: M6-01 (merged)    Size: M    Branch: `m6-02-mqtt-topology-core-and-data`

## Goal
Add MQTT topology inspection to `core` and `data-rabbit`:
1. **Core Domain (`core`):**
   - MQTT domain models: `MqttSubscription(topic, qos)`, `MqttClientRow(clientId, connectionName, vhost, username, peerHost, peerPort, protocol, connectedAt, ssl, cleanSession, subscriptions, connected)`.
   - `MqttCatalog` interface (`listClients(vhost: String?): CatalogResult<List<MqttClientRow>>`) and `UnavailableMqttCatalog`.
   - `GuardedMqttCatalog` proxy in `StreamSource`.
   - Wire `mqtt: MqttCatalog` into `HeldConnection` and `StreamSource`.
   - Add `TopologyTab.Mqtt` to `TopologyTab`, `hasMqtt: Boolean` and `mqttClients: List<MqttClientRowView>` to `TopologyState`.
   - Update `TopologyViewModel` to load, search, filter, and page MQTT clients when on `TopologyTab.Mqtt`.
2. **Data Layer (`data-rabbit`):**
   - Add `connections()` endpoint to `ManagementRequests`.
   - Add `ManagementJson.parseMqttClients(connectionsJson, bindingsJson, vhost)`:
     - Filter connections where `protocol` starts with `"MQTT"`.
     - Extract `clientId` from `client_properties.client_id` (or fallback to `connection_name` or connection name).
     - Associate with subscriptions from `amq.topic` bindings whose destination queues match `mqtt-subscription-<clientId>...`.
     - Convert AMQP routing key format (`.` and `*`) back to MQTT topic format (`/` and `+`).
   - Implement `ManagementMqttCatalog(http, requests): MqttCatalog`.
   - Wire `ManagementMqttCatalog` into `RabbitMqConnectionGateway` and `RabbitMqHeldConnection`.
3. **Test Doubles & Tests (`core-testing`, `core`, `data-rabbit`):**
   - Test double `FakeMqttCatalog` in `core-testing`.
   - Unit tests in `ManagementMqttCatalogTest` and `ManagementJsonMqttTest`.
   - Unit tests in `TopologyViewModelMqttTest`.

## Scope
### In
- MQTT domain types and `MqttCatalog` contract.
- Gateway and `StreamSource` wiring.
- Management API connection & subscription parsing in `data-rabbit`.
- `TopologyViewModel` state management, loading, searching, and paging for MQTT clients.
- Fake doubles and unit test coverage.

### Out
- Compose UI rendering for MQTT Tab (M6-03).
- MQTT message publishing (M6-04 / M6-05).

## Acceptance Criteria
1. `MqttCatalog.listClients(vhost)` parses connections with protocol starting with `"MQTT"`.
2. MQTT subscriptions are mapped from queue bindings (`mqtt-subscription-...`) to topics with proper wildcard translation (`.` -> `/`, `*` -> `+`) and QoS level.
3. `TopologyViewModel` sets `hasMqtt = true` on `TopologyState` when the connection profile has `hasMqtt == true`.
4. Switching to `TopologyTab.Mqtt` triggers loading via `src.mqtt.listClients(target)`.
5. Searching filters MQTT clients by client ID, username, or subscribed topic.
6. Paging applies to the filtered list of MQTT clients.
7. All tests in `core` and `data-rabbit` pass.

## Status
- **Accepted (2026-11-07):** Implemented in branch `m6-02-mqtt-topology-core-and-data`. All unit tests, parser tests, catalog tests, and UI tests green.
