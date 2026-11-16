# M9-06: Check against the broker on the screen

Milestone: M9    Depends on: M9-05, M9-03    Size: S

PLANNED 2026-11-15. Not started.

## Goal
"Check against <connection>" runs the plan check and shows a result per step, a summary line with the time, progress while it runs, and a stale marker as soon as the plan changes. A result of an old connection is never shown.

## Why
Spec "Topology planner (M9)", "Check against the broker"; mockup section 9.

## Scope
In:
- `core`: `PlannerViewModel.check()`; state `CheckState` (`None`, `Running(step, total)`, `Done(results, connectionName, atMillis, stale)`); the summary text built in `PlannerTexts` from the counts; a change of the plan marks the result stale and keeps it; a change of the held connection drops it; a result that arrives for a connection that is no longer the current one is dropped (the token rule of the other view models); the check button's label and gate ("Check against uat-eu", disabled with the reason when not connected or when the connection cannot run migrations).
- `ui`: the result pill and the "Blocked" pill on a step row, the reason line, the summary banner, the stale banner with "Check again", the progress banner. Test tags: `planStepResult:<index>`, `planStepBlocked:<index>`, `planner:checkSummary`, `planner:checkStale`, `planner:checkProgress`.

Out: any new rule of the check (`M9-05`); notes about policies (`M9-08`).

## Acceptance
- BDD: `20_topology_planner_check.feature` / "The summary counts the results" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A check result goes stale when the plan changes" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A running check can be seen and does not block the plan" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "Switching the connection drops the check result" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A late check result of an old connection is dropped" (@fake-broker)
- Unit tests, written first: `PlannerViewModelCheckTest` (summary counts and order of the words, stale on every kind of change, dropped on a connection change, late result dropped with a gated fake, no second check while one runs, cancel on leaving the screen), `PlannerTextsTest` additions (the summary sentence for 1 and many, every result word as a literal).
- Compose: `PlannerCheckUiTest` (each result of mockup section 9, both pills for a blocked step, stale dimming, progress, the button label follows the connection).

## Design notes
The time in the summary comes from the injected clock (`RefreshClock`), never from the system clock in a test. The check never enables or disables Save. No broker in this task: `FakeDeclareCheck` with a `CallGate`.

## UX
`docs/ux/Planner.dc.html`: the step list of the main block (pills, summary) and "9. Check against the broker: every result".

## MCP
None (owner decision 2026-11-15).

## Open points
Q14 (decided 2026-11-15, as written): the result words are built as in the spec; a later wording change is a change in `PlannerTexts` only.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, M9-05 step 1, 2026-11-16): (F4) from the M9-04 review moves here, the first task to touch `PlanReviewCard`: raise `SIDE_BY_SIDE_FROM` in `PlannerScreen.kt` from 900 dp to about 1400 dp so a small plan fits at 1280 px (check `PlanReviewGraphRound2UiTest` at 1280). It needs the OWNER'S word (item 11 of the M9-04 owner review list, unanswered); ask before step 3 of this task. Options: A) 1400 dp (recommended); B) keep 900 dp and narrow the nodes; C) leave it.
