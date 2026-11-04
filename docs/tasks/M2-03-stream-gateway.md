# Stream catalog: core interface, fake and real management client

Milestone: M2    Depends on: M2-01b (merged), M2-02 (accepted), decisions D3, D5, N1 below (see "Open points")    Size: L (split in two if it grows: 03a list and detail, 03b publishers and consumers)

Branch: `m2-03-stream-gateway` from `main` after M2-01b and M2-02 are merged.

## Goal
`core` has a `StreamCatalog` interface and a `FakeStreamCatalog`; `data-rabbit` has a real implementation over the management API (Ktor client, HTTPS 15671) that lists streams with their statistics, reads publishers and consumers, detects the missing plugin and the caller's visibility level, each failure a typed result. No UI.

## Why
`docs/spec.md` "Management API facts (M2-02 spike)" and Goals (stream list, publishers and consumers); `03_stream_list.feature`, `04_publishers_consumers.feature`, `11_global_search.feature`. M2-04 (view model), M2-06 (tab) and M2-09 (gate) need it.

## Scope
In (all derived from the spike; field names and types are in the fixtures, do not guess):
- `core` (no RabbitMQ imports, no JVM-only types): `StreamCatalog` with, in prose, `listStreams(request)` (page, page size up to 500, optional name filter with optional regex; returns rows plus the pagination numbers; the client filters `type == "stream"`), `streamDetail(vhost, name)`, `publishers(vhost, stream)`, `consumers(vhost, stream)` (filtered client side on `queue.name` from `/api/stream/consumers/{vhost}`), `capabilities()`. Immutable data classes `StreamSummary` (name, vhost, messages nullable, consumers, segments, firstTimestamp, leader, node, members, online, retention as parsed arguments), `PublisherRow`, `ConsumerRow`, `Capabilities` (plugin present yes/no/unknown; visibility FULL, OWN_CONNECTIONS_ONLY, NONE). Typed errors as a sealed result: `NotAuthorised` (401), `PluginMissing` (404 with the not_found body from `/api/stream/publishers`), `BadPaging` (400), `Unreachable`, `Unexpected`.
- `FakeStreamCatalog` in core test fixtures, built from the recorded JSON bodies (`data-rabbit/src/test/resources/fixtures/m2-02/`; copy or share through a test-fixtures source set, say which).
- Real implementation in `data-rabbit`: Ktor Java engine, TLS only with the profile's CA handling already used by the permission check, basic auth from the session's credentials (see D6: the session does not hold the password today; use what M2-01 gives, do not add secret storage here). JSON with kotlinx-serialization, `ignoreUnknownKeys`; `messages` optional.
- Capability detection: plugin via `GET /api/stream/publishers` (404 with the JSON body means missing; per-stream paths are not usable for detection: 400 empty body); visibility via `GET /api/whoami` tags (`monitoring` or `administrator` = FULL, `management` = OWN_CONNECTIONS_ONLY, none = 401 = NONE). Confirm in the test that whoami returns tags for `management` and `monitoring` (M1-03 recorded it; the spike did not repeat it).
- Super stream detection is NOT here (M2-07); leave the gateway method out.
Out: any UI, view model, refresh policy (D3, M2-04), first offset and the stream client (see Open points: only if decision D5 is "yes, in M2"), test publishing, topology writes.

## Acceptance
- Unit tests in `core` and `data-rabbit` (no Docker) parse every fixture in `m2-02/` to the data classes and map each error body to the typed error: `plugin-missing-404.json` to `PluginMissing`, `non-admin-no-tag-401.json` to `NotAuthorised`, `queues-page-size-501-error.json` to `BadPaging`.
- Request building tests (no Docker): URL, query string (`page`, `page_size`, `name`, `use_regex`, `columns`), TLS-only (an `http://` base is refused), no password in any log or error string.
- `@broker` tests (extend the spike's container setup, not its assertions): list with 13 mixed queues and streams returns only streams and pagination numbers; publishers and consumers rows for a stream with a client attached; plugin-missing container returns `PluginMissing`; `monitoring` user returns FULL, `management` user OWN_CONNECTIONS_ONLY, no tag NONE. Poll on values with the spike's bounded rule (stats lag up to 5 s).
- BDD: this task un-skips nothing; scenarios in `03`, `04`, `11` stay in M2-04, M2-06 and M2-05. Review them for any scenario step that the interface cannot serve and report.

## Design notes
- Interfaces in `core`, implementation in `data-rabbit`, fake in test fixtures; UI never sees Ktor or JSON. Sealed result types, no exceptions across the boundary, no `!!` on parsed data.
- Do not compute "last offset" or the count from committed chunk id. If the view needs a last offset later: first offset + `messages` - 1 (spec, "Management API facts").
- `messages` is nullable (absent for about 5 s after creation); the view model shows "..." or "n/a", not 0.
- `members`/`online` and `leader` may be missing for non-stream queues; the real client only maps streams.

## UX
None here. Data feeds `docs/ux/Streams.dc.html` (columns decided by N1 and D5).

## Open points
- D5 (first offset in the M2 list). Recommended default: no. Then no stream client in production in M2 and the offset columns wait for M3. If the product owner says yes, add a separate brief M2-03b: `StreamOffsets` interface with `firstOffset(vhost, stream)`, the stream client as `implementation`, one `queryStreamStats` per visible row (not per stream on the broker), and the untested question of a user without the management tag.
- N1 (size column): until decided, `StreamSummary` has no size field.
- S3: the interface already carries `Capabilities.visibility`, so the code does not wait for the decision; only what the screens do with it does.
- D3 (refresh) and D6 (password during a session) are not decided here.
- Unnamed-producer rows, cluster behaviour, case-sensitivity of `name` are unverified (spike "Not answered"); do not rely on them.

## Definition of done
Tests written first and failing for the right reason, then passing; `./gradlew test` and `task test:broker` green; no unrelated changes; spec "Management API facts" updated if a fixture disagrees; task index row updated.
