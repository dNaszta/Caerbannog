# M9-07: Stream step with the optional response stream (DLN)

Milestone: M9    Depends on: M9-00b (S-D, for the explaining text), M9-03, M9-04    Size: S

PLANNED 2026-11-15. Not started.

## Goal
The wizard has a Stream kind. Its form asks for a name and offers the tick "Also create its response stream (DLN)", off by default; with the tick a second stream with a suggested name is added. The pair is shown as a pair in the step list and linked in the review graph, also after the file is saved and opened again, as long as the name follows the rule.

## Why
Owner decisions O9 ("DLN ... is a response stream which is one 'pair' of the 'request'"), O10 ("Stream pair - it is optional"), O11 (suffix by default). Spec "Topology planner (M9)", "Streams and the response stream", A11.

## Scope
In:
- `core.planner`: `StreamForm(name, withResponse = false, responseName = null)`; `actions` gives one `DeclareStream`, or two with the response stream second; the response name is `PlanNaming.derived(name, DerivedWord.DLN, style)` and follows the stream name until the user edits it; both names get the naming hint; `problems`: empty names, the two names equal, a name the plan already uses for a queue.
- Recognition (A11): `PlanPairs.responseOf(plan): Map<stepId, stepId>`: a `DeclareStream` whose name gives a non-null `PlanNaming.baseOf(name, DLN)` that is the name of another `DeclareStream` of the same plan. Display only: no marker is written, removing the stream leaves the response stream as an ordinary stream.
- The step words: the response stream's second line "Response stream (DLN) of <name>".
- The explaining text under the tick, fixed in `PlannerTexts`, worded from S-D.
- `planPreview`: a `PlanLink(RESPONSE_STREAM, stream, response)`; `ui`: the Stream card in the wizard, `StreamFormView`, the indented pair row in the step list, the dotted link in the graph.

Out: retention fields (`M9-08`); routing for the pair (Q3: none); super streams.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A stream is one step" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "The response stream is optional and off until I tick it" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A stream can get its response stream in the same wizard step" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "The response stream name is suggested with a suffix in the naming style" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The suggested name follows the stream name until I change it" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A response stream with a name of my own gets a hint and is still added" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A stream pair is two streams and nothing else" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "The wizard says what a response stream is" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A saved pair is shown as a pair again when its name follows the rule" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Two streams whose names do not follow the rule are two streams" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The review graph links a stream with its response stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Removing the stream does not remove its response stream silently" (@fake-broker)
- Unit tests, written first: `StreamFormTest` (one or two actions, order, the suggestion per style, the follow-until-edited rule as a table), `PlanPairsTest` (each style's derived name is recognised; a free name is not; a stream named like a derived name without its base in the plan is not; two candidates), `PlanPreviewTest` additions (the link). Compose: `StreamFormUiTest`, `PlanStepListPairUiTest`.

## Design notes
`declareStream` stays a name-only action and the stream manager is untouched: this task adds no broker call and no executor change. The pair is two independent steps; nothing ties their lifetime. No broker: fakes only.

## UX
`docs/ux/Planner.dc.html`: "4." form Stream (without the Retention block, which is `M9-08`), step 8 and 9 of the main block, the dotted link in the review graph.

## MCP
None (owner decision 2026-11-15).

## Open points
- Q2 (the on-screen word): built as "Response stream (DLN)".
- Q3 (routing for the pair): built as recommended, the two streams only.
- Q6 (suffix word fixed): built as `dln`.
- S-D: the sentence "RabbitMQ does not move messages into it by itself: your applications write to it." is used only if the spike confirms it; otherwise the architect rewords it before step 2.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
