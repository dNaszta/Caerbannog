# M7-07: Super stream partition balance bar and orphaned consumer indicator

Milestone: M7    Depends on: M2-07 (super streams in the list), M7-05    Size: S    Commit: 9982df1 (the commit message names M7-07)

## Goal
Each partition of an expanded super stream shows its share of the super stream's messages as a bar, marks a hot partition, and warns when a partition has no consumer while a sibling has one.

## Why
Spec "Monitoring views (M7)", last bullet; super stream rules stay (one partition at a time, no merge: this adds no merged browsing).

## Scope (what shipped)
In:
- `core/streams/SuperStreamPartitionBalance.kt`: `computePartitionBalances(visibleEntries, expandedNames)` for expanded `StreamEntry.Group` only; share = messages / total; total is null (no share, "not available") when any partition count is unknown; total 0 gives an even share.
- Hot: at least 2 partitions, total > 0 and share > max(1.5 / count, 0.50). Orphaned: at least 2 partitions, 0 consumers and some sibling with consumers. Orphaned wins over hot. Labels "NN%" and "Hot · NN%".
- `StreamListState.partitionBalances`, filled in `StreamListViewModel`; `ui/streams/PartitionBalanceBarView.kt` used in `StreamsTable`.
Out: rebalancing, any broker call.

## Acceptance
- `core/.../streams/SuperStreamPartitionBalanceTest`: balanced shares; hot partition (85 percent); orphaned partition; none orphaned when nobody has consumers; collapsed groups give nothing; "StreamListViewModel populates partitionBalances when super stream is expanded".
- `ui/.../streams/StreamsSuperStreamUiTest`: "expanded super stream partitions render partition balance bars and orphaned consumer warnings without affecting cell copy".
Not covered by a test: the unknown-count case and the 2-partition threshold (75 percent) as tests; BDD feature `06_super_streams.feature` has no scenario for it.

## Design notes
Threshold: 1.5 times the even share with a 50 percent floor, so a 2-partition super stream needs more than 75 percent, 3 or more partitions more than 50 percent. The code comment saying "more than 37.5 percent in a 4-partition stream" is wrong: the floor makes it 50 percent. Counts are approximate (spec), so the label is too.

## UX
No file in `docs/ux/`.

## MCP
None (UI-only).

## Non-goals
Per-partition throughput, merging partitions.

## Open points
Thresholds are the developer's choice, not an owner decision.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
