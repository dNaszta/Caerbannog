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
- D3 refresh: see "Refresh policy (D3)" below; it no longer blocks this task.
- Scenarios (`@fake-broker`): `13_connection_lifecycle.feature` (core level) and `03` ones listed above.

## Refresh policy (D3, product owner input of 2026-11-04, details proposed)
Product owner: the refresh interval is set in seconds, in the app bar connection control (see `M2-05-stream-list-screen.md`), next to a manual Refresh and "Updated N s ago". The architect fills in the rest as a proposal, to be confirmed (questions in `docs/tasks/README.md`, D3). None of it blocks M2-03 or the start of M2-04.
- Behaviour: load on Connect; manual "Refresh now" at any time; with an interval set, a timer reloads the current page every N seconds while Connected and the Streams screen is shown. "Updated N s ago" counts from the last successful load. A refresh that is still running is never overlapped by the next tick (skip the tick); a failed refresh keeps the old rows, marks them stale and does not stop the timer; a lost connection stops it (D2). Changing the page, filter or sort reloads at once and restarts the timer.
- Proposed default: Off. Why: the statistics lag up to about 5 s, so intervals under 5 s show nothing new; each tick costs one list call plus one `queryStreamStats` per visible row, and the default should be silent against a production broker (the user chose when to look). Proposed values: Off, 5, 10, 30, 60 s (5 s is the lowest that is not noise).
- Proposed scope: per app, not per profile. Why: it is a viewing preference, and a per-profile value would add a field to the persisted profile JSON (needs a DTO field with a default and a back-compat test as M1-06 did). Persistence (in M2-05, not here): the smallest option is a new `settings.json` next to the profiles file with one key `streamListRefreshSeconds` (0 = off, missing = off), read and written by a small `AppSettings` interface in `core` with a JSON file implementation in `data-rabbit` (same pattern as the profile repository: atomic write, tolerant read, unknown keys ignored). If the product owner prefers per profile, it is instead an optional field `streamListRefreshSeconds` (default 0) on the profile DTO plus a back-compat test that a file without it still loads.
- In this task: a `RefreshPolicy` in `core` (interval value, injectable clock and ticker so tests use virtual time, no sleeps), the "updated N s ago" derivation, and the scenarios "Refresh updates the statistics" and the two interval scenarios in `03` (`@needs-decision` until the product owner confirms default, values and scope). The value is in memory only here; the real file is M2-05.
