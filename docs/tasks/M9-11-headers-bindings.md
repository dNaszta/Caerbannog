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
- Fixed text for a headers binding without a header, worded from S-E4.
- `ui`: the headers rows of `BindingFormView`.

Out: `all-with-x` and `any-with-x` (shown read-only when a file has them; offered later if S-E2 measures them and the owner wants them); deleting a headers binding (the format's `deleteBinding` has no arguments today; a later format addition).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A binding on a headers exchange carries its headers and the match rule" (@fake-broker @smoke @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Header values keep their type in the saved file" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Two bindings that differ only in their headers are two steps" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A headers binding without a header says what it would match" (@fake-broker @negative @needs-spike)
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
- S-E decides the accepted match rules, the value types and the text for a binding without a header.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
