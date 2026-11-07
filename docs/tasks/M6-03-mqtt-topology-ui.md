# M6-03: MQTT Tab in Topology screen (Client Sessions, Subscriptions, QoS)

Milestone: M6    Depends on: M6-02 (merged)    Size: M    Branch: `m6-03-mqtt-topology-ui`

## Goal
Implement the Compose UI for the MQTT tab on the Topology screen in `ui`:
1. **MQTT Clients Table (`MqttClientsTable`):**
   - Render columns: Client ID, User, Protocol, Peer Address, Connected, Clean Session, and Subscriptions.
   - Status badge / indicator for active connections vs offline durable sessions.
   - Subscription chips/badges displaying topic and QoS level (`QoS 0`, `QoS 1`, `QoS 2`).
   - Copy action for Client ID and topics.
2. **Tab Integration:**
   - Wire `MqttClientsTable` into `TopologyTabsContent.kt` under `TopologyTab.Mqtt`.
   - Ensure loading and empty state representations match the existing tables.
3. **Verification & Tests:**
   - Compose UI tests in `TopologyMqttUiTest.kt`:
     - Tab switching to MQTT when `hasMqtt == true`.
     - Table renders columns and rows for active and offline clients.
     - Subscriptions display with their topics and QoS badges.
     - Search filter works against the table.
     - Empty message rendered when no clients exist.

## Scope
### In
- `MqttClientsTable.kt` in `ui/src/commonMain/kotlin/dev/caerbannog/ui/topology/`.
- Subscription badge component (`MqttSubscriptionBadge`).
- Integration into `TopologyTabsContent.kt`.
- Compose UI test suite in `TopologyMqttUiTest.kt`.

### Out
- MQTT Publishing Dialog (M6-05).

## Acceptance Criteria
1. The MQTT tab displays in `TopologyTabs` only when `state.hasMqtt == true`.
2. `MqttClientsTable` displays all client rows from `state.mqttClients`.
3. Active connections show connected indicator and peer socket; offline sessions indicate "Offline".
4. Subscriptions render with their topic path and QoS badge.
5. All UI tests pass.

## Status
- **Accepted (2026-11-07):** Complete on branch `m6-03-mqtt-topology-ui`. All 343 UI tests pass including `TopologyMqttUiTest`.
