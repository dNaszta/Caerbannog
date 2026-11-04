# Publishers and consumers tab (inputs from the M2-02 spike)

Milestone: M2    Depends on: M2-03, M2-04, decision S3    Size: M

Status: input note only. The full brief is written when M2-03 is accepted. It records what the spike decided so it is not lost.

## Inputs
- Data: `/api/stream/publishers/{vhost}/{stream}` and `/api/stream/consumers/{vhost}` (filter by `queue.name`); fields in `docs/spec.md` "Management API facts". Scenario `04` "errors" is the field `errored`; "connection" is `connection_details.name` plus `user_provided_name` from `/api/stream/connections` if wanted.
- Plugin missing: `/api/stream/publishers` returns 404 with `{"error":"not_found","reason":"Not Found"}`; show "enable rabbitmq_stream_management" and keep the rest of the screen working (`04` `@negative`).
- Lag: `offset_lag` was 1 for a caught-up named consumer, because its offset tracking entry occupies the next offset. The `04` outline expects "Caught up" at 0 behind. Decide in this brief, with a real-broker check, between: (a) show `offset_lag` as the broker reports and call 0 or 1 "Caught up" when the stream has tracking entries, (b) subtract the stream's tracking entry count if it can be known. Also re-check with a consumer that stores offsets itself and one that does not; the spike's test consumer showed `tracking.offsets` = 0 without the test storing an offset, and the mechanism is now explained in the M2-02c correction in `M2-02-management-api-spike.md` (client safety net; unnamed or no-tracking consumers write nothing and read lag 0).
- Jump to a consumer's offset: `offset` is the last delivered offset; the browser starts at offset + 1 or at the offset (the `04` scenario says "starting at offset 47950"); decide with M3.
- Not tested by the spike: unnamed producers (`reference`), super stream producers.

## S3 handling options (decision pending, see the M2-02 brief, "Product decisions needed")
- Users with the `management` tag see only their own connections' publishers and consumers: show the lists with a fixed notice "Showing only your own connections; the monitoring tag shows all", never an empty state that looks like "nobody is connected".
- Users with no management tag get 401 on every management call: the Streams area shows one message ("This user cannot read stream statistics; ask for the monitoring tag") and no lists. A stream-protocol-only mode for these users needs the open spike question (can such a user call `queryStreamStats`?) and has no stream list (the stream protocol cannot list streams).
