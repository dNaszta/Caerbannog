# Stream list view model (input note)

Milestone: M2    Depends on: M2-01b, M2-03 (decision D3 needed)    Size: M

Status: input note; the full brief is written when M2-03 is accepted.

## Inputs from the decisions of 2026-11-04
- Rows come from `StreamCatalog`; first offset comes lazily from `StreamOffsets` per visible row (D5 b). Row state for the offset: loading, value, `unavailable` (failed or denied call). Last offset = first offset + `messages` - 1, computed only when both are present; otherwise unavailable. The count is management `messages` and is always shown when the broker returned it (`messages` may be missing for about 5 s after creation: show n/a, not 0).
- The Size cell is always blank (N1 c); the view model carries no size.
- Capabilities (S3a, S3b): visibility NONE gives the state "cannot read stream statistics, ask for the monitoring tag" and no rows; the other levels list rows.
- Scenarios to make pass (all `@fake-broker`): in `03_stream_list.feature` the statistics scenario, "The message count and the offsets agree", "The first offset cannot be read for one stream", "A user without the management tag", filter, sort, refresh, empty, paging. The refresh policy is decision D3.
- Server-side paging with name filter; the app filters `type == "stream"` client side, so a page can hold fewer streams than `page_size`.

## Session API (after M2-01b)
Read `session.state` (`SessionState`: `statuses`, `current`); the old `statuses` and `current` flows are gone. A derived view model follows the session with `session.observe { }` (returns the unregister function; call it on close). Follow-up F1 from the M2-01b review: add to the KDoc of `observe` that observers must not throw, before adding a second observer.

## Connection states and refresh (decisions of 2026-11-04)
- The view model follows `ActiveConnection.state` (Disconnected, Connecting, Connected, Failed(reason)) next to the catalog: no rows and the state "Not connected" while Disconnected; a loading state while Connecting; rows when Connected; the failure reason and a Retry action when Failed.
- Lost connection (D2, proposed, awaiting confirmation): a catalog call failing with unreachable or timeout makes the connection Failed("Connection lost"); the list keeps its last rows, marked stale, with a banner and Retry; no automatic reconnect. Retry = connect again, then load.
- D3 refresh is still open and blocks this task. Recommendation: load once on Connect, a manual Refresh button, and the text "updated N s ago" (the `03` scenario "Refresh updates the statistics" already says this); no timer in M2, since statistics lag up to 5 s anyway and a timer adds load per visible row (one `queryStreamStats` each). Options: (a) manual only (recommended), (b) timer only, (c) both (a timer, default off, can come after M2).
- Scenarios (`@fake-broker`): `13_connection_lifecycle.feature` (core level) and `03` ones listed above.
