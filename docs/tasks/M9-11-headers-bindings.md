# M9-11: Bindings on a headers exchange: headers and the match rule in the binding form

Milestone: M9    Depends on: M9-00a (S-E), M9-03    Size: S

PLANNED 2026-11-15. Not started. Decided 2026-11-15 (Q1): headers and topic exchanges are in the first version.

## Goal
When the source of a binding is a headers exchange, the binding form asks for headers (name, value, value type) and the match rule (all or any) instead of a routing key, and writes them as binding arguments. Two bindings that differ only in their headers are two steps.

## Why
Owner decision O8 read with "like header, etc." (Q1). Spec "Topology planner (M9)", "Step kinds" (Binding). A headers exchange without binding arguments cannot be used, so the type is not offered without this form.

## Scope
In:
- `core.planner`: `BindingForm` gains `headers: List<HeaderMatch>` (name, `ArgumentValue` of kind text, whole number or boolean) and `matchRule` (all, any); `actions` writes them as `DeclareBinding.arguments` with `x-match`; `formOf` opens a binding in this form when its arguments are only such values plus `x-match` of `all` or `any`, otherwise it is a read-only step (A2).
- The routing key field is not asked when the source exchange is known to be fanout or headers (from the plan or `KnownTopology`); when the type is not known the field is shown.
- `PlanValidation`: a binding is its exchange, destination, routing key and arguments, so two headers bindings with other arguments are not "declared twice".
- A fixed warning for a headers binding without a header, one text per rule (measured, S-E4: `all` with only `x-match` matches every message, also one with no headers; `any` with only `x-match` matches no message). The texts are owner wording: Owner review list of `M9-00a`, items 6 and 7, so the scenario carries `@needs-decision` until they are settled. The form always writes `x-match` (a binding with none is accepted by the broker, but how it routes was not measured).
- `ui`: the headers rows of `BindingFormView`.

- Match rules offered: `all` (the start value) and `any`; the broker also accepts `all-with-x` and `any-with-x` (201), but their routing was not measured, so a file that holds them opens as a read-only step or as a binding the form keeps unchanged, never edited into another rule. Any other value is refused by the broker with 400 and nothing is created (case sensitive: `ALL` is refused), so the form never writes one.
- Value types: text, whole number, boolean. Measured: the types come back as JSON types, `10` and `"10"` are different values and match differently (a number header does not match a text argument and the other way round). NOT measured: whether a boolean or a decimal MATCHES a published header of the same value, and large numbers; a decimal is not offered, and the live gate adds one probe for a boolean (see Open points).

Out: `all-with-x` and `any-with-x` as choices (see above); deleting a headers binding (the format's `deleteBinding` has no arguments today; a later format addition).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A binding on a headers exchange carries its headers and the match rule" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "Header values keep their type in the saved file" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Only the two measured match rules are offered, and the binding always carries one" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A binding from an opened file that uses a rule the form does not offer is kept as it is" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A number and the same text are different header values" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Two bindings that differ only in their headers are two steps" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A headers binding without a header says what it would match" (outline, two rows; @fake-broker @negative @needs-decision: wording for the owner)
- BDD: `21_topology_planner_recipes.feature` / "A fanout binding has no routing key field" (@fake-broker)
- Unit tests, written first: `BindingFormHeadersTest` (arguments and types written, `formOf` round trip, read-only for anything else), `PlanValidationBindingIdentityTest`. Compose: `BindingFormHeadersUiTest`.
- Live: nothing new; `RealBrokerM9SpikeHeadersBindingTest` of the spike stays the proof, and the executor's binding request with arguments is already proven by `RealBrokerM8MigrationRequestsTest`.

## Design notes
No format change: `declareBinding` has typed `arguments` since M8-04, and the executor matches a binding by key and arguments. No broker in this task.

## UX
`docs/ux/Planner.dc.html`: "4." form "Binding on a headers exchange".

## MCP
None (owner decision 2026-11-15).

## Open points
- Q1 (decided 2026-11-15): topic and headers exchanges are offered, so this task stays.
- S-E is measured (M9-00a): see "Measured facts of M9-00a" in the spec. What this task may rely on: the four accepted `x-match` values and the 400 for anything else; the JSON types read back; number against text; `all` needs every header, `any` one; `all` with only `x-match` matches everything, `any` with only `x-match` matches nothing; two bindings differing only in arguments are two entries (different `properties_key`) and a repeated POST answers 201 and adds nothing; the routing key of a fanout or headers binding is ignored for routing, so the field is hidden and the form writes an empty key. What it must NOT claim: the meaning of `-with-x`, routing of booleans and decimals, the routing of a binding with no `x-match`, that a non-administrator sees the same binding list.
- The two warnings are owner wording (review list items 6, 7) and the offered rules item 10; build the form with the proposed texts behind constants and keep the scenario out of the suite until the owner answers.
- Small live addition (the gate task `M9-13` or a test of this task, the test developer's choice): one headers binding with a boolean argument and a publish with the boolean header, with a positive control, so that the boolean type is measured before it is offered as routing.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
