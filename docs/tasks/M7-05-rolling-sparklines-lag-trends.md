# M7-05: Rolling sparklines and consumer lag trends

Milestone: M7    Depends on: M2-04, M2-06 (stream list and clients refresh loops)    Size: M    Commit: c9b51de (number assigned in commit order by the product owner)

## Goal
The stream list, the publishers table and the consumers table show a tiny line and a badge for the change between the last two refresh ticks: message throughput ("+45/s") for streams and publishers, backlog direction ("Draining" or "Growing") for consumers.

## Why
Spec "Monitoring views (M7)", bullet "Sparklines and lag trends". Costs no extra broker call.

## Scope (what shipped)
In:
- `core/streams/MetricSparkline.kt`: `RollingMetricHistory(maxSamples = 16)`, a per-key ring buffer of (value, timestamp). Null values are not recorded. `snapshot` returns a sparkline only for keys with at least 2 samples; points are normalised to 0..1 (0.5 when all values are equal); direction from the last delta; rate per second from the last two timestamps (null if equal timestamps).
- Labels: Velocity "+N/s" (or "+N" when no rate), negative delta as plain number, steady "0/s"; ConsumerLag "↘ Draining (-N)", "↗ Growing (+N)", "→ Steady".
- `StreamListViewModel`: `messageHistory` per stream name, `messageSparklines` in state for visible rows; history cleared on reset and pruned to current names.
- `StreamClientsViewModel`: `publisherHistory` (published counter) and `consumerLagHistory` (offset lag); settling rows are not recorded; `publisherSparklines` and `consumerLagSparklines` in state.
- `ui/components/MetricSparklineView.kt` (canvas 40 x 14 dp) used in `StreamsTable` and `StreamClientsTables`.
Out: persistence, longer history, alerts.

## Acceptance
- `core/.../streams/MetricSparklineTest`: ring buffer bound and 2-sample minimum; growing, draining and steady lag labels; "StreamListViewModel populates messageSparklines after second refresh tick with zero extra broker calls"; "StreamClientsViewModel populates publisher velocity and consumer lag trend sparklines across ticks".
- `ui/.../streams/StreamScreenUiTest`: "publisher and consumer rows render rolling velocity and lag trend sparklines when present in state".
- `ui/.../streams/StreamsScreenUiTest`: "stream row renders rolling velocity sparkline badge when messageSparklines contains stream entry".
Not covered by a test: pruning of vanished keys, history reset on reconnect, the 16-sample bound inside the view models.

## Design notes
Pure `core` logic fed by the existing ticks (D3 interval, default 5 s), so the rate is per tick interval. A manual refresh also adds a sample, so the per-second figure uses real timestamps, not the interval.

## UX
No file in `docs/ux/`; badge colour by semantic (growing lag is the warning case).

## MCP
None (UI-only): a client-side trend over the app's own refresh history; the MCP server is stateless per call.

## Non-goals
Retention of history across sessions.

## Open points
Message counts are approximate (spec), so the throughput badge is approximate too; the UI does not say so (to decide).

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
