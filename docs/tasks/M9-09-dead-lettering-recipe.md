# M9-09: Dead-lettering for a queue: one wizard kind that adds the exchange, the dead-letter queue, the binding and the policy

Milestone: M9    Depends on: M9-00b (S-C), M9-00c (S-F), M9-08 (merged at 86ad0540), M9-04    Size: M

PLANNED 2026-11-15. BRIEF READY 2026-11-17 (step 1 of the iterate loop). The spike M9-00b is measured and reviewed; its Owner review list is fully decided (spec O19, 2026-11-17: items 1, 2, 3, 8, 9, 10 and 12 accepted as recommended). Section "What the spike settled (M9-00b)" says what this task may rely on and must not. No scenario waits for the owner any more: the three `@needs-decision` tags of `21_topology_planner_recipes.feature` are gone and the wording below is decided.

## Goal
The wizard kind "Dead-lettering for a queue" asks for the source queue and adds four ordinary steps in a safe order: the dead-letter exchange, the dead-letter queue (or stream), the binding between them, and last a policy on exactly the source queue. The source queue is not declared or changed. The review graph shows the dead-letter path.

## Why
Owner decision O8 ("DLX"). Spec "Topology planner (M9)": A4 (by policy, with the reasons), "Dead-lettering for a queue", O19 (the decided texts).

## Scope
In:
- `core.planner`: `DeadLetterForm : StepForm` (data class), fields `queue: String`, `target: DeadLetterTarget = Queue` (enum `Queue`, `Stream`), `exchangeName: String? = null`, `targetName: String? = null`, `strategy: DeadLetterStrategy = AtMostOnce` (enum `AtMostOnce`, `AtLeastOnce`), `publishersRefusedAccepted: Boolean = false`. A null `exchangeName` or `targetName` means "follows the queue name" (same convention as `StreamForm.responseName`: any string, even empty, means the user typed it). Names are `PlanNaming.derived(queue, DerivedWord.DLX | DLQ, ctx.namingStyle)`. `actions(ctx)`, in this order, empty while there is a problem:
  1. `DeclareExchange(exchange, "fanout")`
  2. `DeclareQueue(target, "quorum")` or `DeclareStream(target)`
  3. `DeclareBinding(exchange, target)` (destination type queue; a stream is bound as a queue destination like every other binding of a stream in the planner; routing key as `BindingForm` gives for a fanout exchange)
  4. `DeclarePolicy(name = exchange, pattern = ExactPattern.of(listOf(queue)), applyTo = ..., priority = 0, definition = ...)`.
  `applyTo` by the source queue's type: `quorum_queues`, `classic_queues`, `queues` when the type is not known (use the `PolicyTarget` enum and its `wire`, do not retype the strings). `definition` is `dead-letter-exchange` = exchange; with `AtLeastOnce` it ALSO holds `dead-letter-strategy: at-least-once` and `overflow: reject-publish`, always both (build both in one private function; a form state with one and not the other cannot be built: S-C4). Switching back to `AtMostOnce` clears the tick (`publishersRefusedAccepted = false`) so a later switch asks again.
- The source queue's type, one place: add `FormContext.queueType(name): String?` next to `exchangeType` (the last `DeclareQueue` of the plan wins, then `known.queueDetails[name]?.type`; a `DeclareStream` of the plan gives `"stream"`; null when neither knows it). It decides `applyTo` and whether "At least once" is offered: offered only when the type is `quorum`. A classic queue and an unknown type do not offer it (decided: Owner review list item 9 of `M9-00c`). A source that is a stream (plan or broker) is a field problem `PlannerTexts.DEAD_LETTER_SOURCE_IS_STREAM` (S-D1, S-D2); suggestions list queues only.
- The source queue is picked from `FormContext` (this plan's queues first, then `known.queues`, no name twice; add `FormContext.queueSuggestions()` built like `destinationSuggestions` with streams left out), or typed. Typing a name nobody knows is allowed (type unknown, `queues`, no at-least-once).
- Placement: the four steps are appended after the existing ones, so a plan that declares the source queue gets them after it (scenario "comes after the queue"). Nothing is reordered.
- The texts (below, decided and proposed): the summary sentence, the default note, the at-least-once explanation and the tick note, the own-argument note.
- The own-argument note (S-C2): when the source queue on the broker declares its own `x-dead-letter-exchange`, the form shows the note. This needs data that `KnownQueue` does not hold yet: add `KnownQueue.ownDeadLetterExchange: String? = null` (default keeps every existing construction valid). `PlannerWiring.knownTopology` (desktopApp) fills it from `TopologyCatalog.queueDeclarations(vhost)` (already used by the export; it returns `QueueDeclaration.arguments`): the value of `x-dead-letter-exchange` when it is `ArgumentValue.Text`; null when the argument is absent, when `typedArgumentsComplete` is false (the argument may be the lost one: no claim) or when that read failed (the rest of `KnownTopology` is still returned; one failed extra read must not blank the names). Only the argument is read; `effective_policy_definition` is never read (S-C2). The note is pure (it reads `known`), so it is a `hints(ctx)` entry.
- The policy warning of `M9-08` in the form (scenario "A queue that already has a policy is pointed out before the recipe is added"). Do NOT write a second matcher: `BrokerPolicyNotes(reader, known).notesFor(policyAction)` (internal, `PolicyNotes.kt`) already compares the priority and returns the three decided sentences, skips a broker policy of the same name, caps the reads. It is a suspend call (one `GET /api/policies/{vhost}/{name}` per distinct policy, GET only, never on 3.x: `policyReader` is null there), so the form cannot compute it in `hints`. Design: the view model, when the queue of a `DeadLetterForm` in the wizard changes (and when the form is first shown with a queue), launches ONE read job and cancels the previous one, builds the policy action the form would add with priority 0 (a probe action from the form, the way `PolicyForm.nameProbe` does), calls `notesFor`, and puts the strings into the wizard state as `formNotes: List<String>`; the form view shows them under the queue field (tag `planWizard:deadLetter:notes`). When no reader is wired or the read fails, the list stays empty (no claim).
- `planPreview`: a `PlanLink(sourceId = queue node, targetId = exchange node, kind = PlanLinkKind.DeadLetter)` for each `DeclarePolicy` of the plan that holds `dead-letter-exchange` and whose pattern `ExactPattern.nameOf(pattern)` gives exactly one name; and for each `DeclareQueue` of the plan whose arguments carry `x-dead-letter-exchange` as text (a hand-written file). Derive it in a function `deadLetterLinks(plan)` next to `responseLinks(plan)`; a link is derived from names, never from the broker. The source queue becomes a drawn node even when the plan does not declare it (add the link's source to the queue names, as binding ends are), and is "already on the broker" when `known` has it (existing `markOf`). The edge label is the constant `PlannerTexts.DEAD_LETTER_EDGE_LABEL = "dead letters"`; the edge is dashed (UI).
- `ExactPattern.nameOf(pattern: String): String?` in `PatternMatches.kt`, the exact inverse of `ExactPattern.of` for ONE name: `^`, then characters where a backslash escapes the next character, then `$` and nothing after; null for every other text (several names `^(a|b)$`, an unescaped special character, no anchors, a trailing backslash). `ExactPatternTest` gets a round-trip row per special character in `SPECIAL` and the null rows.
- `ui`: the kind card (add `"Dead-lettering for a queue"` to `PlannerTexts.BUILT_KINDS`; it is already in `WIZARD_KINDS`), `DeadLetterFormView` (new file `DeadLetterFormView.kt` next to `PolicyFormView.kt`; labels from `PlannerTexts`, none written in the composable: the lesson of `M9-08f` item 3), the dashed edge in `PlanReviewGraph.kt`.

Out: a direct dead-letter exchange with a dead-letter routing key (later); dead-lettering by queue arguments in the wizard (A4); dead-lettering for a stream as source (S-D); delivery limit and TTL settings (policy settings of `M9-08`); any line that the death reason travels with the stream message (item 10: skipped until `M9-00b-c` is built, then a one-line follow-up); any further text about WHEN a held dead letter arrives (item 12: the one sentence of the at-least-once explanation is the only place); a change of the policy's priority (the recipe never changes it; the user edits the step with the Policy form).

## Reuse from M9-08 (do not duplicate)
| Need | Use | Where |
| --- | --- | --- |
| One-name pattern | `ExactPattern.of(listOf(queue))`, plus the new `nameOf` | `PatternMatches.kt` |
| Policy target wire texts | `PolicyTarget.QUORUM_QUEUES`, `CLASSIC_QUEUES`, `QUEUES` (`.wire`) | `PolicyKeyCatalog.kt` |
| Which keys suit which queue type | `PolicyBinding.binds(action, QueueKind)` (`dead-letter-strategy` is quorum-only in the catalog, so the rule that offers it and the rule that rejects it are the same) | `PolicyNotes.kt` |
| The three one-policy-per-queue sentences | `PlannerTexts.brokerPolicyTakenPlace / brokerPolicyWins / brokerPolicySamePriority` through `BrokerPolicyNotes.notesFor` | `PolicyNotes.kt`, `PlannerTexts.kt` |
| Missing-exchange and at-least-once-without-reject-publish notes in the plan | The value notes already fire on the recipe's own policy step; the recipe's steps must give NONE of them (the exchange is declared by the step before; both keys are set together). Assert it in `DeadLetterFormTest` through `PlanValidation.problems` | `PolicyNotes.kt` |
| Names of the broker, the type and the applied policy | `KnownTopology.queues`, `queueDetails` (`KnownQueue.type`, `.policy`) | `FormContext.kt` |
| Name checks and "already exists" hints | `FormChecks.nameProblem` and `FormChecks.nameHint` as `QueueForm` and `ExchangeForm` use them | `StepForms.kt` |
| Derived names, follow-until-edited | `PlanNaming.derived`, `PlanNaming.follows` | `PlanNaming.kt` |
| Link into the graph | `PlanLink`, `PlanLinkKind.DeadLetter` (the old brief wrote `DEAD_LETTER`; the enum is `DeadLetter`), `responseLinks` as the model | `PlanGraph.kt` |

Note the one name: the recipe's policy is named after the dead-letter exchange (`name = exchangeName`). `BrokerPolicyNotes` skips a broker policy of that same name (the step replaces it), which is right. Two recipes for two queues give two exchange names and so two policies; two recipes with the SAME exchange name would overwrite each other's policy on apply (known gap 10 of `M9-08`, stays with `M9-12` and `M9-13`): the form gives no check for it here.

## Texts (`PlannerTexts`)
Decided (spec O19, owner, 2026-11-17; word for word in the scenarios):
| Constant or function | Text |
| --- | --- |
| `DEAD_LETTER_DEFAULT_NOTE` (shown with "At most once") | `If the dead-letter target cannot take a message, RabbitMQ drops it.` |
| `DEAD_LETTER_AT_LEAST_ONCE_NOTE` | `RabbitMQ keeps a message it cannot pass on and retries. Retries can take minutes, so do not rely on a quick delivery.` (no number of minutes, ever) |
| `DEAD_LETTER_FULL_QUEUE_NOTE` (next to the tick) | `While the queue is full, publishers are refused. A quorum queue holds one message more than its limit.` |
| `deadLetterOwnArgument(queue, exchange)` | `The queue orders names its own dead-letter exchange legacy-dlx. RabbitMQ uses that one and ignores this policy's exchange.` Echo rule: a name that `echoableName` refuses is left out and the sentence stays whole: `The queue names its own dead-letter exchange. RabbitMQ uses that one and ignores this policy's exchange.` (the fallback shape is the architect's, a plain consequence of the rule) |
| `DEAD_LETTER_SUMMARY_CAUSES` (the wizard summary, item 9) | `Messages that are rejected, expire, are dropped by the queue's length limit or are returned too often are dead-lettered.` |
| tick label (in the mockup and scenarios) | `Publishers are refused when the queue is full` |
| `deadLetterQueueUntouched(queue)` (summary, in the scenario) | `The queue orders itself is not changed.` (name left out through the echo rule: `The queue itself is not changed.`) |

New sentences the architect proposes (labels and a safety net; no owner question, as with the three of `M9-08` ruling 1; the test developer pins them as written):
| Constant | Text | Why |
| --- | --- | --- |
| `DEAD_LETTER_KIND_DESCRIPTION` | `Exchange, dead-letter queue, binding and policy` | The mockup's kind card line |
| `DEAD_LETTER_TARGET_QUEUE` / `_STREAM` | `A dead-letter queue` / `A stream`, with the sub-lines of the mockup (`Quorum queue <name>` / `Stream <name>: dead letters are kept and can be browsed.`) | The mockup |
| `DEAD_LETTER_AT_MOST_ONCE` / `_AT_LEAST_ONCE` | `At most once` / `At least once`, the first with the pill `default` | The mockup and the scenarios |
| `DEAD_LETTER_TICK_REQUIRED` | `Tick "Publishers are refused when the queue is full" to use At least once` | The reason shown while Next is disabled (scenario "At least once needs my tick") |
| `DEAD_LETTER_SOURCE_IS_STREAM` | `Dead-lettering does not work for a stream. Choose a queue.` | S-D1 (refused) and S-D2 (never applies); reachable only by typing a stream's name |
| `DEAD_LETTER_QUEUE_MISSING` | `Choose or type the queue` | The empty field |
| `DEAD_LETTER_EDGE_LABEL` | `dead letters` | The graph edge |
| field labels | `Queue`, `Dead letters go to`, `Dead-letter exchange`, `Dead-letter queue` (`Dead-letter stream` when the target is a stream), `How sure` | The mockup |

Changed existing text: `BUILT_KINDS` gains the kind; the mockup `docs/ux/Planner.dc.html` form "4. Dead-lettering" was updated by the architect (title, the two notes, the tick line, the build note) and the developer matches it.
Texts that must NOT appear: a number of minutes, "guaranteed", "never lost", the raw `x-opt-deaths`, any sentence about the reason travelling with the message, any second statement about timing.

## Acceptance
- BDD (`tests/bdd/features/21_topology_planner_recipes.feature`; all `@fake-broker`; the tag `@m9-09` is the runner's filter):
  - "Dead-lettering for a queue adds four steps, the policy last" (@smoke)
  - "The queue itself is not declared or changed"
  - "The policy matches exactly the chosen queue" (outline: orders, orders.eu, jobs+retry)
  - "The dead-letter names are suggested with suffixes in the naming style" (outline; its first step is now `Given I chose the naming style "<style>" in the planner`, the step of `PlannerDraftSteps`, fixed in this step)
  - "Dead-lettering for a queue of the same plan comes after the queue"
  - "Dead letters can go to a stream"
  - "At least once needs my tick and says what it changes"
  - "At most once is the default and adds no strategy setting"
  - "At least once is not offered for a classic queue" (@negative)
  - "A queue that already has a policy is pointed out before the recipe is added"
  - "The review graph shows the dead-letter path"
  - "The four steps are ordinary steps"
  - "Switching back to At most once takes both at-least-once settings away again"
  - "At least once is not offered when the queue's type is not known" (@negative)
  - "The default says that a dead letter can be lost" (decided wording, tag removed 2026-11-17)
  - "At least once says what it needs, what it changes and that it can be slow" (decided wording, tag removed)
  - "A queue that declares its own dead-letter exchange is pointed out" (decided wording, tag removed)
- No new Gherkin is needed. The test developer adds glue only: "I choose ... in the dead-lettering form" (fills `DeadLetterForm`, reads `hints` and `formNotes`), "I add dead-lettering for the queue ... with the wizard", "the broker has the quorum queue ... declared with the argument ..." (fills `KnownQueue.ownDeadLetterExchange`), "the review graph shows a dashed ... edge" (reads `PlanGraph.links` and `edgeLabels`).
- Unit tests, written first: `DeadLetterFormTest` (the four actions and their order for each target and strategy; `applyTo` by queue type; names per naming style; the follow-until-edited rule; the tick gate as a `FieldProblem`; both at-least-once keys together or neither, over every reachable form state; the recipe's own steps give no `PolicyNotes` value note), `FormContextQueueTypeTest` (plan wins over broker, stream, unknown), `ExactPatternTest` additions (`nameOf` round trip for every special character, null for any other pattern), `PlanPreviewDeadLetterTest` (policy link, `x-dead-letter-exchange` argument link, a pattern for several names gives no link, the source drawn as existing and as not declared), `KnownQueueOwnDeadLetterTest` and a desktopApp wiring test (`CatalogKnownTopologyQueueDetailsTest` is the model: argument as text, absent, `typedArgumentsComplete = false`, a failed `queueDeclarations` read keeps the names), `PlannerViewModelDeadLetterNotesTest` (the read runs on a queue change, the old job is cancelled, no reader gives no note, no read on a 3.x connection). Compose: `DeadLetterFormUiTest` (tags below, Next disabled until the tick, "At least once" absent for a classic or unknown queue) and a font-scale 1.5 / 1280 x 800 fold check like `PolicyFormUiTest`.
- Live: nothing in this task. The dead-letter path is proven by the gate (`M9-13`): a message that expires in the source queue arrives in the planned dead-letter queue after the saved plan was applied by the real runner. The gate does not wait for an at-least-once retry.

## Design notes
The recipe is a form that returns actions; after "Add to plan" nothing remembers that the four steps belong together (A1): each can be edited or removed. The policy comes last so that no dead letter is ever pointed at an exchange that does not exist yet, and a rerun after a failure repeats the earlier steps as no-ops (the existing runner). No broker in this task except through the spike's facts.

Boundaries: everything above `ui` lives in `core.planner` (no `java.*`, no RabbitMQ types); the wiring change is `PlannerWiring` in `desktopApp` only; `data-rabbit` is not touched (`queueDeclarations` and `policyReader` exist). Tests: the fake broker is `KnownTopology` values and a fake `BrokerPolicyReader`; no real broker.

Tags for the UI (stable once chosen): `planWizard:deadLetter:queue`, `:targetQueue`, `:targetStream`, `:exchange`, `:targetName`, `:strategyAtMostOnce`, `:strategyAtLeastOnce`, `:refusedTick`, `:notes`. Existing wizard tags (`planWizard:pageMessage`, Next, Back) are unchanged.

Safety: nothing here touches the broker beyond the one existing GET of a policy; no queue message is read; no plain path; the form only builds actions, which stay subject to the configure permission and the topology level exactly as the other kinds (the planner is locked without them).

## UX
`docs/ux/Planner.dc.html`: "4." form Dead-lettering for a queue (updated 2026-11-17), "5. Summary" (the causes sentence and "The queue orders itself is not changed."), steps 4 to 7 of the main block, the dashed edge of the review graph. States to build: empty (no queue chosen: Next disabled with `Choose or type the queue`), error per field (name empty or refused, source is a stream, tick missing), hints (naming style, "already exists" on the broker), notes (default note, at-least-once note, own-argument note, policy note), no broker read (no suggestions from the broker, type unknown: `queues`, no at-least-once, no own-argument note, no policy note), locked planner (read-only level, no configure, 3.x: no wizard, existing).

## MCP
None (owner decision 2026-11-15; evaluated again 2026-11-17: the recipe only builds ordinary steps, and `:mcp-server` has no planner tool; an agent that needs dead-lettering writes the four migration actions, which the existing tools already take).

## Open points
No product decision is open. Settled questions:
- Q12 (decided 2026-11-15, confirmed by S-C7, S-C3 and S-C4): fanout exchange and quorum dead-letter queue by default; stream target and at-least-once only with the tick, only for a known quorum queue; the default stays at most once.
- Owner review items 1, 2, 3, 8, 9, 10, 12: ACCEPTED AS RECOMMENDED (2026-11-17, spec O19). Item 10 is skipped until `M9-00b-c` is built; item 12 adds nothing.
- S-F2 (one policy per queue, the highest priority wins, an equal priority is unpredictable): the recipe's policy has priority 0, so a broker policy of priority 0 on the same queue gives the "one of the two applies" note of `M9-08`; the recipe never changes the priority.
- S-F1b: a policy binds only if EVERY key suits the queue's type, so at-least-once is offered only for a known quorum queue and its `apply-to` is `quorum_queues`. The plain at-most-once policy uses `apply-to` by the source type, or `queues` when unknown (binds quorum and classic; whether `queues` reaches a stream is unmeasured, and a stream as source is out).
- Known limit, stated so the developer does not close it: a source queue the app did not read (not connected) has no known type; the recipe then adds the policy with `queues` and offers no at-least-once. That is the decided behaviour, not a bug.
- Not measured, stay out of any text: the delivery-limit and `max-length` causes into a STREAM, a direct exchange into a stream, `reject-publish-dlx` with at-least-once (`M9-08e` row 2).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed. The mockup and the scenarios match the texts above word for word. Run: `:core:jvmTest`, `:ui:jvmTest` (the new classes), `:desktopApp:test` (the wiring test), `:acceptance-tests:test` with the `@m9-09` tag, and the full fast suite once before the merge proposal; `brokerTest` is not needed (no transport code changes).

Note (architect, M9-05b review, 2026-11-16): the check judges only the 20 arguments of `DeclareEquivalenceTable`. If this task offers an argument outside them, first add a live row to the table and the honoured sets in `HonouredArgumentRule` (see `M9-05b-check-argument-rule.md`, Notes for later tasks). This task offers none: the four steps use `x-queue-type` and the policy keys already in `PolicyKeyCatalog`.

## What the spike settled (M9-00b, 2026-11-16; RabbitMQ 4.4.0; `docs/spec.md` "Measured facts of M9-00b")
This task MAY rely on, without measuring again:
- a `dead-letter-exchange` policy on a quorum or classic queue that exists dead-letters expired, rejected, delivery-limit and `max-length` messages (S-C1, S-C8); a fanout exchange keeps the routing key whatever it is (S-C9: a direct one keeps the publish key);
- a stream bound to the dead-letter exchange receives them, by at-most-once and at-least-once, with `x-exchange` = the dead-letter exchange, `x-routing-key` = the original key and the death information in the annotations (S-C7, fixture `s-c7-stream-message.json`);
- at-least-once by policy needs `dead-letter-strategy: at-least-once` AND `overflow: reject-publish` together, on a quorum queue; the policy `PUT` is 201 and `effective_policy_definition` shows all three keys (S-C3); WITHOUT `reject-publish` the broker accepts and shows the strategy and does not hold the message (S-C4);
- a held dead letter is counted in the source queue (`messages` 1, ready 0, unacknowledged 0) and delivered late, minutes after the target is repaired in the observations (S-C3: 175 s, one run);
- publishers are refused when the queue is full: a negative confirm or HTTP 400; a quorum queue holds N + 1 (S-C5);
- a policy naming a missing exchange is accepted silently; the four-step order with the policy last is right (S-C6);
- a classic queue gets no `dead-letter-strategy` (a policy with it binds to nothing, S-C8);
- a queue's own `x-dead-letter-exchange` argument wins over the policy's exchange (S-C2; one observation, only `dead-letter-exchange` measured).
It MUST NOT:
- promise or imply a delivery time for a held dead letter, or print a number of minutes (a single observation);
- read `effective_policy_definition` to decide whether the recipe is "already there" or in effect (it shows the policy for a queue that ignores it, S-C2);
- offer a dead-letter exchange for a stream as the SOURCE (S-D1: refused; S-D2: a policy is accepted and never applies);
- assume more about a stream message than the fixture holds (the delivery-limit and `max-length` causes into a stream, and a direct exchange into a stream, were not measured);
- add a direct dead-letter exchange or `dead-letter-routing-key` (still out; S-C9 measured the keys only);
- show the raw `x-opt-deaths` anywhere: the Messages screen fix is `M9-00b-c-readable-death-annotations`, not this task.
The live proof stays the gate (`M9-13`): a message that expires in the source queue arrives in the planned dead-letter queue; the gate does not wait for an at-least-once retry.

## Status
Architect, 2026-11-17, step 1 (brief): READY for the test developer. Branch `m9-09-dead-lettering-recipe` (worktree `../Caerbannog-m9-09`) from main `7d024e73`. Done in this step: owner decision O19 recorded (spec, the `M9-00b` list, this brief); `@needs-decision` removed from the three scenarios and the header of `21_topology_planner_recipes.feature` rewritten; the outline's `Given` fixed to `I chose the naming style "<style>" in the planner`; the mockup form updated to the decided wording; the brief completed against what `M9-08` built (reuse table, `KnownQueue.ownDeadLetterExchange`, the async policy note in the view model, `FormContext.queueType`, `ExactPattern.nameOf`, the `PlanLinkKind.DeadLetter` name). No product question is open.
Next: step 2 test developer (red tests, glue steps listed in "Acceptance"), step 3 senior developer, step 4 UI checks, step 5 review.
