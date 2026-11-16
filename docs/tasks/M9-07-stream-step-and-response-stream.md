# M9-07: Stream step with the optional response stream (DLN)

Milestone: M9    Depends on: M9-03, M9-04 (M9-00b is NOT a dependency of this task: the one sentence that needs S-D is cut, see Open points)    Size: S

## Status
- Step 1 (brief, architect), round 0, 2026-11-16: brief changed. The spike M9-00b has not run (no container runtime), so the task starts without it: the explaining sentence is worded neutrally, with no claim about the broker; the S-D sentence is deferred (see Open points). The M9-06 follow-up F1 and the layout items are folded in. No open product question; one wording is listed for the owner's review.

- Step 2 (test developer, 2026-11-16, round 0, same branch): tests written first, RED (compile errors for the missing production code in `core`, `ui` and `acceptance-tests`; nothing else fails). Core: `StreamFormTest` (16), `PlanPairsTest` (12), `PlanPreviewResponseStreamTest` (9), `StepFormsTest` (5), `PlannerTextsStreamTest` (7), `PlannerViewModelStreamTest` (18). Ui: `StreamFormUiTest` (20), `PlanStepListPairUiTest` (11), F1 test in `PlanReviewGraphRound2UiTest`, gap and step-list-scroll tests in `PlannerLayoutUiTest`, opt-in `PlannerStreamScreenshotTest` (7 pictures). Acceptance: the 12 `@m9-07` scenarios bound (`PlannerStreamSteps`, filter widened). Proof of consistency: a throwaway stub of the production code (not committed) made all new core and acceptance tests pass and left exactly four ui tests red for pre-existing reasons (F1 scrollbar after a check, 63 dp gap, step list scroll at 1600 x 1000, no indent); the stub is deleted. Existing tests changed: `PlannerViewModelWizardTest` (Stream is a built kind: the "not built yet" test now uses Policy), `PlanWizardUiTest` (Stream is drawn). One scenario line changed: the outline of the suggested name now says `I chose the naming style "<style>" in the planner` (the old `the naming style is` step asserts, it cannot set). Two stand-ins for M9-12 ("a pending migration ... declares the streams", `I choose "Edit steps" for ...`) work on the draft file. API shape beyond the brief is in the step 2 report for the architect to confirm (step 2.5).

## Goal
The wizard has a Stream kind. Its form asks for a name and offers the tick "Also create its response stream (DLN)", off by default; with the tick a second stream with a suggested name is added. The pair is shown as a pair in the step list and linked in the review graph, also after the file is saved and opened again, as long as the name follows the rule.

## Why
Owner decisions O9 ("DLN ... is a response stream which is one 'pair' of the 'request'"), O10 ("Stream pair - it is optional"), O11 (suffix by default). Spec "Topology planner (M9)", "Streams and the response stream", A11.

## Scope
In:
- `core.planner`: `StreamForm(name, withResponse = false, responseName = null)`; `actions` gives one `DeclareStream`, or two with the response stream second; the response name is `PlanNaming.derived(name, DerivedWord.DLN, style)` and follows the stream name until the user edits it; both names get the naming hint; `problems`: empty names, the two names equal, a name the plan already uses for a queue.
- Recognition (A11): `PlanPairs.responseOf(plan): Map<stepId, stepId>`: a `DeclareStream` whose name gives a non-null `PlanNaming.baseOf(name, DLN)` that is the name of another `DeclareStream` of the same plan. Display only: no marker is written, removing the stream leaves the response stream as an ordinary stream.
- The step words: the response stream's second line "Response stream (DLN) of <name>".
- The explaining text under the tick, fixed in `PlannerTexts` (`RESPONSE_STREAM_NOTE`), worded neutrally: it says only what the planner does, no claim about what the broker does (S-D is not measured, so no sentence about messages moving or not moving).
- `PlannerTexts`: `BUILT_KINDS`, `kindDescription` and the wizard kind page get Stream (`WIZARD_KINDS` already lists it); `PlannerViewModel.chooseKind` accepts it; `StepForms.formOf` maps a `DeclareStream` to `StreamForm` (a stream has no arguments, so every `DeclareStream` is editable; a response stream recognised by `PlanPairs` is edited as a stream, the tick is only for adding).
- `PlanGraph`: `planPreview` fills `links` with `PlanLink(stream, response, PlanLinkKind.ResponseStream)` from `PlanPairs`; the placeholders `PlanLinkKind.DeadLetter` and `PlanGraphNote` stay for `M9-09` and `M9-07b`. The graph draws the link dotted with the label "response (DLN)".
- Optional: `PlanValidation.Need.kind` string becomes a small private enum if a stream rule touches it (no stream rule is required here: the queue-and-stream same-name conflict exists, and `PlanCheck` already treats a stream as a queue name).
- Planner screen layout (follow-ups of M9-06 F1 and the owner's "as recommended" items, all in `ui`): (a) F1: after a check at 1280 x 800 the review graph box of a 3-step plan must not draw a scrollbar: make the check banner shorter (for example summary and caveat on one line when they fit, or the banner beside the step list header) or let the graph box take the remaining height; the caveat text `CHECK_CAVEAT` and the summary words stay as they are (owner, item 1 of the M9-06 list); (b) the gap above the review card in the stacked layout is closed; (c) the step list no longer scrolls inside its own panel at screenshot size when the page has room (cheap fix only; if it needs more than a half day, record it as a note and stop).
- `planPreview`: a `PlanLink(RESPONSE_STREAM, stream, response)`; `ui`: the Stream card in the wizard, `StreamFormView`, the indented pair row in the step list, the dotted link in the graph.

Out: retention fields (`M9-08`); routing for the pair: the two optional MQTT ticks of the owner's request and answer flow are `M9-07b` (O13, Q3 revised), and without a tick the pair has no routing (decided); super streams.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A stream is one step" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "The response stream is optional and off until I tick it" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A stream can get its response stream in the same wizard step" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "The response stream name is suggested with a suffix in the naming style" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The suggested name follows the stream name until I change it" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A response stream with a name of my own gets a hint and is still added" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A stream pair is two streams and no routing unless an MQTT tick is set" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The wizard says what a response stream is" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A saved pair is shown as a pair again when its name follows the rule" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Two streams whose names do not follow the rule are two streams" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The review graph links a stream with its response stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Removing the stream does not remove its response stream silently" (@fake-broker)
- Unit tests, written first: `StreamFormTest` (one or two actions, order, the suggestion per style, the follow-until-edited rule as a table), `PlanPairsTest` (each style's derived name is recognised; a free name is not; a stream named like a derived name without its base in the plan is not; two candidates), `PlanPreviewTest` additions (the link), `StepFormsTest` addition (`formOf(DeclareStream)`), `PlannerTexts` guard test (the note says nothing about what the broker does). Compose: `StreamFormUiTest`, `PlanStepListPairUiTest`, and in `PlanReviewGraphRound2UiTest` a checked 3-step plan at 1280 x 800 with no scrollbar on the graph box (F1); the stacked layout gap and the step list scroll get a layout test in `PlannerLayoutUiTest`.

## Design notes
`declareStream` stays a name-only action and the stream manager is untouched: this task adds no broker call and no executor change. The pair is two independent steps; nothing ties their lifetime. No broker: fakes only.

## UX
`docs/ux/Planner.dc.html`: "4." form Stream (this task builds the name and the response-stream tick; the MQTT ticks are `M9-07b` and the Retention block is `M9-08`), step 8 and 9 of the main block, the dotted link in the review graph.

## MCP
None (owner decision 2026-11-15).

## Open points
- Q2 (decided 2026-11-15): the on-screen word is "Response stream (DLN)".
- Q3 (revised, O13): without an MQTT tick the pair is two streams and no routing, which is what this task builds; the ticks are `M9-07b` (the owner confirmed the revised Q3 on 2026-11-15, O14), which waits for the MQTT route step (`M9-10`), so this task does not.
- Q6 (decided 2026-11-15): the suffix word is fixed, `dln`.
- S-D (the spike M9-00b has NOT run; this machine has no container runtime). What needs the spike: only the one sentence "RabbitMQ does not move messages into it by itself: your applications write to it.", because it is a claim about the broker (S-D1 to S-D3). Everything else is planner behaviour and needs no broker fact: names, the suffix, the pair recognition (a naming rule on the plan, A11), the link, the step words, and `declareStream`, which exists and is measured by M5/M8. Decision: M9-07 starts now, like `M9-05b` was cut from `M9-05`. The sentence is cut and the scenario "The wizard says what a response stream is" is reworded (feature edited, `@needs-spike` removed) to the planner-only text: "A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them." Both halves are true of the planner by construction (Q3 revised, O13). When `M9-00b` has run, S-D2/S-D3 may add one sentence about the broker; that is a small follow-up (a new `PlannerTexts` constant and an added scenario), not part of this task. If M9-07b later adds an MQTT route, it rewords "no routing" itself.
- Wording review (owner, not blocking): the neutral note above is new text; the owner may reword it at the next review. No product decision blocks the task.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, M9-06 step 5, 2026-11-16, follow-up F1): after a check at 1280 x 800 the two-line check banner (summary plus caveat, about 60 px) shortens the review graph box for a 3-step plan so it draws a scrollbar, against the M9-04 F4 intent (a small plan readable at 1280). M9-07 is the next task to touch the planner screen: make the banner take less height (for example the caveat on the same line when it fits, or the banner beside the step list header) or let the graph box take the remaining height, and extend `PlanReviewGraphRound2UiTest` with a checked 3-step plan at 1280 x 800 (no scrollbar). Do not shorten or reword the caveat text without the owner (item 1 of the M9-06 Owner review list). Also look at the stacked layout gap above the review card (item 10).
