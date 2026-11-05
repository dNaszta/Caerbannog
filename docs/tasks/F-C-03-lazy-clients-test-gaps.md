# F-C-03: three test gaps of the lazy clients list (stale popup, widened window, swipe on a row)

Milestone: M2    Depends on: F-C-lazy-clients-list (steps 3 and 4)    Size: S

## Goal
Three behaviours that the F-C build changed and no test pins are covered by tests in `StreamClientsLazyUiTest`, so the branch can be merged with the rules of the brief proven, not assumed. Test code only, unless a test goes red; then the senior developer fixes the row code and the architect is told.

## Why
Architect review of F-C step 5. The dimming and the sideways scroll moved from one table-wide node to one node per row, and a per-row layer or scroll node can cut the keyboard hint popup (that is how 02a/02b lost 8 px during the build). The tester's mutation run left the `rowScroll` clamp alive; the reason given ("ScrollState already clamps") is true for gestures only, see test A.

## Scope
In (tests, all written to pass on the current code; if one is red, that is the finding):
- A. `rowScroll` clamp: scroll a narrow window (700 px, 40 rows) to the last column, then widen the window (a state or a `Modifier.width` that grows, same composition, so the `ScrollState` keeps its value), and assert that the row cells and the header cells still line up and that the last column ends at the viewport edge, not shifted left of it. This kills the mutant "remove `coerceIn(0, placeable.width - width)`": `ScrollState.value` is clamped on `scrollBy`, not on a resize, so without the clamp a data row is placed at `-value` past its own overflow while the header (which clamps in its own layout) is not. If the test cannot be written without the mutant surviving, say why and mark the mutant equivalent in the brief.
- B. Stale popup: with `stale = true` (Stale state, rows dimmed), focus the "i" of a Behind row that is not the last one; the popup is inside the window and the list viewport and really drawn (same pixel rule as test 4, R-FC5). Reason: `Modifier.alpha(0.6f)` is a per-row layer now and used to be one section layer; a layer can clip what it draws outside its bounds, the popup is drawn outside the row. A fresh row has no layer (that is why 02a/b/c pass), a stale one has, and no test or screenshot covers it. If it fails, the fix is not a `Popup` window (R-FC5, M2-06 round 2): dim with a colour alpha on the text and divider colours, or a `graphicsLayer` on the list item with `clip = false`, decided by the developer, test first.
- C. Swipe on a data row: in the 700 px window, swipe on a data cell (not the header) and assert the header and all rows moved together. Test 10 swipes the header only, so the `scrollable` on the rows is never driven by a gesture.
Out: any change to texts, columns, screenshots (a stale screenshot is optional; the pixel rule in B is the contract). Accessibility scroll actions on the rows (only the header carries `horizontalScroll` semantics; rows have `scrollable` only): noted as an idea, not a requirement.

## Acceptance
- BDD: none (rendering only, no `.feature` change).
- UI: three tests in `ui/src/jvmTest/kotlin/dev/caerbannog/ui/streams/StreamClientsLazyUiTest.kt`, named after the behaviour, fake state only, no sleeps.
- Mutation check: remove the clamp, test A goes red; make the stale layer clip, test B goes red (record both in the verify report).

## Design notes
Fake state only. Use `bounds(...)`, `list()` and the pixel helper already in the test file and `StreamClientsHintPopupUiTest`. No change to production code in this task unless a test is red.

## UX
`docs/ux/Clients.dc.html`; no change.

## Open points
Whether the stale layer clips popups on desktop Skia is unverified; test B answers it.

## Definition of done
Tests written and green (or the finding raised), mutation results recorded in the brief Status of F-C, `StreamClientsLazyUiTest` and the clients screenshot tests green, no unrelated changes. This task is a merge condition of F-C (see the F-C brief, Status).
