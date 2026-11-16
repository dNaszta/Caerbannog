# M9-08: Policy step: a form with measured settings, the match preview, the one-policy warning, stream retention

Milestone: M9    Depends on: M9-00c (S-F1, S-F2), M9-03, M9-05 (for the note in the check), M9-07 (for the retention block of the stream form)    Size: M

PLANNED 2026-11-15. Not started.

## Goal
The wizard has a Policy kind: name, pattern, what it applies to, priority, and settings chosen from a list that holds only keys measured to work for that target, each with a typed value. The form shows which names the pattern matches, and the plan and the check point out a policy that would hide another one. Per Q4 (decided 2026-11-15), the stream form offers retention, saved as such a policy, once the spike confirms the keys (S-F1).

## Why
Owner decision O7 ("yes policies"). Spec "Topology planner (M9)": A4, A5, "Step kinds", "Check against the broker" (notes).

## Scope
In:
- `core.planner`: `PolicyForm(name, pattern, target, priority = 0, settings)`; `PolicyTarget` (quorum queues, classic queues, queues, streams, exchanges, all) mapped to the `applyTo` texts the format already has; `PolicySetting(key, value)`.
- `PolicyKeyCatalog`: data, one entry per offered key: the broker key, the plain label ("Maximum number of messages"), the value kind (whole number, duration text such as `7D`, one of a list), the targets it is offered for, and the evidence (the fixture or constant of `M9-00c` that proves it, as the M8 rules carry evidence). A key without evidence is not in the catalog. A test fails when an entry names evidence that does not exist.
- Validation: name, pattern, at least one setting, a value of the right kind (fixed messages), priority a whole number.
- Match preview: `PatternMatches.of(pattern, names)` with the existing linear-time `PolicyPatternMatcher` (same module); a pattern it cannot evaluate gives the fixed sentence and does not block.
- The one-policy rule (only if S-F2 confirms it): `PlanValidation` adds a note, not a block, to two policy steps of the plan that match the same declared queue or stream; `PlanCheck` adds a note to a policy step that matches a queue on which the broker already applies another policy (the `policy` name comes with the queue rows of `TopologyCatalog`, via `KnownTopology`). The planner never merges, changes or removes a policy.
- `formOf`: a `DeclarePolicy` whose keys are all in the catalog for its target opens in the form; any other is a read-only step (A2). `DeclareOperatorPolicy` is never built.
- Retention block of the stream form (Q4): "Keep messages for" and "Keep at most"; adds `DeclarePolicy(<stream>-retention, applies to streams)` after the stream steps, with a pattern that matches exactly the stream, or exactly the stream and its response stream when "Same retention for the response stream" is ticked (`ExactPattern.of(names)`, escaped, alternation).
- `ui`: the Policy card, `PolicyFormView` (setting rows with a typed value field), the notes in the step list, the retention block.

Out: operator policies; editing a policy that exists on the broker; a free "other key" row (later).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A policy is built from a form" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "Policy values keep their type in the saved file" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The form offers only the settings measured for what the policy applies to" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A policy form that cannot be added says why" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "The form shows which names of the plan and of the broker the pattern matches" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A pattern the app cannot evaluate is said so and can still be added" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "Two policies of a plan that match the same queue are pointed out" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A policy that would take the place of a policy on the broker is pointed out by the check" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Retention of a stream is saved as a policy" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The response stream gets the same retention unless I untick it" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Operator policies are not built by the wizard" (@fake-broker @negative)
- Unit tests, written first: `PolicyFormTest`, `PolicyKeyCatalogTest` (every entry has evidence; the offered list per target equals the measured effect matrix), `PatternMatchesTest` (reuse of the matcher, hostile patterns cannot hang: the M8-03 cases), `ExactPatternTest` (every regular-expression character escaped; the pattern of one and of two names matches exactly those names with the app's matcher), `PlanValidationPolicyTest`, `PlanCheckPolicyNoteTest`. Compose: `PolicyFormUiTest`.
- Live: the retention policy and one limits policy of the catalog are part of the gate (`M9-13`); this task adds no live test of its own beyond the spike's.

## Design notes
The catalog is data, not code paths: adding a key is one entry plus its evidence. Values are `ArgumentValue` (numbers stay numbers). The pattern text is untrusted once files are opened: only the app's own matcher looks at it. No broker in this task except through the spike's constants.

## UX
`docs/ux/Planner.dc.html`: "4." form Policy, the Retention block of form Stream, the policy rows of "9." (the warning-colour note), step 7 of the main block.

## MCP
None (owner decision 2026-11-15).

## Open points
- S-F1 decides the catalog; S-F2 decides whether the two notes exist at all. If policies turn out to combine, the notes are dropped and A4 is rewritten.
- Q4 (decided 2026-11-15: retention in the stream form as one policy covering the stream and, with a tick, its response stream): built only after S-F1d confirms the keys; if a policy-set retention is not shown by the app's stream list (it reads the stream's arguments today), that is reported as a follow-up for the stream list, not fixed here.
- A plain label per key is wording: the list of labels is shown to the owner with the spike results before step 2.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

- Notes from the M9-07 review (2026-11-16): (F2) three nodes in one column draw a graph scrollbar at 1280 x 800 (M9-04 layout), look at it when the policy node is added; remove the unused `GRAPH_BOX_MIN` in `PlannerScreen.kt` or derive the card floors from it while touching that file.
