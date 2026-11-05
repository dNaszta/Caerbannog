# F-C: virtualised rows on the Publishers and consumers tab (LazyColumn)

Milestone: M2 (follow-up of M2-06; may slip to the start of M3 with F-H)    Depends on: M2-06 (merged after the hand trial A1)    Size: M

Status: briefed (architect, 2026-11-05, branch `f-c-lazy-clients-list`, docs only). The owner asked for it on 2026-11-05 ("F-C: a LazyColumn"); the M2-06 review had it as conditional. NEXT: step 2 (test developer): spike S-1 first, then the tests below (red), then step 3 build, step 4 verify, step 5 review. Not started in code. Decisions for the owner: D-FC1 and D-FC2 below.

## Goal
The Publishers and consumers tab composes only the rows that are on screen (plus a small buffer) however many clients a stream has; header, status hint, keyboard hint popup, copy, scroll position and screenshots behave exactly as today.

## Why
M2-06 follow-up F-C (`docs/tasks/M2-06-publishers-consumers.md`, follow-up table, and deviation 4 of the 06b review: "plain `Column` rows, no `LazyColumn`, a stream has tens of clients, not thousands"). The spec has no limit for this tab (`docs/spec.md`, "Publishers and consumers tab"); the product need is "who is connected to this stream", which for a busy shared stream can be hundreds of consumers.

What actually grows (facts from the code and spec, no measurement yet):
- The per-stream consumers endpoint does not exist (400, spec "Management API facts"), so the data layer reads the consumers of the whole vhost and filters by stream before the UI gets rows. That cost is in `core` and the network, it is NOT reduced by a LazyColumn. The UI only ever gets the rows of one stream.
- In the UI every row is composed eagerly: about 6 `CopyableText` cells (each with semantics, a focus target and a copy handler), one `TooltipArea` plus one focusable "i" per Behind consumer, inside one `SelectionContainer` per table. Cost is linear in rows on first composition, on every refresh that changes a row, and in the semantics tree that screen readers and tests walk. 5000 rows are about 30,000 cells.
- `StreamsTable.kt` (the stream list) is not a LazyColumn either: `Column(Modifier.weight(1f).verticalScroll(...))` with `rows.forEach`. A list of 1000 streams has the same issue. That is a separate follow-up (F-C2, below), not part of this task.

Honest size of the problem: no real stream has shown more than a few rows yet, so the trigger is unproven. Spike S-1 measures it and sets the go or no-go line, so the owner's request is built on a number and not on a guess.

## Scope
In:
- Spike S-1 (first, throwaway, in the test source set): time the first composition and one refresh with 200, 1000 and 5000 consumer rows in the current `Column` code, `runDesktopComposeUiTest`. Record numbers in this brief. Go if 1000 rows take longer than about 1 s to compose or a refresh drops frames visibly; the owner asked for it, so on a No-go we still build it (decision D-FC1) but the spike tells us whether it is a fix or a safeguard.
- Spike S-2: does `SelectionContainer` around a `LazyColumn` still give mouse selection and the keyboard copy of a cell (UI1, `StreamClientsCopyUiTest`)? Does selecting across a row that scrolls out of the viewport lose or crash the selection? Record the answer.
- The tab body becomes one `LazyColumn` (hoisted `LazyListState`) whose items are: the banners and notice, then per table: caption, header row, one item per client row (key = stable row key), empty/loading text; then the footnote. Both tables live in the one list, because a LazyColumn cannot sit inside the current `verticalScroll` page (unbounded height crashes) and two LazyColumns would need a fixed height each.
- Horizontal scroll: one shared `ScrollState` per table, applied to the header item and every row item of that table, so the columns scroll together as now.
- Stable row keys. `ConsumerRowState.key` exists. `PublisherRowState` has no key (the view uses the list index): add one in `core` (for example connection name + reference + a counter for duplicates, decided by the developer, test first in `StreamClientsViewModelRowsTest` style) so a refresh that reorders or inserts rows keeps state and scroll with the row.
- Scroll position survives a refresh tick (same keys, same `LazyListState`), a Loading to Content change keeps the top, and leaving the tab and coming back to it starts at the top (as today; the `ClientsTab` effect is keyed by stream).
- The "i" control, its popup and the `TooltipArea` keep working for rows that scroll in and out (see Design notes).

Out:
- The `StreamScreen.kt` file split (F-H); see recommendation D-FC2.
- A LazyColumn for the stream list (`StreamsTable.kt`): follow-up F-C2, only with its own measurement.
- Sticky header (see UX: today the header scrolls away with the page; no change).
- Any change to texts, columns, widths, refresh behaviour, data layer or the vhost-wide fetch (a per-stream read is not possible, spec).
- Paging or a "show more" button: not needed once rows are virtualised; the owner has not asked for a cap.

## Acceptance
No `.feature` scenario changes: this is a rendering change with no new user-visible behaviour, and `04_publishers_consumers.feature` already describes the behaviour that must stay. If the developer finds a user-visible difference, stop and raise it as a spec change.

UI tests (new, `ui/src/jvmTest/.../streams/StreamClientsLazyUiTest.kt`, tags none, fake state only; written first, red for the right reason because every row is composed today):
1. With 5000 consumer rows the number of composed row nodes (count of `consumerRow:*` nodes in the unmerged tree) is far below 5000 (assert <= a named constant, for example 100 at 1280x800) and the caption still says 5000.
2. Scrolling to the last row (`performScrollToIndex` or a scroll to key) makes its cells exist and the first row's cells disappear; the table header and caption are still there.
3. Status hint after scrolling: scroll to a Behind row far down, request focus on `statusHint:<key>`, the `statusHintPopup:<key>` node exists with the hint text, Escape closes it (the contract of `StreamClientsStatusUiTest`, on a row that was not composed at first).
4. Popup not clipped for a row near the bottom of the viewport: same bounds rule as `StreamClientsHintPopupUiTest` (inside the window, inside the table, off the header, pixels really drawn). The popup must be visible for the first and last fully visible Behind row.
5. Refresh keeps position: scroll to row 3000, push a new state with the same keys and changed numbers, the same row is still the first visible one and its text shows the new number; a row inserted above does not move the viewport away from the row the user looks at.
6. Refresh keeps focus and open popup: focus "i" on a Behind row, push a refreshed state with the same keys, the popup is still open and the focus is still on that "i". (If the row scrolled out and comes back, the popup is closed and the focus is gone: assert that too, it is the accepted behaviour.)
7. Loading placeholder rows and the empty text still render (the existing tests cover it).
8. Copy: the cells of a visible row still copy as in `StreamClientsCopyUiTest` (existing tests, unedited).

Existing tests and screenshots (gate, no edit allowed): `StreamClientsStatusUiTest`, `StreamClientsHintPopupUiTest`, `StreamClientsCopyUiTest`, `StreamClientsScreenshotTest` (`clients-01`, `clients-02a/b/c` and the others) and `StreamClientsEventsAdapterTest` stay green with the baseline PNGs unchanged (compare with the usual `screenshotTest` run; no re-record). If a test must change, that is a finding to raise, not an edit to make.

Extra checks: `task test` once at the end of the task (tiered rule: affected tests per step, full run once). Manual check by the developer with the dev seed: `task dev:seed -- --hold` plus a throwaway script holding 1000 consumers on one stream is optional; the UI test with 5000 fake rows is the proof.

## Design notes
- Module: `ui` only (`StreamScreen.kt`), plus the publisher row key in `core` (`StreamClientsState.kt`, the view model that builds rows). No broker code in the UI, no new interface, nothing to fake beyond the existing state builders.
- Structure: `ClientsSections` stops using `ClientsScroll` (`verticalScroll` Column) and uses `LazyColumn(state = listState, contentPadding = ..., verticalArrangement = spacedBy(...))`. `ClientsSection` splits into item emitters (`LazyListScope.clientsSection(...)`) instead of one Column composable. The `Modifier.testTag("${prefix}Section")`, `${prefix}Table` semantics (content description, Loading and Stale state description) and `${prefix}Caption` must keep existing for the current tests: carry the table semantics on the header item and the rows via a shared modifier, and check the tests that look up `publishersTable` and `consumersTable` first (read them before the build; if a test needs the table node to contain the rows, say so in the spike note and ask the architect, do not edit the test).
- `PluginMissing` keeps using the plain scroll Column (no rows).
- Stale dimming: `alpha(0.6f)` moves to each row item (or a `graphicsLayer` on the list) with the same look.
- Selection (S-2): put the `SelectionContainer` around the `LazyColumn`. If S-2 shows that mouse selection across rows breaks or crashes, fall back: one `SelectionContainer` per row item (selection then works inside a row only; keyboard copy per cell is unchanged and is the tested contract UI1) and note it in the spec paragraph. Do not ship a crash.
- Hint popup: it is an inline composable drawn with zero layout size left of the "i" (`floatingBesideLeft`), not a `Popup` window (a second root would break `onRoot()`; M2-06 round 2 ruling). A `LazyColumn` clips its content to its viewport, exactly as the `verticalScroll` Column does today, so the clipping rule is the same: the popup is clipped only where the viewport ends. Test 4 pins it for the first and last visible rows. If the popup for the last visible row is cut by the viewport bottom, when the "i" gains focus call `bringIntoViewRequester` on the row (or the "i") so the row, and its popup height, is scrolled in; do not move to a `Popup` window. Do not put `clipToBounds` or `graphicsLayer(clip = true)` on a row or cell (the "no clip on the control" comment in `StatusHintControl` applies to every ancestor).
- State inside a row (`focused`, `dismissed` in `StatusHintControl`) lives in `remember`; a row that scrolls out leaves the composition and loses it. That is acceptable: focus cannot stay on a node that is not there, and a popup that comes back closed is the safe default. Key the items by row key so state follows the row on a reorder, never by index.
- Keyboard: tab order inside a LazyColumn follows composed items; tabbing past the last composed row asks the list to scroll the next row in (Compose focus search does this for lazy lists). Test 3 covers focus on a row that was not composed at first; the developer also tries Tab from the last visible "i" in the spike and records whether the next row's "i" gets focus. If it does not, that is a finding (a keyboard user could not reach rows below the fold) and blocks.
- `TooltipArea` works inside lazy items (it only needs a pointer); nothing to change.
- Row height: rows are two-line in the Connection cell, so heights differ a little; do not set a fixed height (screenshots must not move). The list measures only composed rows, scrollbar thumb size will be an estimate: add a `VerticalScrollbar` only if the page has one today (it has not; keep as is).
- Names, not numbers: the buffer and the test's composed-row bound are named constants.

## UX
`docs/ux/Clients.dc.html`. No visual change: the same states (loading, content, stale, empty, plugin missing, lost connection, stream gone). Behaviour that must not change: header columns scroll sideways together with the rows; the header scrolls up with the page and is not sticky (the mockup shows no sticky header; a sticky header is a separate UX decision, not part of this task). Footnote under the tables stays at the end of the list.

## Open points
- Spike S-1 numbers (is the Column a real problem at 1000 rows?): unverified, first job of step 2.
- Spike S-2 (selection around a LazyColumn, and Tab to rows below the fold): unverified.
- Whether any existing test needs `publishersTable`/`consumersTable` to contain the rows as children: check in step 2; if yes the table semantics move onto a per-row modifier.
- Publisher row key: not decided; developer proposes, architect approves in the red-test round (publisher identity from the API is `publisher_id` per connection plus `reference`, `connection_details.name`; the fields are in the spec "Management API facts").
- Large lists and the vhost-wide fetch: not solved here; if a vhost with thousands of consumers makes the call itself slow, that is a data-layer follow-up (a bounded refresh or a note), not this task.

## Decisions for the owner
- D-FC1 (do it now?). Options: (a) build F-C now, after S-1 whatever the numbers say, as asked; (b) build it only if S-1 shows 1000 rows are slow, else park F-C. Recommendation: (a) with S-1 recorded first: the cost is M, the risk is contained by the unedited tests and screenshots, and a safeguard against a vhost with many consumers is cheap now and expensive later once M3 adds more to this screen. Blocks: nothing; step 2 can start with S-1.
- D-FC2 (F-H together?). F-H splits `StreamScreen.kt` (557 lines) into the shell and `StreamClientsTables.kt`; it was planned for the start of M3 with its own commit and a no-test-edit gate. F-C rewrites exactly the code F-H moves (`ClientsSection`, rows, cells), so doing both in one change makes a large diff that is hard to review. Options: (a) F-H first as its own commit on the same branch (pure move, gate: no test change, S), then F-C on the new file; (b) F-C in place now, F-H later (the file is moved after a rewrite, same cost, no conflict risk but the diff of F-C is read inside a 557-line file); (c) one combined commit (not recommended). Recommendation: (a): the move is cheap, mechanical and verified by the same green tests, and it makes the F-C diff small and reviewable. If the owner wants the least change now, (b) is acceptable.
- F-C2 (not decided, only noted): the stream list (`StreamsTable.kt`) renders all rows in a `Column`; with the M2-04 paging over all queues a vhost of 1000 or more streams has the same cost. Recommend a separate brief after S-1 shows what the Column costs; it has its own tests (`StreamsScreenUiTest` family) and the row selection of M3.

## Definition of done
Tests written first and red for the right reason, then green; S-1 and S-2 results recorded here; existing client-tab tests and screenshots unedited and green; no `.feature` change (or a raised spec change); full `task test` once at the end; no unrelated changes; the spec paragraph "Publishers and consumers tab" gets one sentence if the selection fallback of S-2 is used.
