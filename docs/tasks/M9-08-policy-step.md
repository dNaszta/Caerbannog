# M9-08: Policy step: a form with measured settings, the match preview, the one-policy warning, stream retention

Milestone: M9    Depends on: M9-00c (S-F1, S-F2), M9-03, M9-05 (for the note in the check), M9-07 (for the retention block of the stream form)    Size: M

PLANNED 2026-11-15. Not started. Updated 2026-11-16 (step 3 of `M9-00c`): S-F1 and S-F2 are measured; see "What the spike settled" below.

## Goal
The wizard has a Policy kind: name, pattern, what it applies to, priority, and settings chosen from a list that holds only keys measured to work for that target, each with a typed value. The form shows which names the pattern matches, and the plan and the check point out a policy that would hide another one. Per Q4 (decided 2026-11-15), the stream form offers retention, saved as such a policy, once the spike confirms the keys (S-F1).

## Why
Owner decision O7 ("yes policies"). Spec "Topology planner (M9)": A4, A5, "Step kinds", "Check against the broker" (notes).

## Scope
In:
- `core.planner`: `PolicyForm(name, pattern, target, priority = 0, settings)`; `PolicyTarget` (quorum queues, classic queues, queues, streams, exchanges, all) mapped to the `applyTo` texts the format already has; `PolicySetting(key, value)`.
- `PolicyKeyCatalog`: data, one entry per offered key (the table of "Measured facts of M9-00c"; `PolicyKeyMatrix.effect` is the evidence and the unit test compares the two): the broker key, the plain label ("Maximum number of messages"), the value kind (whole number, duration text such as `7D`, one of a list), the targets it is offered for, and the evidence (the fixture or constant of `M9-00c` that proves it, as the M8 rules carry evidence). A key without evidence is not in the catalog. A test fails when an entry names evidence that does not exist.
- Whole-policy rule (S-F1b): a policy binds only when every key suits the type, so the form offers, for a target, only the keys that EVERY queue type of that target honours: quorum queues the quorum set, classic queues the classic set, streams the stream set, exchanges `alternate-exchange` only, and for "queues" and "all" the three keys all types honour (`max-length-bytes`, `queue-leader-locator`, `alternate-exchange`; the effect was measured with `apply-to: all`, so this is the conservative reading; whether `apply-to: queues` includes streams was not measured). The form says why the list is short (proposed sentence in scenario "A policy that applies to more than one kind of target offers only what all of them honour", Owner review list item 2). A policy loaded from a file whose keys are not honoured by one queue it matches gets the note "has no effect" (proposed wording, item 3).
- Validation: name, pattern, at least one setting, a value of the right kind (fixed messages), priority a whole number. `max-age` is always a TEXT of digits and a unit (`7D`): a JSON number makes the broker answer 500 with an empty body (S-F1c), so the form and `PolicySetting` never build it and a unit test pins it; a hand-written file with a numeric `max-age` is shown as a read-only step (A2) and the check says nothing special (the apply reports the broker's error). Units measured: `Y`, `D`, `s`; `M`, `h`, `m` only after a live row.
- Match preview: `PatternMatches.of(pattern, names)` with the existing linear-time `PolicyPatternMatcher` (same module); a pattern it cannot evaluate gives the fixed sentence and does not block.
- The one-policy rule (CONFIRMED, S-F2a: the highest priority applies, nothing is merged, equal priority is unpredictable). A policy only counts when it WOULD BIND to a queue: its pattern matches the name, its `apply-to` matches the queue's type (a declared queue's type is known; a broker queue's type comes with the `TopologyCatalog` row; an unknown type counts as a match for the note only when `apply-to` is not a specific other type), and EVERY key of its definition is honoured by that type (`PolicyKeyMatrix.effect`). `PlanValidation` adds a note, not a block, to two policy steps of the plan that would bind to the same declared queue or stream (the existing note text stands; with different priorities the lower one never applies, with equal priority one of them does: the same note is correct for both); two policies that cannot bind to one queue (other `apply-to`) get no note. `PlanCheck` adds one of three notes to a policy step that would bind to a queue on which the broker already applies another policy (`policy` of the queue row, via `KnownTopology`; its priority and keys are read with one GET of that named policy, an item path the allow-list already has (never the policy list)): this step has the higher priority ("would take its place", existing wording), the lower priority ("would not apply", proposed wording), the same priority ("one of the two, not predictable", proposed wording). An operator policy on the broker is not warned about (it combines, the smaller number wins; S-F2b). The planner never merges, changes or removes a policy.
- `formOf`: a `DeclarePolicy` whose keys are all in the catalog for its target opens in the form; any other is a read-only step (A2). `DeclareOperatorPolicy` is never built.
- Retention block of the stream form (Q4): "Keep messages for" and "Keep at most"; adds `DeclarePolicy(<stream>-retention, applies to streams)` after the stream steps, with a pattern that matches exactly the stream, or exactly the stream and its response stream when "Same retention for the response stream" is ticked (`ExactPattern.of(names)`, escaped, alternation).
- `ui`: the Policy card, `PolicyFormView` (setting rows with a typed value field), the notes in the step list, the retention block.

Out: operator policies; editing a policy that exists on the broker; a free "other key" row (later).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A policy is built from a form" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "Policy values keep their type in the saved file" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The form offers only the settings measured for what the policy applies to" (outline, 8 rows; @fake-broker; `@needs-spike` removed)
- BDD: `21_topology_planner_recipes.feature` / "A policy form that cannot be added says why" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "The form shows which names of the plan and of the broker the pattern matches" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A pattern the app cannot evaluate is said so and can still be added" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "Two policies of a plan that match the same queue are pointed out" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Policies of a plan that cannot bind to the same queue are not pointed out" (outline, @fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A policy whose settings one kind of queue ignores is said to have no effect on it" (@fake-broker @needs-decision: proposed wording)
- BDD: `21_topology_planner_recipes.feature` / "A policy that would take the place of a policy on the broker is pointed out by the check" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A policy that would never apply because the broker's policy has the higher priority is pointed out by the check" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "A policy with the same priority as the broker's policy is pointed out by the check" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "A policy on a queue that the broker applies no policy to is not pointed out" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A policy that applies to more than one kind of target offers only what all of them honour" (outline, @fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "Keep messages for is saved as text" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Retention of a stream is saved as a policy" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The response stream gets the same retention unless I untick it" (@fake-broker)
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
- Settled by the spike (2026-11-16): the catalog (S-F1b), the notes exist (S-F2a: no merge). Not settled: whether a policy with `apply-to: queues` includes streams (conservative reading above), the winner of an equal-priority tie (the note says it is not predictable), `M`, `h`, `m` as `max-age` units.
- Q4 (decided 2026-11-15: retention in the stream form as one policy covering the stream and, with a tick, its response stream): the keys are confirmed (S-F1b: `max-age`, `max-length-bytes`, `stream-max-segment-size-bytes` take effect on a stream). The stream list does NOT show a policy-set retention (S-F1d): the follow-up is `M9-08b`, to be merged before the retention block is released. Not fixed here.
- A plain label per key is wording: the list of labels is shown to the owner with the spike results before step 2 (Owner review list item 2 of `M9-00c`, together with the target labels and the three proposed notes, items 3 to 5).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

- Notes from the M9-07 review (2026-11-16): (F2) three nodes in one column draw a graph scrollbar at 1280 x 800 (M9-04 layout), look at it when the policy node is added; remove the unused `GRAPH_BOX_MIN` in `PlannerScreen.kt` or derive the card floors from it while touching that file.

## What the spike settled (M9-00c, 2026-11-16)
This task may rely on, without measuring again: `PolicyKeyMatrix` (accept, effect, `wrongTypeRows`; core-testing), the whole-policy rule, the one-policy rule with priorities, the 500 on a numeric `max-age`, the policy delay of about 6 seconds (any live wait is a bounded poll), and the table "Measured facts of M9-00c" in `docs/spec.md`. The catalog test compares the catalog with `PolicyKeyMatrix.effect`. It may NOT rely on: operator policies, `apply-to: queues` reaching streams, the tie winner.
