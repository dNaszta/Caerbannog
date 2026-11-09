# M7-01: Stream log tape in the message browser

Milestone: M7    Depends on: M3 message browser (merged)    Size: M    Commit: 923cd3d (no task branch)

## Goal
The message browser shows a diagram ("log tape") of the stream's offsets: truncated history before the first offset, the active window, the retention danger zone, the page on screen, the selected message and one pin per consumer. A click on the tape seeks the browser to that offset.

## Why
Operators want to see at a glance where the browsed page sits in the stream, how close consumers are to the retention cliff, and jump around without typing offsets. Spec: "Monitoring views (M7)", bullet "Stream log tape".

## Scope (what shipped)
In:
- `core/browser/StreamLogTape.kt`: pure model `StreamLogTape.from(firstOffset, lastOffset, pageFirstOffset, pageLastOffset, selectedOffset, consumers)`; returns null when first or last offset is missing or `last < first`.
- Constants: truncated prefix drawn as 0.14 of the bar width when `firstOffset > 0`; danger zone = first 10 percent of the active span, only when `firstOffset > 0` and the span is at least 10 offsets.
- `ConsumerPinHealth`: Inactive (single-active-consumer standby), CaughtUp (status CaughtUp or offset at the head), AtRisk (offset inside the danger zone), Lagging (otherwise). Consumers without a browsable offset get no pin.
- `offsetAtTotalFraction` and `offsetAtActiveFraction` turn a click position into an offset clamped to `firstOffset..lastOffset`; a click in the truncated part gives `firstOffset`.
- Text helpers: range summary, truncated badge, "Retention cliff <= N" badge, axis labels, "Viewing a..b (click tape to jump)".
- `ui/streams/StreamLogTapeView.kt` (`StreamLogTapeCard`), placed above the table in `MessageBrowserView`; `StreamScreen` passes `state.consumers`; click calls `events::browseOffset`.
Out: no new broker call (offsets and consumers come from the browser state and the existing refresh loop); no MCP tool.

## Acceptance
Tests that exist (no feature file; no BDD scenario was written):
- `core/.../browser/StreamLogTapeTest`: null on missing or invalid offsets; window without truncation; truncated zone, danger cliff (2000..10000 gives 2800) and the four pin healths; click mapping across truncated and active regions.
- `ui/.../streams/MessageBrowserUiTest`: "stream log tape renders offset window, truncated cliff badges, and consumer pins that seek on click" and "clicking stream log tape canvas seeks to proportional stream offset".
Not covered by a test: the 0.14 visual fraction as a visual result, colours, tooltips.

## Design notes
Pure function in `core` (common code), drawing only in `ui`; the tape never reads the broker. Paging rules of the browser are unchanged: a seek is a normal `browseOffset`.

## UX
No file in `docs/ux/` describes it (built from the owner's idea). States: hidden when offsets are unknown.

## MCP
None (UI-only): a visual aid; the MCP browse tool already takes an offset.

## Non-goals
Editing or deleting offsets; showing consumers of other partitions.

## Open points
No BDD scenario exists; see the list in the architect's reply. Danger-zone 10 percent and the 10-offset minimum are product-neutral constants, not decided by the owner.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
