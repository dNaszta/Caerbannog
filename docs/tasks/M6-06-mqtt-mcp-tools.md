# M6-06: MCP tools: caerbannog_list_mqtt_clients & caerbannog_publish_mqtt

Milestone: M6    Depends on: M6-05 (merged)    Size: M    Branch: `m6-06-mqtt-mcp-tools`

## Goal
Extend the Model Context Protocol (MCP) server with two new native MQTT inspection and test publishing tools:
1. **Tool `caerbannog_list_mqtt_clients`:**
   - **Description:** List active MQTT client sessions, connected protocols, peer addresses, and topic subscriptions with QoS levels on a connection profile.
   - **Arguments:**
     - `profile`: (string, required) Connection profile name.
     - `vhost`: (string, optional) Virtual host (defaults to profile's configured virtual host).
   - **Output:** JSON summary of client sessions including clientId, username, peerHost, peerPort, protocol, cleanSession, and subscriptions (`topic`, `qos`).
   - **Guardrails:** Validates profile existence and reachability. Returns descriptive error if MQTT is not configured on the profile.
2. **Tool `caerbannog_publish_mqtt`:**
   - **Description:** Publish a test message over MQTT with QoS and retain flags to a configured connection profile.
   - **Arguments:**
     - `profile`: (string, required) Connection profile name.
     - `topic`: (string, required) MQTT topic path (e.g. `sensors/room1/temperature`).
     - `payload`: (string, required) Message payload text or JSON.
     - `qos`: (integer, optional, default 0) Quality of Service level: 0, 1, or 2.
     - `retain`: (boolean, optional, default false) Retain flag for the broker.
   - **Output:** JSON confirmation of published message.
   - **Guardrails:**
     - Validates profile exists, has MQTT configured (`hasMqtt == true`), and is reachable.
     - Enforces write permission and non-`READ_ONLY` topology control level.
     - Enforces MQTT topic syntax: topic cannot be blank; topic cannot contain wildcards (`+`, `#`).
     - Enforces valid QoS (0, 1, 2).
3. **Server Wiring & Main (`mcp-server`):**
   - Provide `mqttCatalogProvider` and `mqttMessagePublisherProvider` to `McpServer`.
   - Wire in `Main.kt` via `HeldConnection.mqtt` and `HeldConnection.mqttPublisher`.
4. **Verification & Tests:**
   - Unit tests in `McpServerTest.kt` verifying both tools, error handling, permission checks, topic validation, and output formatting.
   - Acceptance BDD scenarios in `14_mcp_server.feature` and `McpSteps.kt`.

## Scope
### In
- MCP tool declarations and handlers in `mcp-server`.
- Wiring into `Main.kt`.
- Unit tests in `mcp-server/src/test/kotlin/dev/caerbannog/mcp/McpServerTest.kt`.
- Acceptance BDD scenario in `tests/bdd/features/14_mcp_server.feature`.

### Out
- Non-MQTT MCP changes.

## Acceptance Criteria
1. `tools/list` returns `caerbannog_list_mqtt_clients` and `caerbannog_publish_mqtt`.
2. Calling `caerbannog_list_mqtt_clients` returns the client list with subscriptions and QoS.
3. Calling `caerbannog_publish_mqtt` publishes via `MqttMessagePublisher` and returns success confirmation.
4. Calling `caerbannog_publish_mqtt` with wildcards in topic, invalid QoS, or on `READ_ONLY` profiles is refused.
5. All unit and acceptance tests pass.

## Status
- **Accepted (2026-11-08):** Completed and verified on branch `m6-06-mqtt-mcp-tools`. All unit tests (`:mcp-server:test`) and acceptance BDD scenarios (`14_mcp_server.feature`) pass.
