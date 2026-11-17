# M9-11: Bindings on a headers exchange: headers and the match rule in the binding form

Milestone: M9    Depends on: M9-00a (S-E), M9-03    Size: S

PLANNED 2026-11-15. Status: step 3 built (senior developer, 2026-11-17), waiting for step 4 (verify) and review. Decided 2026-11-15 (Q1): headers and topic exchanges are in the first version.

## Goal
When the source of a binding is a headers exchange, the binding form asks for headers (name, value, value type) and the match rule (all or any) instead of a routing key, and writes them as binding arguments. Two bindings that differ only in their headers are two steps.

## Why
Owner decision O8 read with "like header, etc." (Q1). Spec "Topology planner (M9)", "Step kinds" (Binding). A headers exchange without binding arguments cannot be used, so the type is not offered without this form.

## Scope
In:
- `core.planner`: `BindingForm` gains `headers: List<HeaderMatch>` (name, `ArgumentValue` of kind text, whole number or boolean) and `matchRule` (all, any); `actions` writes them as `DeclareBinding.arguments` with `x-match`; `formOf` opens a binding in this form when its arguments are only such values plus `x-match` of `all` or `any`, otherwise it is a read-only step (A2).
- The routing key field is not asked when the source exchange is known to be fanout or headers (from the plan or `KnownTopology`); when the type is not known the field is shown.
- `PlanValidation`: a binding is its exchange, destination, routing key and arguments, so two headers bindings with other arguments are not "declared twice".
- A fixed warning for a headers binding without a header, one text per rule (measured, S-E4: `all` with only `x-match` matches every message, also one with no headers; `any` with only `x-match` matches no message). The texts are owner wording: Owner review list of `M9-00a`, items 6 and 7, DECIDED (owner, 2026-11-16: "do arch suggest"): `With no header, this binding matches every message` (all) and `With no header, this binding matches no message at all` (any); the scenario lost `@needs-decision`. The form always writes `x-match` (a binding with none is accepted by the broker, but how it routes was not measured).
- `ui`: the headers rows of `BindingFormView`.

- Match rules offered: `all` (the start value) and `any`; the broker also accepts `all-with-x` and `any-with-x` (201), but their routing was not measured, so a file that holds them opens as a read-only step or as a binding the form keeps unchanged, never edited into another rule. Any other value is refused by the broker with 400 and nothing is created (case sensitive: `ALL` is refused), so the form never writes one.
- Value types: text, whole number, boolean. Measured: the types come back as JSON types, `10` and `"10"` are different values and match differently (a number header does not match a text argument and the other way round). NOT measured: whether a boolean or a decimal MATCHES a published header of the same value, and large numbers; a decimal is not offered, and the live gate adds one probe for a boolean (see Open points).

Out: `all-with-x` and `any-with-x` as choices (see above); deleting a headers binding (the format's `deleteBinding` has no arguments today; a later format addition).

## Acceptance
All in `tests/bdd/features/21_topology_planner_recipes.feature`, section "M9-11: bindings on a headers exchange", tag `@m9-11`, all `@fake-broker`:
- "A binding on a headers exchange carries its headers and the match rule" (@smoke)
- "Header values keep their type in the saved file"
- "Two bindings that differ only in their headers are two steps"
- "Only the two measured match rules are offered, and the binding always carries one"
- "A binding from an opened file that uses a rule the form does not offer is kept as it is"
- "A headers binding without a header says what it would match" (outline, rows `all` and `any`; @negative)
- "A number and the same text are different header values"
- "A fanout binding has no routing key field"
- ADDED at step 1: "A header row that cannot be written is refused" (outline, 3 rows; @negative), "A header listed twice is refused" (@negative), "A binding from an opened file keeps its headers and opens in the headers form".
- The step glue of the outline and the new scenarios is new: the test developer writes the steps in `acceptance-tests/.../planner/` (`PlannerSteps.kt` and `PlannerDriver.kt` already drive `BindingForm`).
- Unit tests, written first: `BindingFormHeadersTest` (core; arguments and types written, `formOf` round trip, read-only for anything else, hints, problems), `PlanValidationBindingIdentityTest` (core), `BindingFormHeadersUiTest` (ui, Compose).
- Live: nothing new for the form. `RealBrokerM9SpikeHeadersBindingTest` stays the proof; the executor's binding request with arguments is proven by `RealBrokerM8MigrationRequestsTest`. One small live probe (a boolean argument and a boolean header, with a positive control) is wanted so that the boolean type is measured before it is offered as routing: the test developer may put it in this task or in `M9-13`; if it shows that a boolean does not match, the boolean choice is removed from the form (see Open points).

## Design notes
Confirmed at step 1 (2026-11-17) against main c34b19f2: `MigrationAction.DeclareBinding` already has typed `arguments: Map<String, ArgumentValue>` (M8-04), its `detail` is the arguments sorted by key as `key=value` (so `format=pdf, type=report, x-match=all`, the text the first scenario expects, needs no change), `FormContext.asksRoutingKey(exchange)` already hides the routing key for a known fanout or headers exchange (plan first, then `KnownTopology`), and `PlannerViewModel.detailsOf` already hands it to the screen. No format change, no broker, no new dependency, `core` stays pure.

Correction to the first draft of this brief: `PlanValidation.duplicates` handles only queues, exchanges and streams. Bindings are not checked for "declared twice" at all today, so there is no binding identity to widen. `PlanValidationBindingIdentityTest` therefore pins: two bindings with the same exchange, destination and key but different arguments give NO `PlanProblem`. It does not ask for a new "declared twice" note on identical bindings (not in scope; if wanted it is a separate idea).

API to pin (core.planner, `StepForms.kt`; names are the architect's, the test developer pins them):
- `enum class MatchRule(val wire: String) { ALL("all"), ANY("any") }`; start value `ALL`.
- `enum class HeaderValueType { TEXT, WHOLE, FLAG }`.
- `data class HeaderMatch(val name: String, val type: HeaderValueType = TEXT, val value: String = "")`. The value is raw text because a whole-number field is typed key by key (`1`, then `12`); for `FLAG` it is `"true"` or `"false"`. `fun argumentValue(): ArgumentValue?` gives `Text`, `Whole` (null when `value` is not a whole number that fits a `Long`, no sign other than a leading `-`, no spaces) or `Flag`. Deviation from the first draft ("`HeaderMatch` holds an `ArgumentValue`"): the draft cannot hold half-typed input.
- `BindingForm` gains `headers: List<HeaderMatch> = emptyList()`, `matchRule: MatchRule = MatchRule.ALL` and `heldRule: String? = null`. `heldRule` is the verbatim `x-match` of an opened file when it is not `all` or `any` (`all-with-x`, `any-with-x`, or anything else the file holds); the form writes `heldRule` when set and `matchRule.wire` otherwise. Choosing `all` or `any` in the form clears `heldRule`: an explicit choice is an edit, the only way a kept rule changes.
- Arguments written (`actions`): when `headers` is not empty, or the source exchange is a known headers exchange (plan or `KnownTopology`): `x-match` plus one entry per header, typed `Text`, `Whole` or `Flag`; otherwise no arguments, byte for byte as before. An empty `headers` list on a known headers exchange writes `{x-match: all}` and the form's hint is the fixed warning. The routing key is written as held (the form never adds one: the field is not asked for fanout and headers); a held key of an opened file is kept, not dropped.
- `formOf` for `DeclareBinding` (after the MQTT route check, unchanged): a binding whose arguments are `Text`, `Whole` or `Flag` values plus an `x-match` of kind `Text` opens as `BindingForm`: `all` or `any` as `matchRule`, any other text as `heldRule` (so `all-with-x` opens in the form and is written back unchanged: scenario "kept as it is"). Read-only step (`null`, A2): any other argument kind (decimal, list, table, null), a non-text `x-match`, arguments that are ONLY `x-match` (no header; decided at step 2), and arguments WITHOUT `x-match` (how such a binding routes was not measured, so the form must not write a rule the file did not have). A binding with no arguments is the plain form, as today.
- Problems (blocking, field `headers`, one per mistake, first wins in the page message): empty name `Give the header a name`; name `x-match` `x-match is the match rule, not a header`; a name used twice `A header can be listed once`; `WHOLE` with a value that is not a whole number `A whole number has digits only`. These four texts are working wording of the architect (technical refusals in the style of the decided name refusals), constants in `PlannerTexts`; the owner may reword them (see Open points). Spaces in a name or a text value are allowed and kept; nothing is trimmed (a header `" format"` is a different header on the broker).
- Hints (never blocking): the two fixed warnings, only when the source exchange is a known headers exchange and `headers` is empty, `PlannerTexts.HEADERS_NONE_ALL` and `HEADERS_NONE_ANY` (decided texts above), as a `FieldHint("headers", ...)`.
- When the exchange type is not known, the routing key field is shown, the headers section is not offered; a form that already holds headers (opened file) shows the section so they can be edited or removed, and writes them.
- Exchange type of the plan: the LAST declaration of that name wins (existing `asksRoutingKey`); add a small `FormContext.exchangeType(name): String?` that `asksRoutingKey` also uses, so there is one place that reads it.
- `WizardState.Details` gains what the screen needs: `asksHeaders: Boolean` (true for a known headers exchange or a form that holds headers). Keep decisions in core; the view only draws.
- Plan graph, check and dry run are unchanged: the check's binding row already compares key and arguments (M9-05/M8); the graph edge label stays the routing key. State in the PR if a headers binding looks odd on the graph; do not change the graph here.

## UX
`docs/ux/Planner.dc.html`, "form-binding-headers" (the mockup title still says "S-E pending": S-E is measured; fix the title when the screen is built). States: the source exchange is a known headers exchange (match rule choice `all headers match` / `any header matches`, header rows of name, value, a type chip `text`, `number`, `true or false`, a remove button, `Add header`; no routing key), unknown type (routing key field, no headers section), a form that already holds headers (section shown), problem under the header rows (one `planForm:error:headers`; `FieldProblem` has no row; decided at step 2), hint (the two fixed warnings) when there is no row. The mockup's chip says `number`; the model name is `WHOLE` (whole numbers only, a decimal is not offered). A boolean value is a two-way choice, not free text. Stable `testTag`s to add: `planForm:matchRule:all`, `planForm:matchRule:any`, `planForm:header:<n>:name`, `:value`, `:type`, `:remove`, `planForm:addHeader`. Look and feel stay Material 3 like the other forms; no new colours.

## MCP
None (owner decision 2026-11-15). Re-evaluated at step 1: the planner has no MCP surface at all (it builds a draft that the user saves as a migration on the Migrations screen), and a headers binding is only a binding argument in the same migration format, which `M5-09` read-only tools already list and dry-run. No new tool, no write tool, nothing to expose safely.

Safety (CLAUDE.md): the form only writes steps into a draft; the broker names it suggests come from the read-only `KnownTopology`; no new request, no queue read, no TLS path, no dependency.

## Open points
- Q1 (decided 2026-11-15): topic and headers exchanges are offered, so this task stays.
- S-E is measured (M9-00a): see "Measured facts of M9-00a" in the spec. What this task may rely on: the four accepted `x-match` values and the 400 for anything else; the JSON types read back; number against text; `all` needs every header, `any` one; `all` with only `x-match` matches everything, `any` with only `x-match` matches nothing; two bindings differing only in arguments are two entries (different `properties_key`) and a repeated POST answers 201 and adds nothing; the routing key of a fanout or headers binding is ignored for routing, so the field is hidden and the form writes an empty key. What it must NOT claim: the meaning of `-with-x`, routing of booleans and decimals, the routing of a binding with no `x-match`, that a non-administrator sees the same binding list.
- The two warnings and the offered rules are DECIDED (owner, 2026-11-16, review list items 6, 7, 10 of `M9-00a`: the texts above; offer only `all` and `any`); build the form with these texts behind constants.
- Small live addition (the gate task `M9-13` or a test of this task, the test developer's choice): one headers binding with a boolean argument and a publish with the boolean header, with a positive control, so that the boolean type is measured before it is offered as routing.

- DECIDED at step 1 (architect, technical, no new owner product decision): a file binding with arguments but no `x-match` is read-only; the four header refusals are working wording (listed below); a boolean is offered only if the live probe shows it routes (otherwise remove `FLAG` from the form and keep reading it from files).
- OWNER GLANCE (not blocking, ships as written unless reworded; recommendation yes): the four refusal texts `Give the header a name`, `x-match is the match rule, not a header`, `A header can be listed once`, `A whole number has digits only`, and the type chip labels `text`, `number`, `true or false`.
- Not measured, so not claimed: that a header whose name starts with `x-` is ignored by `all` and `any` matching (RabbitMQ documents this; the form does not rely on it and does not forbid such names except `x-match`).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Step 1 record
Architect, 2026-11-17, branch `m9-11-headers-bindings` (worktree `../Caerbannog-m9-11`), main c34b19f2: brief confirmed against `StepForms.kt`, `FormContext.kt`, `PlanValidation.kt`, `PlannerViewModel.detailsOf`, `PlanForms.kt`, `MigrationModels.kt` and the spec (S-E, O15, O8/Q1). Changed: the PlanValidation bullet (bindings are not checked for duplicates today), the model of a header value (raw text plus type, not `ArgumentValue`), the handling of a kept `all-with-x` rule, the no-`x-match` case; added three scenarios (refused rows, duplicate header, opened file keeps headers). Next: step 2, test developer writes the tests first (unit, UI, glue).

## Step 2 record
Test developer, 2026-11-17, branch `m9-11-headers-bindings`. Status: tests written, RED; no production code touched.
- Core (`core/src/jvmTest/.../planner/`): `BindingFormHeadersTest` (35: model, typed `argumentValue`, arguments written, key kept, spaces kept, detail text, file round trip with types, held rule, the four refusals and the two warnings word for word, `formOf` table incl. read-only cases), `FormContextExchangeTypeTest` (5), `PlanValidationBindingIdentityTest` (4), `PlannerViewModelHeadersTest` (10: `asksHeaders`, `asksRoutingKey`, page message, no step added while refused, edit of an opened step).
- UI: `BindingFormHeadersUiTest` (16, Compose, 1280 x 800 and one 700 x 700).
- Glue: `PlannerHeadersSteps` (new), `PlannerSteps.formSays` (a warning is a hint, a header refusal is also read from the screen), `PendingStandIn.binding` (the "saving writes the binding back unchanged" step now also sends an argument binding through its form first), runner filter `+ @m9-11`. All ten `@m9-11` scenarios are bound; no feature line was edited.
- Live: `RealBrokerM9HeadersBindingTest` (5, RabbitMQ 4.4.0, passes today because it needs no new production code): a boolean header matches a boolean argument and only that (probe, with a number positive control), so `FLAG` STAYS in the form; arguments the form writes are read back typed with an empty routing key; routing for `all` and `any`; two bindings differing only in headers route separately.
- Proof the tests are consistent: a throwaway reference implementation (outside the repo) made core (all planner tests), ui (16/16) and the planner acceptance suite (321 run, 0 failed) green.

Names pinned by the tests beyond the brief (architect to confirm or rename): `BindingForm.withRule(MatchRule)`; `PlannerTexts.HEADERS_NONE_ALL`, `HEADERS_NONE_ANY`, `HEADER_NAME_MISSING`, `HEADER_NAME_IS_MATCH_RULE`, `HEADER_LISTED_TWICE`, `HEADER_WHOLE_DIGITS_ONLY`; every headers problem is `FieldProblem("headers", ...)` (one per mistake, a name used twice is one problem), the hint `FieldHint("headers", ...)`; screen tags `planForm:header:<n>:type:text|number|flag` (radios, labels `text`, `number`, `true or false`), `planForm:header:<n>:value:true|false` (radios, no `:value` text field on a boolean row), `planForm:error:headers` and `planForm:hint:headers` (one each, under the rows; FieldProblem has no row number, so "under a row" is "under the rows"); an empty WHOLE value is refused, an empty TEXT value is legal; the headers of an opened file keep the file's order.

Brief gap found (decision needed): a file binding with ONLY `x-match` (no header) opens in the form with no header. On an exchange of unknown type the rule "write arguments when headers is not empty or the exchange is a known headers exchange" drops the rule on a plain Edit and Add to plan (data loss in an opened file). `an opened binding with x-match only is not changed by writing it back on an unknown exchange` accepts either fix (the step opens read-only, or the form remembers the rule).

## Step 2: contract confirmed
Architect, 2026-11-17, branch `m9-11-headers-bindings`, head 2b3494f0. Read: this brief, `BindingFormHeadersTest`, `StepForms.formOf`, the `@m9-11` scenarios. No product decision was needed.

1. Names pinned by the test developer: all CONFIRMED as they are, no rename.
   - `BindingForm.withRule(MatchRule)` (an explicit choice of `all` or `any`; clears `heldRule`).
   - `PlannerTexts.HEADERS_NONE_ALL`, `HEADERS_NONE_ANY`, `HEADER_NAME_MISSING`, `HEADER_NAME_IS_MATCH_RULE`, `HEADER_LISTED_TWICE`, `HEADER_WHOLE_DIGITS_ONLY` (texts as in "Design notes").
   - `FieldProblem("headers", ...)` and `FieldHint("headers", ...)`.
   - Tags: `planForm:header:<n>:type:text|number|flag`, `planForm:header:<n>:value:true|false` (radios; no `:value` text field on a boolean row), `planForm:error:headers`, `planForm:hint:headers`. These add to the tags listed under "UX"; the `:type` tag of the UX list is the per-option form above, and `planForm:header:<n>:name`, `:value` (text and number rows), `:remove`, `planForm:addHeader`, `planForm:matchRule:all|any` stay as listed.
   - Problem shown under the header rows, not under one row: KEEP (simpler; `FieldProblem` carries a field, not a row). The UX line is changed to say so.
   - Also confirmed: an empty WHOLE value is refused, an empty TEXT value is legal; the headers of an opened file keep the file's order; `FLAG` stays in the form (the live probe `RealBrokerM9HeadersBindingTest` shows a boolean header matches a boolean argument).
2. Gap (a file binding with ONLY `x-match` on an exchange of unknown type): DECIDED, read-only opening. `StepForms.formOf` returns `null` for a `DeclareBinding` whose arguments are exactly `{x-match: <any text>}` (`all`, `any`, `all-with-x`, anything else). The step is shown read-only and the file keeps it as written, so nothing changes silently and no extra state (`hadRule` flag) is added to the form. Reason: `formOf` has no exchange context, a remembered flag would be a boolean that switches how `actions` writes, and a headers binding with no header is a corner case (it matches everything or nothing; the user who wants another one removes the step and adds it through the form, which shows the two warnings). The write rule stays as written: arguments are written when `headers` is not empty or the source is a known headers exchange. Consequence for the senior developer: a form opened by `formOf` therefore always holds at least one header when it has arguments, so "x-match written on an unknown exchange" can only happen with headers. The test `an opened binding with x-match only is not changed by writing it back on an unknown exchange` accepts this (a null form passes); a stricter assertion (`assertNull`) may be added by the test developer, optional.
3. Contradictions with the spec or CLAUDE.md: none found. No broker path, no queue read, no TLS path, no secret, no new dependency in the tests; the live probe publishes to a test broker only and is `@broker`. MCP stays "None".

Go for step 3 (senior developer): the contract is closed; implement against the tests, make the throwaway-reference-proven names real, and run core, ui and the planner acceptance suite.

## Step 3 record
Senior developer, 2026-11-17, branch `m9-11-headers-bindings`. Built against the step 2 tests: `StepForms.kt` (`MatchRule`, `HeaderValueType`, `HeaderMatch` with `argumentValue()` and `withType()`, `BindingForm.headers/matchRule/heldRule/withRule`, `formOf` opens headers bindings, `x-match` alone is read-only), `FormContext.exchangeType`, `WizardState.Details.asksHeaders`, `PlannerTexts` (two warnings, four refusals), `PlanForms.kt` (`HeadersSection`, `HeaderRow`). Graph, check and dry run untouched. Not done by me (not mine to edit): the mockup title in `docs/ux/Planner.dc.html` still says "S-E pending".
