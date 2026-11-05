# F-C2: virtualised rows for the stream list (LazyColumn in StreamsTable)

Milestone: M2/M3 (follow-up of F-C; do before the M3 row selection)    Depends on: F-C merged    Size: M

## Goal
The stream list composes only the rows near the viewport however many streams a vhost has; sorting, search, expand/collapse of super-stream partitions, copy, open link and screenshots behave as today.

## Why
F-C review (this follow-up was named in the F-C brief). `StreamsTable.kt` (389 lines) renders `Column(Modifier.weight(1f).verticalScroll(...))` with `rows.forEach { DataRow }` inside one `SelectionContainer` (line 145 to 151). The M2-04 paging reads all queues of a vhost, so 1000 or more streams are possible, and F-C measured the same shape: about 4.4 s first paint and 33,000 semantics nodes for 5000 rows (indicative). The trigger for the stream list is unmeasured, so a spike comes first.

## Scope
In:
- Spike S-1 (first, throwaway): time first composition and one refresh with 200, 1000 and 5000 stream rows in the current Column code, as F-C did (same method, same machine, numbers into this brief). Go if 1000 rows take longer than about 1 s; on No-go the owner decides (recommendation: still build, the pattern is proven by F-C).
- Replace the `Column` + `forEach` by a `LazyColumn` with a hoisted `LazyListState`, item key = the stream name (partitions of an expanded super stream: `partition:<super>:<name>` or the existing identity, the developer checks that names are unique within the list and prefixes by kind like F-C R-FC1), `SelectionContainer` around the list, `DisableSelection` on non-row items, no `clipToBounds` on a row or cell.
- Scroll position survives a refresh tick and a sort or search change that keeps the row (same key); an expand toggle keeps the position of the toggled row.
- Expanded partition notes (`partitionNote:*`) and the expand toggle focus keep working in lazy rows (state in `remember` is lost when a row leaves; the expanded set must stay in the view model or a hoisted state, not in the row).
Out: sticky header (own UX decision), paging or a "show more" button, the M3 row selection and multi-select (design it on top of this list), data layer changes.

## Acceptance
- BDD: no `.feature` change; `02_stream_list.feature` (or the file that holds the list scenarios) stays green unedited. The developer lists the scenario names in the brief.
- UI (new `StreamsLazyUiTest`, fake state): 5000 rows compose at most a named constant of `streamRow:*` nodes; scroll to the last row and back; position kept on refresh and on a sort that keeps the row; expand toggle on a row scrolled in; keyboard Tab reaches rows below the fold; copy of a cell in a scrolled-in row; duplicate names or partitions do not crash (`Key ... was already used`).
- Gate, no edit: `StreamsScreenUiTest`, `StreamsCopyUiTest`, `StreamsOpenLinkUiTest`, `StreamsSuperStreamUiTest`, `StreamsScreenshotTest` baselines byte-identical (if a PNG differs, the tester views it and the architect rules, as for 02c in F-C).
- Stale dimming: test for the popup or any overflow drawn from a stale row if the list has one; dim per row only when stale (F-C finding, see F-C-03 test B).

## Design notes
Module `ui` only. Reuse the F-C lessons: per-row layers and clips cut overflowing content; hoist list and scroll states; one numbering function for keys if keys can repeat; tags unchanged; `PluginMissing`-style non-list states keep the plain scroll.

## UX
`docs/ux/Streams.dc.html` (or the stream list mockup in `docs/ux/`); no visual change.

## Open points
S-1 numbers; whether the horizontal scroll of the table needs the F-C `rowScroll` approach (only if rows get their own scroll nodes and carry overflowing popups; the stream list has no hint popup today).

## Definition of done
Tests written first and passing, spike numbers recorded, gate tests and screenshots unedited, no unrelated changes, full `task test` once at the end.
