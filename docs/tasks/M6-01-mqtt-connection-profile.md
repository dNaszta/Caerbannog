# M6-01: MQTT port in ConnectionProfile, validation, and profile form UI

Milestone: M6    Depends on: M5-04 (merged)    Size: S    Branch: `m6-01-mqtt-connection-profile`

## Goal
Add dedicated MQTT port support (`1883` plain / `8883` TLS) to connection profile management across `core`, `data-rabbit`, and `ui`:
1. **Core Domain (`core`):**
   - Add `mqttPort: Int` to `ConnectionProfile` (default: 8883 when `tls == true`, 1883 when `tls == false`).
   - Add `mqttPort: Int?` to `ConnectionProfileInput`.
   - Update `BrokerPorts` and `BrokerEndpoints` to handle default MQTT ports and TLS switch transitions.
   - Update `ConnectionsViewModel.addConnection` and `updateConnection`.
2. **Data Layer (`data-rabbit`):**
   - Update `JsonFileConnectionProfileRepository.ConnectionProfileDto` with optional `mqttPort: Int?` for backwards compatibility with existing stored profiles.
3. **UI Layer (`ui`):**
   - Update `ConnectionFormState` to include `ports.mqtt` in `BrokerPorts` and `toInput()`.
   - Add `ReadOnlyPortField` for MQTT port in `ConnectionsScreen.kt` edit panel (following the TLS switch).
4. **Verification & Tests:**
   - Unit tests in `BrokerEndpointsTest`, `ConnectionsViewModelTlsTest`, `ConnectionFormStateTest`, `JsonConnectionProfileRepositoryTest`.
   - Compose UI tests in `ConnectionsScreenTlsUiTest`.

## Scope
### In
- `ConnectionProfile.mqttPort`, `ConnectionProfileInput.mqttPort`.
- `BrokerPorts.mqtt`, `BrokerEndpoints.defaultsFor`, `BrokerEndpoints.afterTlsSwitch`.
- Persistence and backward compatibility in `JsonFileConnectionProfileRepository`.
- Edit panel display in `ConnectionsScreen`.
- Unit and UI test coverage.

### Out
- MQTT Topology management (M6-02).
- MQTT Publishing engine (M6-04).

## Acceptance Criteria
1. With TLS on and MQTT enabled, connection profile has default MQTT port `8883`.
2. Switching TLS to off transitions default MQTT port from `8883` to `1883` (and vice versa).
3. Custom ports are preserved on TLS toggling per `BrokerEndpoints.afterTlsSwitch`.
4. Older JSON profile files without `mqttPort` load cleanly with `mqttPort = null` (`hasMqtt == false`), treating MQTT as an optional plugin capability.
5. Edit panel in `ConnectionsScreen` renders an MQTT plugin toggle and, when enabled, the MQTT port field appropriately labeled.
6. All unit tests, Compose UI tests, and `./gradlew test` pass.

## Status
- **Accepted (2026-11-07):** Implemented in branch `m6-01-mqtt-connection-profile`. All unit tests, UI tests, and repository tests green.
