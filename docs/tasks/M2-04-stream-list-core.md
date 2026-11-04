# Stream list view model (input note)

Milestone: M2    Depends on: M2-01b, M2-03    Size: M

Status: input note; the full brief is written when M2-03 is accepted.

## Inputs from the decisions of 2026-11-04
- Rows come from `StreamCatalog`; first offset comes lazily from `StreamOffsets` per visible row (D5 b). Row state for the offset: loading, value, `unavailable` (failed or denied call). Last offset = first offset + `messages` - 1, computed only when both are present; otherwise unavailable. The count is management `messages` and is always shown when the broker returned it (`messages` may be missing for about 5 s after creation: show n/a, not 0).
- The Size cell is always blank (N1 c); the view model carries no size.
- Capabilities (S3a, S3b): visibility NONE gives the state "cannot read stream statistics, ask for the monitoring tag" and no rows; the other levels list rows.
- Scenarios to make pass (all `@fake-broker`): in `03_stream_list.feature` the statistics scenario, "The message count and the offsets agree", "The first offset cannot be read for one stream", "A user without the management tag", filter, sort, refresh, empty, paging. The refresh policy is decision D3.
- Server-side paging with name filter; the app filters `type == "stream"` client side, so a page can hold fewer streams than `page_size`.
