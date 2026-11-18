# Planner wizard: Cancel and Next stay reachable on a short window at a large font

Milestone: M9 (follow-up)    Depends on: M9-08 (merged)    Size: S

## Goal
On every wizard page, at a window 420 high and font scale 1.5, the Cancel and Next (and Back) buttons are displayed or reachable without guessing, as they are on 1280 x 600.

## Why
Finding 1 of the M9-08 step 4 report (test developer): at 1280 x 420 and font scale 1.5 the buttons of wizard page 1 are not displayed. Not measured on the base commit `0dcba3a4`: the sixth card (Policy) may have made it worse, or it may be older. The first thing to do is measure both commits.

## Scope
In: a Compose test that reproduces it on page 1 (and pages 2 and 3 at the same size); measure it on `0dcba3a4` and on the M9-08 head and say which; then the smallest layout fix (the buttons in a fixed footer outside the scrolling content, as the Publish dialogs do in M9-10e, or a content area with its own scrollbar and the footer pinned).
Out: other dialogs; a new wizard layout; the cards' look.

## Acceptance
- Compose: `PolicyFormScaleUiTest` gets the 420-high, font-scale-1.5 row that today is only a comment ("Finding ... Kept as a note, not asserted"): page 1, the Policy form, the summary page. Cancel and Next displayed on each.
- Existing `PlanWizardUiTest`, `MqttRouteLayoutExploratoryUiTest` and the M9-07b fold tests stay green; no `testTag` changes.

## Design notes
`ui/planner/PlanWizardDialog.kt` only, plus tests. Remember the Compose pitfall in `CLAUDE.md` (scroll gives children infinite size); the scroll goes where the content overflows, with a scrollbar for mouse users.

## UX
`docs/ux/Planner.dc.html`, wizard pages 1 to 3. No new state.

## MCP
None (UI-only layout).

## Open points
Whether this is old or new (measure first; the answer goes into the brief's status).

## Definition of done
Tests first and failing for the right reason, then green; no unrelated changes.

Status 2026-11-18: briefed (ready); parent task merged.
