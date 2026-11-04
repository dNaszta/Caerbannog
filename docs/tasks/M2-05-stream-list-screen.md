# Stream list screen (input note)

Milestone: M2    Depends on: M2-04    Size: M

Status: input note; the full brief is written when M2-04 is accepted.

## Inputs from the decisions of 2026-11-04
- `docs/ux/Streams.dc.html` was updated: the Size column is kept but every cell shows a dash and the header has a tooltip ("not available from the broker, left blank until M3").
- Build the states: loading, rows, first offset loading and "unavailable" (cells read "unavailable", counts still shown), empty broker, and the denied state for a user without the management tag (message "This user cannot read stream statistics. Ask for the monitoring tag.", no table). Add the denied and unavailable states to the UX file if missing before building them (ask the architect).

## Connection picker and states (decisions of 2026-11-04)
- The sidebar connection picker shows each connection's state (Disconnected, Connecting, Connected, Failed with the reason) and the actions Connect and Disconnect for the current one; the interim edit-panel Connect button of M2-01 is removed. Connect on another profile disconnects the held one (one held connection at a time).
- The `02` scenario "Switching connection shows the permission state of the new current connection" and the deferred acceptance scenario "Switch between connections" (`SwitchBetweenConnectionsSteps`) are un-skipped here: switching means Connect to the other profile.
- Failed Connect: the reason as one plain line and a Retry button; the stream area shows "Not connected". Lost connection: banner "Connection lost" with Retry over the stale rows (D2 proposed).
- The connection panel shows the one-line note "Host name is not verified." (D7 (a)). There is no Connections mockup in `docs/ux/`; the architect adds the note and the connection states to the UX set when M2-05 is briefed in full.
- Scenarios: `13_connection_lifecycle.feature` (UI level steps), `03`, `02` as above.
