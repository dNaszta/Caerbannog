# Stream list screen (input note)

Milestone: M2    Depends on: M2-04    Size: M

Status: input note; the full brief is written when M2-04 is accepted.

## Inputs from the decisions of 2026-11-04
- `docs/ux/Streams.dc.html` was updated: the Size column is kept but every cell shows a dash and the header has a tooltip ("not available from the broker, left blank until M3").
- Build the states: loading, rows, first offset loading and "unavailable" (cells read "unavailable", counts still shown), empty broker, and the denied state for a user without the management tag (message "This user cannot read stream statistics. Ask for the monitoring tag.", no table). Add the denied and unavailable states to the UX file if missing before building them (ask the architect).

## Connection control in the app bar (product owner idea of 2026-11-04, refined by the architect; mockups `docs/ux/Streams.dc.html` (popup open) and the closed chip in every other `docs/ux/*.dc.html`)
Replaces the sidebar picker idea, the drawer "Connection" card and the interim edit-panel Connect button of M2-01.

**The chip.** One fixed button at the right end of the app bar on every screen (last item, after the screen's own actions). It shows: a state icon, the current connection's name (ellipsis when long), the permission pill (same pill component and wording as the Connections screen: "Can change topology", "Can change topology (declared)", "Topology locked", and so on), and a drop-down arrow. Fixed means it never moves, never scrolls away and is present on every screen, including Connections.

| Chip state | Icon | Text and pill | Notes |
| --- | --- | --- | --- |
| No connection (nothing current) | `link_off`, dim | "No connection", no pill | Opens the popup like any other state |
| Connecting | `sync` | name, pill hidden until the permission check answered | the popup row says "Connecting" |
| Connected | `link` | name and permission pill | |
| Failed | `error`, error colour border | name, reason in the popup row and as the chip tooltip; no pill | popup row offers Retry |
| Disconnected (a current connection that was disconnected, or "Connection settings changed. Connect again.") | `link_off` | name, "Disconnected" | popup row offers Connect |

**The popup** (a menu anchored under the chip, role `menu`; content in this order):
1. Section "Connections": every saved profile, one row each: state icon, name, state text (Connected, Disconnected, Connecting, "Failed: <reason>"), and one action: Disconnect on the connected one, Connect on a Disconnected one, Retry on a Failed one, none while Connecting. The current row is highlighted. Connect on another profile disconnects the held one (one held connection at a time, spec "Connection lifecycle"). With no saved profile the popup says "No saved connections" and a "New connection" action that opens the Connections screen.
2. Section "Stream list refresh": "Refresh every" with the choices Off, 5 s, 10 s, 30 s, 60 s (single choice chips); a "Refresh now" button; the text "Updated N s ago" (or "Not loaded yet"). The refresh section is shown on every screen but is disabled with the note "Connect to refresh" while not Connected. The one-click Refresh icon and "Updated N s ago" stay on the Streams screen app bar as a shortcut to the same action and the same state; they are not a second setting.
3. Footer action "Manage connections" opens the Connections screen (add, edit, delete, check stay there).

**Keyboard and access.** The chip is a normal tab stop, with the accessible label "Connection <name>, <state>, <permission>. Open connection menu" (state and permission spoken, not colour only). Enter or Space opens it; Escape closes it and returns focus to the chip; Up and Down move between rows and the refresh choices; Enter on an action row runs it and keeps the popup open for Connect, Disconnect and Retry so the new state is visible (the user closes it with Escape or a click outside); focus moves into the popup on open. Colour is never the only signal (icon and text always).

**Fate of the old pieces.** The drawer "Connection" card is REMOVED from the drawer (it duplicated name, pill and host; the chip now carries name, state and pill, and the host is a row detail in the Connections screen). The drawer keeps search and the four navigation items. The edit-panel Connect button of M2-01 is removed in this task; "Check connection" stays. The Connections screen profile cards keep their state line (Connected / Not connected) as a read-only summary driven by the same `ActiveConnection.state`; it has no Connect button (the chip is the one place that connects). Failed Connect: the reason as one plain line in the popup row and the tooltip, a Retry action there, and the stream area shows "Not connected". Lost connection: banner "Connection lost" with Retry over the stale rows (D2 proposed).

**Why a chip and not the drawer card.** The connection decides what every screen may do (topology locked, no publishing), so it must be visible and reachable from every screen without a navigation step. The product owner asked for it in the top right.

## Other inputs
- The `02` scenario "Switching connection shows the permission state of the new current connection" and the deferred acceptance scenario "Switch between connections" (`SwitchBetweenConnectionsSteps`) are un-skipped here: switching means Connect on the other profile in the popup.
- The connection panel shows the one-line note "Host name is not verified." (D7 (a)).
- Scenarios: `13_connection_lifecycle.feature` (the three app bar scenarios at the end, `@needs-M2`, un-skip here; the earlier ones run in M2-03 at core level), `03` (refresh, interval), `02` as above.
- The refresh interval setting and its persistence are in "Refresh policy (D3)" of `M2-04-stream-list-core.md`; this task renders the control and persists the value: decided per app, `settings.json` next to the profiles file, `AppSettings` file implementation in `data-rabbit` (with tests: atomic write, tolerant read, missing file and unknown value read as the default). The default interval (Off) and the lost-connection rule (D2) are PROPOSED and not yet confirmed by the product owner: build them as flagged defaults, one constant each, and re-check with the architect before this task is accepted.
- The mockups of the other screens (Browser, Clients, Publish, Migrations, Search, Topology, Main) carry the closed chip and no drawer card; the open popup is drawn only in `Streams.dc.html`. No Connections mockup state for the chip states beyond the table above; build from the table.
