# Connection holder and stream catalog: core interfaces, fakes and real clients

Milestone: M2    Depends on: M2-01b (merged), M2-02 (merged), M2-03a mini-spike (first step); decisions D1 (b), D6 (a), D7 (a) taken    Size: L; split from the start into M2-03 part 1 (connection holder and lifecycle) and part 2 (catalog, offsets) if the developer prefers two branches

Branch: `m2-03-stream-gateway` from `main` after M2-01b and M2-02 are merged.

## Goal
The app can Connect to and Disconnect from a profile: `core` has the connection lifecycle and the `StreamCatalog` and `StreamOffsets` interfaces with fakes; `data-rabbit` has the holder that owns the management HTTP client and the stream `Environment`, and real implementations that list streams with statistics, read publishers and consumers, detect the missing plugin and the caller's visibility level, each failure a typed result. No UI.

## Why
`docs/spec.md` "Management API facts (M2-02 spike)" and Goals (stream list, publishers and consumers); `03_stream_list.feature`, `04_publishers_consumers.feature`, `11_global_search.feature`. M2-04 (view model), M2-06 (tab) and M2-09 (gate) need it.

## Connection model (decisions D1 (b), D2 proposed, D6 (a), D7 (a); `docs/spec.md` "Connection lifecycle")
What "connected" means: an explicit held connection. State per connection: `Disconnected`, `Connecting`, `Connected`, `Failed(reason)`; no Disconnecting state (close is immediate).
- `core` (no data-rabbit types): `ConnectionGateway { suspend fun open(profile): OpenResult }` where `OpenResult` is `Opened(HeldConnection)` or `Failed(reason)`; `HeldConnection : AutoCloseable` exposes `catalog: StreamCatalog` and `offsets: StreamOffsets`; a core class (name it `ActiveConnection`) holds `state: StateFlow<LinkState>` (name and phase) and the current `HeldConnection`, with `connect(profile)`, `disconnect()`, `close()`. It uses `ConnectionSession` for select and the permission check and follows the session's rules (latest started wins, a connect superseded by another connect or a disconnect closes what it opened). Same shape as the session: one immutable state, one StateFlow, observers must not throw (F1).
- `connect(profile)` order: (1) if another connection is held, close it and set it Disconnected (one held connection at a time, why: spec); (2) `session.select(name)`; (3) `Connecting`; (4) permission check through the session (`open`); (5) `gateway.open(profile)`; (6) `Connected` or `Failed(reason)`. `ConnectionsViewModel.connect(name)` (interim entry point of M2-01) calls this; the edit panel's "Check connection" stays a check only and holds nothing.
- Failed(reason) when: the broker is not reachable, TLS is refused, there is no stored password, or credentials are refused (reason text from the management 401; M2-03 records the exact 401 reason for a wrong password versus "Not management user" and tests both). Not Failed: Unreadable permissions for a non-admin (Connected, safest mode), a stream-protocol failure (Connected, offsets "unavailable"), management 401 for a user without the management tag (Connected, `Capabilities.visibility` NONE, message per S3a). Reasons are plain text without host secrets, never the password.
- `data-rabbit`: a per-connection holder created by `gateway.open`: reads the password from `SecretStore` once (D6), builds the Ktor management client with the profile's CA, host name NOT verified (D7), and creates the stream `Environment` lazily on the first first-offset call (stream-client trust: the same CA, host name not verified if the client allows it; see M2-03a). `close()` closes the HTTP client and the `Environment`, forgets the password reference, and is idempotent.
- Closed on: Disconnect; Connect on another profile; delete of the held profile; edit or rename of the held profile (state Disconnected with the note "Connection settings changed. Connect again."); window close (composition root `onCloseRequest` calls `close()`); a failed Connect (anything opened is closed); cancellation of a Connect.
- Lost connection (D2, proposed, awaiting confirmation): a data call that fails with unreachable or timeout sets `Failed("Connection lost")` and closes the holder; no automatic reconnect in M2; Retry is Connect again. A 401 on a data call is `Failed("Credentials were refused")`. The typed catalog errors carry this so the view model does not parse strings.
- Composition root (`desktopApp`) wires `ConnectionGateway` to the `data-rabbit` implementation and calls `close()` on window close; the UI never sees `data-rabbit`, Ktor or the stream client.

## Scope
In (all derived from the spike; field names and types are in the fixtures, do not guess):
- The connection lifecycle above, with a `FakeConnectionGateway` (configurable success, failure reason, slow open for overlap tests) and tests first in `core`.

- `core` (no RabbitMQ imports, no JVM-only types): `StreamCatalog` with, in prose, `listStreams(request)` (page, page size up to 500, optional name filter with optional regex; returns rows plus the pagination numbers; the client filters `type == "stream"`), `streamDetail(vhost, name)`, `publishers(vhost, stream)`, `consumers(vhost, stream)` (filtered client side on `queue.name` from `/api/stream/consumers/{vhost}`), `capabilities()`. Immutable data classes `StreamSummary` (name, vhost, messages nullable, consumers, segments, firstTimestamp, leader, node, members, online, retention as parsed arguments), `PublisherRow`, `ConsumerRow`, `Capabilities` (plugin present yes/no/unknown; visibility FULL, OWN_CONNECTIONS_ONLY, NONE). Typed errors as a sealed result: `NotAuthorised` (401), `PluginMissing` (404 with the not_found body from `/api/stream/publishers`), `BadPaging` (400), `Unreachable`, `Unexpected`.
- `FakeStreamCatalog` in core test fixtures, built from the recorded JSON bodies (`data-rabbit/src/test/resources/fixtures/m2-02/`; copy or share through a test-fixtures source set, say which).
- Real implementation in `data-rabbit`: Ktor Java engine, TLS only with the profile's CA handling already used by the permission check, basic auth with the password the holder read from `SecretStore` (D6 (a)); the password is never stored in `SessionState`, logs or error strings (a test checks the error and log text of a failed Connect). JSON with kotlinx-serialization, `ignoreUnknownKeys`; `messages` optional.
- Capability detection: plugin via `GET /api/stream/publishers` (404 with the JSON body means missing; per-stream paths are not usable for detection: 400 empty body); visibility via `GET /api/whoami` tags (`monitoring` or `administrator` = FULL, `management` = OWN_CONNECTIONS_ONLY, none = 401 = NONE). Confirm in the test that whoami returns tags for `management` and `monitoring` (M1-03 recorded it; the spike did not repeat it).
- Super stream detection is NOT here (M2-07); leave the gateway method out.
- Decided D5 (b) (product owner, 2026-11-04): first offset is shown in the M2 list. `StreamOffsets` interface in `core` (`firstOffset(vhost, stream)` returning a typed result: value, `Unavailable` for a failed or denied call), real implementation in `data-rabbit` over `com.rabbitmq:stream-client` as a PRODUCTION `implementation` dependency (TLS only; the trust handling must reuse the profile's CA decision, not a trust-all context). One `queryStreamStats` call per visible row, issued lazily and limited in parallelism, one `Environment` per held connection, closed with the holder. When the call fails or is denied the catalog row is still returned with the first offset marked unavailable; the list never fails because of it. Browsing safety: `queryStreamStats` must be shown by a test to write nothing (`messages` and `tracking` unchanged).
- Dependency: move `stream-client` to `implementation` in `data-rabbit/build.gradle.kts` as its own first commit. Version decision (architect, 2026-11-04): pin 1.12.0 (published 2026-10-09, passes the two-week rule, and it is the version the spike tested). 1.13.1 was published 2026-11-03 and would only qualify after 2026-11-17; do not chase it in M2-03. A bump to 1.13.x is a separate dependency change after 2026-11-17 with the `brokerTest` rerun, decided by the product owner. If M2-03 starts after 2026-11-17, still use 1.12.0 unless the brief is updated. Record the jar size and run `:desktopApp:packageDmg` size check before and after (about 10 MB of compression libraries ride along). Approved by the product owner as part of D5 (b).
Out: any UI, view model, refresh policy (D3, M2-04), test publishing, topology writes, browsing (M3).

## Acceptance
- Unit tests in `core` and `data-rabbit` (no Docker) parse every fixture in `m2-02/` to the data classes and map each error body to the typed error: `plugin-missing-404.json` to `PluginMissing`, `non-admin-no-tag-401.json` to `NotAuthorised`, `queues-page-size-501-error.json` to `BadPaging`.
- Request building tests (no Docker): URL, query string (`page`, `page_size`, `name`, `use_regex`, `columns`), TLS-only (an `http://` base is refused), no password in any log or error string.
- `@broker` tests (extend the spike's container setup, not its assertions): list with 13 mixed queues and streams returns only streams and pagination numbers; publishers and consumers rows for a stream with a client attached; plugin-missing container returns `PluginMissing`; `monitoring` user returns FULL, `management` user OWN_CONNECTIONS_ONLY, no tag NONE. Poll on values with the spike's bounded rule (stats lag up to 5 s).
- BDD: new scenarios in `13_connection_lifecycle.feature` are made to pass here at the core level (`@fake-broker`, with `FakeConnectionGateway`); the UI parts of them (sidebar picker) are in M2-05. Scenarios in `03`, `04`, `11` stay in M2-04, M2-06 and M2-05. Review them for any scenario step that the interface cannot serve and report.

## Design notes
- Interfaces in `core`, implementation in `data-rabbit`, fake in test fixtures; UI never sees Ktor or JSON. Sealed result types, no exceptions across the boundary, no `!!` on parsed data.
- Do not compute "last offset" or the count from committed chunk id. If the view needs a last offset later: first offset + `messages` - 1 (spec, "Management API facts").
- `messages` is nullable (absent for about 5 s after creation); the view model shows "..." or "n/a", not 0.
- `members`/`online` and `leader` may be missing for non-stream queues; the real client only maps streams.

## UX
None here. Data feeds `docs/ux/Streams.dc.html` (columns decided by N1 and D5).

## Open points
- D5 (b), N1 (c), S3a and S3b are decided (2026-11-04). `StreamSummary` has no size field (the column is blank until M3). `Capabilities.visibility` drives the screens: NONE gives the message and no lists, OWN_CONNECTIONS_ONLY gives the fixed notice.
- First step: M2-03a (`M2-03a-stream-stats-permissions.md`); the answer decides whether non-admin users see first offsets or "unavailable". The code path for "unavailable" is needed either way.
- D3 (refresh) is not decided here (needed by M2-04). D1 (b), D6 (a) and D7 (a) are decided and described above; D2 is proposed and awaits confirmation.
- Whether the stream-client lets us skip host name verification (D7 (a)): M2-03a checks it; if it cannot, report to the architect before building the TLS part.
- Unnamed-producer rows, cluster behaviour, case-sensitivity of `name` are unverified (spike "Not answered"); do not rely on them.

## Definition of done
Tests written first and failing for the right reason, then passing; `./gradlew test` and `task test:broker` green; no unrelated changes; spec "Management API facts" updated if a fixture disagrees; task index row updated.
