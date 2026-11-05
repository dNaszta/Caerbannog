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
- Offsets are shown as plain digits, message counts grouped (product owner 2026-11-04, "it should be plain"): update the mockup `docs/ux/Streams.dc.html` (it groups offsets) and the other mockups that show offsets.
- UI dispatcher (verify finding F-2 of M2-04): the `StreamListViewModel` public methods (`setShown`, `refreshNow`, `retry`, `setFilter`, `setSort`, paging, `setRefreshInterval`, `close`) must not race with the view model's scope. This task passes a scope on the UI dispatcher (`Dispatchers.Main`, Compose's), or the public methods hop onto the scope with `scope.launch`; build in `desktopApp` composition and add a test that calls them from another thread. The core has no dispatcher knowledge.
- D2 is decided: the lost-connection scenario in `13` (now `@needs-M2`) is un-skipped here, with the banner "Connection lost", stale rows and Retry.
- Copyable values (product owner 2026-11-04; scope CONFIRMED by the user 2026-11-05: all list and detail values): stream name, offsets, counts, retention, leader and node are selectable text; copy gives exactly the displayed string (retention `7D`, not `604800s`). Use the Compose `SelectionContainer` around the table and the detail panel and a UI test for the retention cell. Keyboard: a focused cell copies with the platform copy shortcut. Add it to the UX file notes if missing.
- The `02` scenario "Switching connection shows the permission state of the new current connection" and the deferred acceptance scenario "Switch between connections" (`SwitchBetweenConnectionsSteps`) are un-skipped here: switching means Connect on the other profile in the popup.
- The connection panel shows the one-line note "Host name is not verified." (D7 (a)).
- Scenarios: `13_connection_lifecycle.feature` (the three app bar scenarios at the end, `@needs-M2`, un-skip here; the earlier ones run in M2-03 at core level), `03` (refresh, interval), `02` as above.
- The refresh interval setting and its persistence are in "Refresh policy (D3)" of `M2-04-stream-list-core.md`; this task renders the control and persists the value: decided per app, `settings.json` next to the profiles file, `AppSettings` file implementation in `data-rabbit` (with tests: atomic write, tolerant read, missing file and unknown value read as the default). The default interval is DECIDED: 5 s (product owner 2026-11-04, "try with that, after we can change"; one constant; the chip "5 s" is selected on a fresh install, and `docs/ux/Streams.dc.html` shows 30 s only as a mock state). Because of it polling starts on connect and a tick must obey "latest started check wins": Disconnect, switching connection or a superseding open cancels the timer and drops a late result (see `M2-04-stream-list-core.md`, "Refresh policy"); the screen test needs a case for it. The lost-connection rule (D2) is DECIDED (2026-11-04).
- The mockups of the other screens (Browser, Clients, Publish, Migrations, Search, Topology, Main) carry the closed chip and no drawer card; the open popup is drawn only in `Streams.dc.html`. No Connections mockup state for the chip states beyond the table above; build from the table.

## Inputs from the M2-04 architect review (2026-11-05)
- Acceptance line (F-2): a test or a documented rule proves that no public method of `StreamListViewModel` is called from a thread other than the one its scope runs on (the scope is the UI dispatcher; `kotlinx-coroutines-swing` is APPROVED by the user, 2026-11-05, desktopApp only, version equal to the coroutines version in use). `limitedParallelism(1)` in the composition root is only a placeholder until then.
- Text: change `StreamListTexts.LOAD_FAILED` to something like "The stream list could not be loaded. Check that the virtual host exists and that this user may use it." RabbitMQ 4 answers 404 for a vhost the user cannot access, and the app cannot tell "missing" from "forbidden"; the generic failure state is kept, only the wording improves.
- Reword the four remaining "D2 proposed" comments (`ActiveConnection`, `StreamListViewModel`, `LinkState`, `StreamListViewModelConnectionTest`) when touching them.
