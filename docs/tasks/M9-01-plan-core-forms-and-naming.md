# M9-01: Plan core: the plan model, the queue, exchange and binding forms, plain words, validation and naming

Milestone: M9    Depends on: none (the owner answered the open questions on 2026-11-15); no spike    Size: M

PLANNED 2026-11-15. Not started. First task of the earliest working slice (`M9-01`, `M9-02`, `M9-03`).

## Goal
`core` can hold a plan, build its steps from three forms (queue, exchange, binding), say every step in plain words, find the problems of a plan, and suggest and judge names by a naming style. All of it is pure and unit tested; nothing is stored, drawn or sent.

## Why
Spec "Topology planner (M9)": A1 (a plan is the content of a migration), A2 (nothing is lost), "Step kinds of the first version", "Naming" (owner decisions O3, O10, O11). The owner's need: build a topology change without writing JSON.

## Scope
In (module `core`, package `dev.caerbannog.core.planner`, `commonMain`, no `java.*`, no RabbitMQ import):

```kotlin
/** A plan is the content of a migration (spec A1): a description, one virtual host, and steps that are each exactly one action. */
data class TopologyPlan(val description: String = "", val vhost: String = "/", val steps: List<PlanStep> = emptyList())

/** [id] is a session-only key for the list (never written to a file); [action] is the existing migration action, unchanged. */
data class PlanStep(val id: Int, val action: MigrationAction)
```

- Plan operations, each returning a new plan: append a list of actions (ids are assigned), replace the action of a step, remove a step, move a step up or down, set the description, set the virtual host (every step moves to it).
- Forms, one sealed type per kind, with defaults: `QueueForm(name, type = quorum)`, `ExchangeForm(name, type = direct)`, `BindingForm(exchange, destination, destinationKind = queue, routingKey)`. Each form answers `problems(context): List<FieldProblem>` (field and fixed message) and `actions(context): List<MigrationAction>`; `StepForms.formOf(action): StepForm?` is the inverse and answers null for an action a form cannot hold (arguments, flags that are not the default, every delete, every access action), which the screen then shows as a read-only step (A2).
- `FormContext`: the plan, its virtual host, the naming style, and `KnownTopology` (names and types of the exchanges, and names of the queues and streams, that the broker has; `KnownTopology.None` when not connected). It feeds the suggestions of the binding form ("in this plan" first, then "on the broker") and decides whether the routing key is asked (not for a fanout or headers exchange whose type is known).
- Plain words: the title of a step is `MigrationAction.description` and its second line `MigrationAction.detail`, so the planner, the dry run and the migrations list say the same thing. No second wording table for these three kinds.
- `PlanValidation.problems(plan): List<PlanProblem>` (step, fixed message, blocks saving or not): the same item declared twice with other settings (blocks; the same settings twice is a note); a queue and a stream of one name (blocks); a binding placed before a step of the same plan that declares its exchange or destination (blocks, names the later step); and the parser round trip: `MigrationParser.parse(MigrationParser.exportToJson("draft", description, actions))` must be `Valid`, otherwise the parser's fixed reason is the problem (blocks).
- Naming (owner decisions O10, O11), file `PlanNaming.kt`:

```kotlin
enum class NamingStyle(val label: String) { KEBAB("kebab-case"), SNAKE("snake_case"), DOT("dot.separated"), CAMEL("camelCase"), NONE("No convention") }
enum class DerivedWord(val word: String) { DLN("dln"), DLQ("dlq"), DLX("dlx"), RETENTION("retention") }

object PlanNaming {
    fun derived(base: String, word: DerivedWord, style: NamingStyle): String   // orders-dln, orders_dln, orders.dln, ordersDln; NONE as KEBAB
    fun follows(name: String, style: NamingStyle): Boolean                     // NONE: always true
    fun example(name: String, style: NamingStyle): String?                     // "Orders_EU" -> "orders-eu"; null when none can be built
    fun hint(name: String, style: NamingStyle): String?                        // "This name does not follow kebab-case, for example orders-eu"
    fun baseOf(name: String, word: DerivedWord): String?                       // "orders_dln" -> "orders" in any style; null when not derived
}
```

  Rules, exact: kebab-case is lower-case letters and digits in words joined by `-`; snake_case the same joined by `_`; dot.separated joined by `.`; camelCase starts with a lower-case letter and has no separator. `example` splits a name into words at `-`, `_`, `.`, a space and before an upper-case letter that follows a lower-case letter or a digit, lower-cases them and joins them in the style; a name without a letter or digit gives null and the hint then has no example. `derived` keeps the base as typed and adds the separator and the word (camelCase: the word with a capital first letter). `hint` is null for an empty name, for `NONE` and for a name that follows. The hint is advice: no function here blocks anything (O11).
- What blocks in a form: an empty name, `.` or `..` (the parser's own text), and what the parser round trip refuses. Nothing about the broker's name rules is assumed (S-H is not measured yet).
- Fixed texts in `PlannerTexts` (one file, plain text, no markup): the form messages of the scenarios below.

Out: the view model, storage and settings (`M9-02`); every screen (`M9-03`); the stream, policy, dead-lettering and MQTT route forms (`M9-07` to `M9-10`); headers arguments (`M9-11`); the check (`M9-05`); the graph (`M9-04`).

## Acceptance
- BDD: `19_topology_planner.feature` / "Add a queue, an exchange and a binding with the wizard" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "The wizard ends with a summary before anything is added" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Cancelling the wizard adds nothing" (@fake-broker)
- BDD: `19_topology_planner.feature` / "The wizard offers exactly these kinds" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A fanout or a direct exchange" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A topic or a headers exchange" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A new queue is a quorum queue unless I choose otherwise" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A form that cannot be added says why" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "The same thing declared twice with other settings is a problem in the plan" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A binding placed before the thing it needs is a problem in the plan" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "A wizard binding to something the plan declares is placed after it" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Steps can be edited, moved and removed" (@fake-broker)
- BDD: `19_topology_planner.feature` / "The destination of a binding can be picked from the plan and from the broker" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A plan is for one virtual host" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A name that does not follow the naming style gets a hint and can still be added" (@fake-broker)
- BDD: `19_topology_planner.feature` / "The hint follows the chosen naming style" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Names that already exist on the broker are never flagged" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Changing the naming style renames nothing" (@fake-broker)
- New suite `RunTopologyPlannerAcceptanceTest` (package `dev.caerbannog.acceptance.planner`, features `19`, `20`, `21`, filter `@m9-01 and @fake-broker and not @needs-decision and not @needs-spike`, widened by each later task). The steps talk to a small `PlannerDriver` in the glue that, in this task, calls the forms and the plan functions directly; `M9-02` re-points the driver at the view model without editing a feature line.
- Unit tests, written first: `TopologyPlanTest` (operations, ids, virtual host move), `QueueFormTest`, `ExchangeFormTest`, `BindingFormTest` (problems as a table; `formOf` round trip for every action a form builds; null for arguments, flags, deletes and the four access actions), `PlanValidationTest` (one test per rule, and the parser's reason passed through unchanged), `PlanNamingTest` (one table per style for `follows`, `example`, `derived`, `baseOf`; hostile names: empty, 1,000 characters, control characters, only separators), `PlannerTextsGuardTest` (no text holds markup or a placeholder left unfilled).
- A test pins that the title of every step equals `MigrationAction.description`.

## Design notes
Everything is immutable data and pure functions; no coroutine, no clock. Reuse `MigrationAction`, `MigrationParser`, `echoableName` (a name from a plan goes into a message only through the echo rule, as everywhere in `core.migrations`). Use plain character loops for the naming rules, not `Regex`, and cap the name length looked at (a name is untrusted text once a file is opened in `M9-12`). `KnownTopology` is a plain value built by the caller; `core.planner` does not call `TopologyCatalog` in this task. No broker is involved: there is nothing to fake.

## UX
None in this task. The forms and texts are the ones of `docs/ux/Planner.dc.html`, sections "4. Wizard, page 2 of 3" (Queue, Exchange, Binding) and "6. Naming style".

## MCP
None. Owner decision 2026-11-15 ("no MCP"), recorded in `docs/spec.md` "Topology planner (M9)", "MCP evaluation": the planner is a form for a person, and a saved plan is an ordinary migration that the existing read-only tools already list and dry-run.

## Open points
- Q1 (decided 2026-11-15): the Exchange form offers fanout, direct, topic and headers; the binding form of a headers exchange is `M9-11`.
- Q5, Q6, Q7 (decided 2026-11-15): the default style is kebab-case, the suffix words are fixed, the hint covers every new name of a queue, exchange, stream or policy and never an existing broker name, a routing key, a topic filter or a header name.
- Q13 (decided 2026-11-15): the queue form is name and type only.
- S-H (broker name rules) is not measured: the form blocks only on the app's own rules.

## API shape confirmed (architect, 2026-11-16, step 2.5)
The tests fix this shape in `dev.caerbannog.core.planner`, beyond the brief. All of it is confirmed; the senior developer follows it as the tests read.
- `TopologyPlan`: `append(List<MigrationAction>)`, `replace(id, action)`, `remove(id)`, `moveUp(id)`, `moveDown(id)`, `withDescription`, `withVhost`, each returning a new plan; an unknown id, the first step up and the last step down change nothing; ids never repeat a live one; `withVhost` sets the vhost of every action. `PlanStep.title` is `action.description` and `PlanStep.detail` is `action.detail` (computed properties).
- `StepForm` is a sealed interface with `problems(ctx): List<FieldProblem>`, `actions(ctx): List<MigrationAction>` (empty while there is a problem) and `hints(ctx): List<FieldHint>`. `FieldProblem` and `FieldHint` are `(field, message)`, field is `name`, `exchange` or `destination`. Forms: `QueueForm(name, type = "quorum")`, `ExchangeForm(name, type = "direct")`, `BindingForm(exchange, destination, destinationKind = QUEUE, routingKey = "")`.
- `FormContext(plan, namingStyle, known = KnownTopology.None)` with `destinationSuggestions(kind)`, `exchangeSuggestions()` and `asksRoutingKey(exchange)` (a plan exchange's type counts as known; an unknown type asks). `Suggestion(name, source)` with `SuggestionSource.PLAN | BROKER`, plan first, no repeated name. `KnownTopology(exchanges: Map<name, type>, queues, streams)`.
- Hints: queue and exchange forms hint only for a non-empty name that is not already on the broker (the queue form checks queues and streams together, the exchange form the exchange names); the binding form never hints (Q7: no routing key, no hint).
- `PlanProblem(stepId, message, blocking)`; "step N" in a message is the 1-based place of the step and the texts are those of the scenarios (lower-case "step").
- `PlannerTexts` holds `WIZARD_KINDS` (seven names in the order of the mockup and spec), `summaryTitle(n)`, `saveBlocked(n)` and the fixed form and plan messages. One home for fixed texts, as the brief says; later tasks (M9-03 and after) add their texts there.
- Test developer's two fixes accepted: the reflowed description line of feature 19 (no wording change) and the regular-expression step for "the form says" (the parser's message holds quotes). No other line of features 19, 20 and 21 starts with an at sign outside a real tag line (checked by search).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-16, base main `869401f`, branch `m9-01-plan-core-forms-and-naming`, round 0): brief checked against the spec (A1, A2, O3, O10, O11, Q1, Q5 to Q7, Q13) and `19_topology_planner.feature`: the 18 `@m9-01` scenarios match the 18 names in Acceptance one to one, no spike needed, no dependency, no other non-done task must come first (the spikes and `M9-14` run beside it). No change to the brief or the scenarios was needed. Open product questions: none; S-H (broker name rules) stays unmeasured and the form blocks only on the app's own rules.
- Step 2 (test developer, 2026-11-16, branch `m9-01-plan-core-forms-and-naming`, round 0): tests first, red. All 18 `@m9-01` scenarios (29 runs with the outline rows) have steps in `acceptance-tests/.../planner/` (`PlannerSteps`, `PlannerDriver`, suite `RunTopologyPlannerAcceptanceTest`); unit tests `TopologyPlanTest`, `QueueFormTest`, `ExchangeFormTest`, `BindingFormTest`, `PlanValidationTest`, `PlanNamingTest`, `PlannerTextsGuardTest` in `core/src/jvmTest/.../planner`. Red because `core.planner` does not exist (compile errors on its types only). Checked against a throwaway implementation (not committed): all 29 scenario runs and 68 unit tests pass, so the steps and tests are consistent. Two fixes outside the brief: a description line of `19` began with `@` and broke Gherkin parsing (reflowed, no scenario changed), and the step "the form says" is a regular expression because the parser's message holds quotes. API shape the tests fix beyond the brief is listed in the test developer's report. Next: step 3 (senior developer).
- Step 2.5 (architect, 2026-11-16, branch `m9-01-plan-core-forms-and-naming`, round 0): API shape beyond the brief reviewed against the spec and brief; all seven points confirmed, see "API shape confirmed". No test must change, no scenario changed. Next: step 3 (senior developer).
- Step 3 (senior developer, 2026-11-16, branch `m9-01-plan-core-forms-and-naming`, round 1): `core.planner` built in `commonMain` (`TopologyPlan`, `StepForms`, `FormContext`, `PlanValidation`, `PlanNaming`, `PlannerTexts`); no test or scenario edited, no dependency added. `:core:jvmTest` planner tests, the 29 `@m9-01` scenario runs and the whole `./gradlew test` pass; `brokerTest` not run (nothing here touches a broker). Next: step 4 (test developer verifies).
