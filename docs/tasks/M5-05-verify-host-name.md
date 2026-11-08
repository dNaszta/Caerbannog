# M5-05: Per-profile "Verify host name" switch (D7)

Milestone: M5    Depends on: M5-04 (merged), M6 (merged)    Size: M    Branch: `m5-05-verify-host-name`

## Goal
Implement a per-profile "Verify host name" switch (product decision D7):
- Default is `false` (off), maintaining backwards compatibility and allowing local / dev broker connections where the server certificate Subject Alternative Name (SAN) does not match `localhost`.
- When toggled `true` (on), strict TLS hostname verification is enforced across Management HTTP, the Stream protocol client, reachability checks, and MQTT over TLS.
- In the UI, the note `"Host name is not verified."` is shown only when TLS is on and `verifyHostName` is false. When `verifyHostName` is true, the note is hidden.
- The switch is persisted in `ConnectionProfile` and `JsonFileConnectionProfileRepository`.

## Scope
### In
1. **Core domain:**
   - Add `val verifyHostName: Boolean = false` to `ConnectionProfile`.
   - Add `val verifyHostName: Boolean? = null` to `ConnectionProfileInput`.
   - Update `EditPanelNotes`: `hostNameNotVerified` is non-null only when `tls == true && !verifyHostName`.
   - Update `ConnectionsViewModel.addConnection` and `updateConnection` to handle `verifyHostName`.
2. **Data & RabbitMQ transport:**
   - Update `BrokerTransport.Tls` to hold `val verifyHostName: Boolean`.
   - Update `TlsSupport.managementSslContextOf`: wrap in `NameTolerantTrustManager` only when `verifyHostName == false`; otherwise use standard trust managers.
   - Update `StreamEnvironments`: set `endpointIdentificationAlgorithm("HTTPS")` when `verifyHostName == true`, or `null` when `false`.
   - Update `StreamAndManagementReachabilityChecker`: set `endpointIdentificationAlgorithm = "HTTPS"` on the stream SSL socket when `verifyHostName == true`.
   - Update `RabbitMqMqttPublisher`: honour `verifyHostName` when configuring the TLS socket factory.
   - Update `JsonFileConnectionProfileRepository`: serialize/deserialize `verifyHostName` (omit when false for backward compatibility).
3. **UI (Compose):**
   - Add `var verifyHostName` state in `ConnectionFormState`.
   - Add "Verify host name" toggle switch row in `ConnectionsScreen.kt` edit panel when `tls` is enabled.
4. **Verification & Tests:**
   - Unit tests in `core` for notes, inputs, and view model.
   - Unit tests in `data-rabbit` for persistence and TLS contexts.
   - Compose UI tests in `ui` verifying the toggle, persistence in form, and note visibility.
   - Acceptance BDD scenarios in `01_connections.feature`.

### Out
- Modifying plain HTTP/stream connections (they never use TLS or hostname verification).

## Acceptance Criteria
1. `ConnectionProfile` has `verifyHostName: Boolean = false` by default.
2. Saving a profile with "Verify host name" enabled persists `verifyHostName = true`.
3. In the UI edit panel with TLS on, toggling "Verify host name" on hides `"Host name is not verified."`; toggling it off restores the note.
4. With "Verify host name" on, connecting to a broker whose certificate does not match the host fails hostname verification.
5. All test suites pass.

## Status
- **Accepted (2026-11-08):** Completed and verified on branch `m5-05-verify-host-name`. All unit tests, UI tests, and BDD scenarios pass.
