# Publishers and consumers tab (inputs from the M2-02 spike)

Milestone: M2    Depends on: M2-03, M2-04, decision S3    Size: M

Status: input note only. The full brief is written when M2-03 is accepted. It records what the spike decided so it is not lost.

## Inputs
- Data: `/api/stream/publishers/{vhost}/{stream}` and `/api/stream/consumers/{vhost}` (filter by `queue.name`); fields in `docs/spec.md` "Management API facts". Scenario `04` "errors" is the field `errored`; "connection" is `connection_details.name` plus `user_provided_name` from `/api/stream/connections` if wanted.
- Plugin missing: `/api/stream/publishers` returns 404 with `{"error":"not_found","reason":"Not Found"}`; show "enable rabbitmq_stream_management" and keep the rest of the screen working (`04` `@negative`).
- Settled values only: the consumer row right after a subscribe can be off by one (`offset` = next offset, lag 0) for up to about 5 s; M2-06 tests wait for the settled row (M2-02c) and the screen does not treat the first row as final.
- Caught up (architect, after the M2-02c review): lag is 0 for a consumer that never stored an offset and 1 for one that did, per tracking chunk written after its last read. Another application's consumer storing offsets on the same stream also adds chunks after this consumer's read position, so a constant threshold of 1 is not exact. Use option (a) with a small tolerance and label it approximate ("Caught up" for lag 0 to 1, "Behind" above); the `04` outline row `billing` (lag 0) holds only for non-storing consumers, so add a row with a storing consumer (lag 1, Caught up). Decide the exact tolerance with a real-broker check in the M2-06 brief.
- Lag (history): `offset_lag` was 1 for a caught-up named consumer, because its offset tracking entry occupies the next offset. The `04` outline expects "Caught up" at 0 behind. Decide in this brief, with a real-broker check, between: (a) show `offset_lag` as the broker reports and call 0 or 1 "Caught up" when the stream has tracking entries, (b) subtract the stream's tracking entry count if it can be known. Also re-check with a consumer that stores offsets itself and one that does not; the spike's test consumer showed `tracking.offsets` = 0 without the test storing an offset, and the mechanism is now explained in the M2-02c correction in `M2-02-management-api-spike.md` (client safety net; unnamed or no-tracking consumers write nothing and read lag 0).
- Jump to a consumer's offset: `offset` is the last delivered offset; the browser starts at offset + 1 or at the offset (the `04` scenario says "starting at offset 47950"); decide with M3.
- Not tested by the spike: unnamed producers (`reference`), super stream producers.

## S3 handling (decided by the product owner, 2026-11-04: S3a (a) and S3b (a))
- S3b: users with the `management` tag see only their own connections' publishers and consumers: show the lists with a fixed notice "Showing only your own connections; the monitoring tag shows all", never an empty state that looks like "nobody is connected".
- S3a: users with no management tag get 401 on every management call: the Streams area shows one message ("This user cannot read stream statistics; ask for the monitoring tag") and no lists. A stream-protocol-only mode for these users needs the open spike question (can such a user call `queryStreamStats`?) and has no stream list (the stream protocol cannot list streams).

Scenarios added in `04` for these states: "A user with only the management tag sees their own connections", "A user without the management tag", "A consumer that stored its offset is caught up at one message behind". The stream-protocol-only mode for no-tag users is not built (decided).

## Inputs from the M2-03 part 2 build (2026-11-04)
- Unknown connection name: right after a client attaches, publisher and consumer rows carry `"connection_details":{}` and zero counters for some seconds. The catalog returns the row with an empty connection name (not `Unexpected`); the tab shows "unknown" for it (and treats the counters as not settled, see "Settled values only") and the window after attach is covered by a test. Add a `04` scenario for it if none fits.
- Plugin missing, 400 versus 404: per-stream paths (`/api/stream/publishers/{vhost}/{stream}`) answer 400 with an empty body and the top-level path answers 404 when the plugin is absent. The real catalog therefore asks the top-level probe after a 400 or 404 and maps to `PluginMissing`; the tab must not read the 400 as a bad request. Already implemented in `data-rabbit` and covered by `RealBrokerCatalogTest`; spec "Management API facts" row "Plugin missing" is the source.
- `NotFound` on `publishers` or `consumers` means the stream is gone: show "stream no longer exists" and go back to the list.
