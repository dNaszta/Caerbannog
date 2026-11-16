# M9-06: Check against the broker on the screen

Milestone: M9    Depends on: M9-05, M9-03    Size: S

Step 1 done 2026-11-16 (round 0, branch `m9-06-check-on-screen`, base main ea310be). Owner decisions of 2026-11-16 folded in (see Decisions).

## Goal
"Check against <connection>" runs the plan check and shows a result per step, a summary line with the time, progress while it runs, and a stale marker as soon as the plan changes. A result of an old connection is never shown.

## Why
Spec "Topology planner (M9)", "Check against the broker"; mockup section 9.

## Scope
In:
- `core`: `PlannerViewModel.check()`; state `CheckState` (`None`, `Running(step, total)`, `Done(results, connectionName, atMillis, stale)`); the summary text built in `PlannerTexts` from the counts; a change of the plan marks the result stale and keeps it; a change of the held connection drops it; a result that arrives for a connection that is no longer the current one is dropped (the token rule of the other view models); the check button's label and gate ("Check against uat-eu", disabled with the reason when not connected or when the connection cannot run migrations).
- `ui`: the result pill and the "Blocked" pill on a step row, the reason line, the summary banner, the stale banner with "Check again", the progress banner. Test tags: `planStepResult:<index>`, `planStepBlocked:<index>`, `planner:checkSummary`, `planner:checkStale`, `planner:checkProgress`.

- Real code facts the brief now follows (checked on main ea310be): `PlannerViewModel` is REBUILT by `PlannerWindowContent` (`desktopApp/Main.kt`) whenever `PlannerContext` changes and its job is cancelled behind the flush, so "switching the connection drops the result" is the new view model starting at `CheckState.None`; the in-view-model token below covers a result that arrives inside one view model after a change it must not survive (a plan edit while running is NOT dropped: it marks the result stale). The view model has no clock and no `DeclareCheck` today: add constructor parameters `declareCheck: DeclareCheck? = null` and `clock: RefreshClock`, wired in `PlannerWiring.createPlannerViewModel` from `inputs`/the held connection (`HeldConnection.declareCheck`). Key the `remember` in `PlannerWindowContent` on the check port too, not on `context` alone, so a reconnect to the same profile does not keep a port of the closed connection (the guard answers "Connection closed" as a last defence). The result lives in the view model: leaving the screen drops it (accepted; the planner draft is what persists).
- `PlannerState`: `checkEnabled` and `checkDisabledReason` stop being constants. Enabled = not locked, connected, `declareCheck != null`, plan has at least one step, no check running. Disabled reasons in this order (step 2.5, a): `context.unavailableReason` (`migrationUnavailableReason` of the connection, shown first), then `PlannerTexts.NOT_CONNECTED`, then `PlanCheck.NO_CHECK` when there is no port, then the empty-plan reason; this is the order of `PlanCheck.run`. `check()` while disabled does nothing (state stays `None`), so a `Refused` result cannot reach the screen through the button; if one still arrives (a race with a closing connection) the view model ends at `None`, not at a state. Delete `PlannerTexts.CHECK_LATER` and its test literal. Label: "Check" while no connection name, otherwise "Check against <connection>" (decided, mockup). Keep `planner:checkBtn`.
- Progress counts STEPS ("Checking step 4 of 10 against uat-eu", from `onStep`), never requests (a binding step may cost up to 3 GETs). Cancel the job on `dismiss` and when the view model's scope ends; no second check while one runs (button disabled, `check()` ignored).
- Summary: "Checked against <connection>" + time from `RefreshClock` + the counts sentence in the spec's order, plus one fixed caveat line: the results describe the broker as it is now, not a promise (a plan that deletes a queue and then binds to it reads "Will be created" for the binding; no test of this task expects otherwise). "Already there" and "Will be deleted" are never worded as a promise (M9-05 owner review item 10).
- Stale: any change of the plan's steps or virtual host (add, edit, remove, move, vhost change) sets `stale = true` and keeps the results; steps added after the check show no result; a stale result keeps its pills dimmed. Results are matched to steps by `StepCheck.stepId`, never by index, because ids survive a move. A Blocked step shows the "Blocked" pill AND its result pill (both, as mockup section 9).
- F4 (owner decided A, 2026-11-16): `SIDE_BY_SIDE_FROM` in `PlannerScreen.kt` from 900 dp to 1400 dp; below it the review card stacks the graph above the JSON; a small plan fits at 1280 px (update `PlanReviewGraphRound2UiTest` at 1280 and any test that assumed side by side at 900 to 1399). Node subtitle: the mockup form "exchange, direct" / "queue, quorum" (with the existing wording if already so). Both go in the FIRST commit that touches `PlanReviewCard`.
- The "Different" note "Applying this step would stop here; nothing on the broker would be overwritten." is drawn as the reason line of that step; screenshots of this task show it and the M9-05 Owner review list (10 items, in `M9-05-check-core-and-data.md`) goes to the owner with them.

Out: any new rule of the check (`M9-05`); notes about policies (`M9-08`); saving the result anywhere; any gate on Save or Apply.

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

## Decisions
- Owner, 2026-11-16 ("recommended"): F4 = option A (1400 dp); M9-04 owner review items 7 to 10 accepted (switch without icons, node subtitle in the mockup form, extra "new" pills on edges, footnote length). Recorded here; both changes are in scope.
- Check = GET only, 4.x only, no write, no Shovel, no queue-message read (all in `M9-05`); this task adds no broker code except the wiring of the existing port.

## Open points
- No product question open. Process: `RealBrokerPlanCheckTest` (`@broker`, M9-05) was NOT RUN (no container runtime on this machine at step 1: no Docker daemon, no Podman). Run `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlanCheckTest*'` before the hand trial if a runtime exists; otherwise say so plainly in the reports.
Q14 (decided 2026-11-15, as written): the result words are built as in the spec; a later wording change is a change in `PlannerTexts` only.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, M9-05 step 1, 2026-11-16, ANSWERED by the owner 2026-11-16: option A): (F4) from the M9-04 review moves here, the first task to touch `PlanReviewCard`: raise `SIDE_BY_SIDE_FROM` in `PlannerScreen.kt` from 900 dp to about 1400 dp so a small plan fits at 1280 px (check `PlanReviewGraphRound2UiTest` at 1280). It needs the OWNER'S word (item 11 of the M9-04 owner review list, unanswered); ask before step 3 of this task. Options: A) 1400 dp (recommended); B) keep 900 dp and narrow the nodes; C) leave it.

Note (architect, M9-05 step 5, 2026-11-16): (a) the check probes the broker as it is now, so a plan that deletes a queue and later binds to it shows the binding as "Will be created" although the queue is gone after the delete step; the summary must carry one line saying the results describe the broker today (not a promise), and no test of this task may expect the other behaviour. (b) A binding step may cost up to three reads (the binding and its two ends); progress counts steps, not requests. (c) When `migrationUnavailableReason` exists, show it for a refused check; `PlanCheck.NO_CHECK` is the fallback. (d) The wording items 1 to 10 of the Owner review list in `M9-05-check-core-and-data.md` are shown to the owner with this task's screenshots. (e) The `@broker` scenario of M9-05 was not run; run it before this task's hand trial if a container runtime exists.

## API shape confirmed (architect, step 2.5, 2026-11-16, round 0)
The tests are the exact API spec. Confirmed as the test developer proposed, plus these decisions:
1. Core: `PlannerViewModel(..., reader, declareCheck: DeclareCheck? = null, clock: RefreshClock = RefreshClock { 0L })`. The zero default exists only so the ~25 older constructions compile; the app NEVER uses it: `createPlannerViewModel` passes `System.currentTimeMillis()` (a `CompositionRootPlannerCheckTest` assertion pins the wiring; verify by reading). `PlannerState.check: CheckState`, `checkEnabled`, `checkDisabledReason`, `checkLabel`, `connectionName`, `stepCheck(stepId)` as in the tests. Removing a step DROPS its result (`TopologyPlan.append` reuses max(id)+1, so a kept result would stick to a later step): pinned by "a step added after the last step was removed does not inherit...".
2. `PlannerTexts` as in `PlannerTextsCheckTest`; the time is formatted by the screen (HH:mm, system zone), never in core; "blocked" is last and counts every step whose `blocked` is non-null; zeros omitted.
3. Ui: abstract `PlannerEvents.check()` (+ adapter); tags `planStepResult:<i>`, `planStepBlocked:<i>`, `planStepReason:<i>`, `planner:checkSummary`, `planner:checkStale`, `planner:checkProgress`, `planner:checkAgainBtn`, `planner:checkBtn`; the stale pill has `stateDescription` "stale"; node subtitle is `GraphNode.detail` ("exchange, direct", "queue, quorum"); the Topology screen keeps "(topic)".
4. desktopApp: `MigrationsInputs.declareCheck`, `createPlannerViewModel` passes the port and the system clock, `PlannerKey(context, declareCheck)` / `plannerKeyOf(inputs)` is the `remember` key.
5. Existing tests changed (`PlanReviewGraphRound2UiTest` at 1280: no clipped node and no scrollbar, the sanctioned 1400 dp change; `PlanPreviewTest`; the two `CHECK_LATER` literals; glue; `FakeDeclareCheck`): all acceptable, nothing else may change.

Decisions on the open technical points:
- a. Reason order: unavailable, NOT_CONNECTED, NO_CHECK, empty plan (as `PlanCheck.run`). Not connected with no port (the real app case before connecting) shows NOT_CONNECTED. Must be pinned: new test `not connected and no port - the not-connected text` in `PlannerViewModelCheckTest`.
- b. No refused state. A disabled check does nothing and the reason is shown beside the button (`planner:checkBtn` disabled with its reason text, as today).
- c. Cancellation is pinned on the confirmed dismiss, not on the question. Confirmed.
- d. A description edit does NOT mark the result stale (the check reads steps and virtual host only). New test `editing the description does not mark the result stale` in `PlannerViewModelCheckTest` (a `setDescription` after a check, `stale` stays false, results kept). "Load of a draft" is not a stale cause (the draft loads at construction); removed above.
- e. Stream node subtitle in the plan graph: `stream` (the mockup form, no parenthesis, no count). New test in `PlanPreviewSubtitleTest`: a declared stream and a stream the broker has say "stream"; the parenthesis test extends to the stream nodes. The Topology screen keeps "(stream)". Not user-visible beyond the mockup.
- f. A port that throws: the planner stays usable, state not `Running`, back to `None`; the screen shows nothing extra (the button is usable again). A visible failure is a follow-up only if the hand trial shows a port that throws in practice (`ManagementDeclareCheck` answers `Unreadable`, it does not throw).
- g. Running replaces Done in the single `check` field, so the old pills and the stale banner are HIDDEN while a re-check runs and the progress banner shows (simplest, consistent with the mockup: one banner at a time). No dimmed-old-results state.
- h. Wording the owner has not decided, added to the Owner review list below.
- i. The `remember` key in `PlannerWindowContent` has no seam; `plannerKeyOf` and `PlannerKey` are tested. The verify step must read `Main.kt` and confirm that `remember(plannerKeyOf(inputs))` (not `remember(context)`) builds the view model. A seam is not worth it for one line.
- j. `RealBrokerPlanCheckTest` (M9-05, `@broker`): the verify step runs `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlanCheckTest*'` if `docker info` (or Podman) answers; otherwise it says plainly "not run". This task is not "done" for the owner without it.
- Safety: the Check button only calls `PlannerViewModel.check()`, which runs `PlanCheck` through the `DeclareCheck` port of the held connection (the GET-only, allow-listed `ManagementDeclareCheck` behind the closed-connection guard). Nothing writes, no Shovel, no queue-message read, no MCP tool.

Tests that must change: none of the existing ones; three tests are ADDED by the test developer before step 3 (a, d, e above).

## Owner review list (wording the tests fix; recommendations, not decisions)
1. Caveat line under the summary (non-blank, contains "now" and "promise"): recommend "This is how the broker looks now; it is not a promise of what applying will do."
2. Progress line suffix: "Checking step 4 of 10 against uat-eu. Only reads are sent." Recommend keep the suffix (it tells the user nothing is changed).
3. Place of "N will be deleted" and "N already gone" in the summary order: recommend right after "N will be created" and "N already there", before "different", "missing", "could not be checked", and "blocked" last.
4. Plus the 10 items of `M9-05-check-core-and-data.md`, shown with this task's screenshots.

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-06-check-on-screen`, base main ea310be): brief checked against the spec, the 5 `@m9-06` scenarios (unchanged, they fit) and the real code (`PlanCheck`, `PlanCheckResult`, `StepCheck`, `StepFinding`, `PlannerViewModel`, `PlannerState`, `PlannerScreen`, `PlannerWiring`, `Main.kt`). Brief CHANGED: view model has no clock or port (new constructor parameters and wiring), the view model is rebuilt on a connection change (so the drop is a fresh state plus a token for late results inside one view model), step-id matching, disabled-reason order with `migrationUnavailableReason` first, caveat line, progress by step, `CHECK_LATER` removed, F4 (1400 dp) and node subtitle folded in, `## Decisions` added. No open product question. Next: step 2 (test developer, tests first, red).
- Step 2 (test developer, 2026-11-16, round 0, same branch): tests written first, RED (compile errors for the missing production code in `core`, `ui`, `desktopApp` and `acceptance-tests`; nothing else fails). Core: `PlannerViewModelCheckTest` (41 tests: result by step id, progress by step, stale on add, edit, remove, move up and down and vhost change, a plan edit while running, no second run, cancel on dismiss and on scope end, late result dropped, a failing port, the gate and its reason order, the label, Save untouched), `PlannerTextsCheckTest`, `PlanPreviewSubtitleTest`; `PlannerRig` and `FakeDeclareCheck` (`holdOn`, `cancelled`) extended. Ui: `PlannerCheckUiTest`, `PlannerLayoutUiTest` (1400 dp breakpoint, node subtitle), opt-in `PlannerCheckScreenshotTest`. desktopApp: `CompositionRootPlannerCheckTest` (port in the inputs, `plannerKeyOf`, reconnect). Acceptance: the 5 `@m9-06` scenarios bound in `PlannerCheckScreenSteps` and the tag filter widened. Existing tests changed, and only these: `PlanReviewGraphRound2UiTest` (at 1280 the graph must fit with no scrollbar), `PlanPreviewTest` ("(topic)" to "exchange, topic"), and the two `CHECK_LATER` literals (`PlannerStateDerivedTest`, `PlannerScreenUiTest`), as this brief says. Proven consistent with a throwaway production stub (all green, 2800 `core`, 143 planner `ui`, 32 planner `desktopApp`, the planner acceptance suite), then deleted; the layout, subtitle and round 2 tests were also seen red on the old 900 dp rule. Open questions for the architect are in the step 2 report. `RealBrokerPlanCheckTest` (`@broker`, M9-05) still NOT RUN. Next: step 3 (senior developer).
- Step 2.5 (architect, 2026-11-16, round 0): tests and glue reviewed; API shape confirmed, decisions a to j in "API shape confirmed". Brief changed: reason order (NOT_CONNECTED before NO_CHECK), description edit is not stale, no load-of-draft cause, running hides old results, stream subtitle "stream", Owner review list added. Three tests to ADD (no existing test changes): not connected and no port; description edit not stale; stream subtitle. `RealBrokerPlanCheckTest` still NOT RUN. Next: test developer adds the three tests (red), then step 3.
