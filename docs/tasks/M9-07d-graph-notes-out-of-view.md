# Keep the graph notes in view when labels stack

Milestone: M9    Depends on: M9-07b    Size: XS to S    Priority: optional, after the M9 gate

## Goal
In the review graph, a note (M9-07b) is never pushed below the stacked edge labels where a short card hides it behind a scroll bar.

## Why
Step 5 of `M9-07b` (2026-11-17) found by test: with two or more MQTT routes into one response stream, or several pairs, `placeNotes` skips the slots taken by label blocks and the note slides down under the stack. The review card scrolls, so nothing is lost or overlapped, but the owner has to scroll to read a sentence that belongs to the first row. Owner-visible effect of the fix: none in the words; the note stays near the top row when there is room.

## Scope
In: a rule in `placeNotes` (`ui/.../topology/PlanGraphNotes.kt`, pure) for what to do when the preferred slot and the nearest free slots are taken by label blocks: try the free band to the right of the right-hand column, or stack the notes of several pairs beside the first row, before searching downwards. Measured by a unit test of `placeNotes` (pure rectangles), not by pixels.
Out: any new wording, any change to `planGraphNotes` (core), the same-column placement (decided in M9-07b step 5: next to the request stream and covering nothing is the requirement), a second layout pass using measured label heights.

## Acceptance
- No scenario changes (the BDD glue does not look at geometry).
- Unit tests in `PlanGraphNotesLayoutTest` (new cases, written first by the test developer): two routes into one response stream; three pairs; the note's top is within one note height of the first row's top whenever a free band exists; never overlaps a node, a label block or another note.
- Sweep test `PlanReviewGraphNoteSweepUiTest` stays green; no test is disabled by this task. (No test of M9-07b is `@Disabled("M9-07d")`: the three red tests of step 4 are fixed or relaxed in round 2 of M9-07b, see its "Step 5".)
- The twelve `topology-*.png` hashes stay equal (the Topology graph has no notes).

## Design notes
`ui` only; the rule is pure and takes rectangles. Do not add a pattern; one function and one constant.

## UX
`docs/ux/Planner.dc.html` "8. Review graph: other cases".

## MCP
None (UI-only layout).

## Open points
Rare shape, so this may be dropped if the owner never meets it. No decision needed from the owner.

## Definition of done
Tests written first and passing, no unrelated changes.
