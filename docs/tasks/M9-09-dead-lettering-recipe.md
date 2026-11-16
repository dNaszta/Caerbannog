# M9-09: Dead-lettering for a queue: one wizard kind that adds the exchange, the dead-letter queue, the binding and the policy

Milestone: M9    Depends on: M9-00b (S-C), M9-00c (S-F), M9-08, M9-04    Size: M

PLANNED 2026-11-15. Not started. The spike M9-00b is measured and reviewed (2026-11-16): the `@needs-spike` tags are gone; section "What the spike settled (M9-00b)" says what this task may rely on and must not.

## Goal
The wizard kind "Dead-lettering for a queue" asks for the source queue and adds four ordinary steps in a safe order: the dead-letter exchange, the dead-letter queue (or stream), the binding between them, and last a policy on exactly the source queue. The source queue is not declared or changed. The review graph shows the dead-letter path.

## Why
Owner decision O8 ("DLX"). Spec "Topology planner (M9)": A4 (by policy, with the reasons), "Dead-lettering for a queue".

## Scope
In:
- `core.planner`: `DeadLetterForm(queue, target = queue, exchangeName, targetName, strategy = at most once, publishersRefusedAccepted = false)`. Names are suggested with `PlanNaming.derived` (`dlx`, `dlq`) and follow the queue name until edited. `actions`, in this order: `DeclareExchange(exchangeName, fanout)`, `DeclareQueue(targetName, quorum)` or `DeclareStream(targetName)`, `DeclareBinding(exchangeName, targetName)`, `DeclarePolicy(name = exchangeName, pattern = ExactPattern.of(queue), applyTo by the source queue's type (quorum queues, classic queues; queues when the type is not known), definition dead-letter-exchange = exchangeName)`. At least once (measured, S-C3 and S-C4; only for a known quorum queue, only with the tick): the definition also holds BOTH `dead-letter-strategy: at-least-once` and `overflow: reject-publish`, always together (a form state with one and not the other cannot be built: S-C4 shows the strategy alone is accepted and not in effect).
- The source queue is picked from the plan or from `KnownTopology`, or typed; its type decides `applyTo` and whether "At least once" is offered.
- Placement: the four steps are appended; when the plan declares the source queue they therefore come after it.
- The summary sentence of the wizard, fixed in `PlannerTexts`, worded from S-C1 (measured: expired, rejected, over the delivery limit and dropped by `max-length` all dead-letter), and the texts of the Owner review list of `M9-00b` (items 1 to 3), proposed wording until the owner answers.
- A note when the broker queue has its OWN `x-dead-letter-exchange` argument (read from `QueueDeclaration.arguments` of the catalog, `typedArgumentsComplete` honoured): the queue uses its argument and ignores the policy (S-C2).
- The warning of `M9-08` when the broker already applies another policy to the source queue, shown in the form before the steps are added.
- `planPreview`: a `PlanLink(DEAD_LETTER, queue, exchange)` when a policy of the plan holds `dead-letter-exchange` and `ExactPattern.nameOf(pattern)` gives exactly one name, or a `DeclareQueue` of the plan carries the argument `x-dead-letter-exchange` (a hand-written file); the source queue is drawn as existing when the broker has it.
- `ui`: the kind card, `DeadLetterFormView`, the dashed edge.

Out: a direct dead-letter exchange with a dead-letter routing key (later); dead-lettering by queue arguments in the wizard (A4); dead-lettering for a stream as source (the broker has no such thing as far as is known, S-D); delivery limit and TTL settings (they are policy settings of `M9-08`).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "Dead-lettering for a queue adds four steps, the policy last" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "The queue itself is not declared or changed" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The policy matches exactly the chosen queue" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The dead-letter names are suggested with suffixes in the naming style" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Dead-lettering for a queue of the same plan comes after the queue" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Dead letters can go to a stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "At least once needs my tick and says what it changes" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "At most once is the default and adds no strategy setting" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "At least once is not offered for a classic queue" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A queue that already has a policy is pointed out before the recipe is added" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The review graph shows the dead-letter path" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The four steps are ordinary steps" (@fake-broker)
- BDD (added at M9-00b step 3, 2026-11-16): "Switching back to At most once takes both at-least-once settings away again" (@fake-broker); "At least once is not offered when the queue's type is not known" (@fake-broker @negative; Owner review list item 9 of M9-00c, decided); and three with proposed wording, tagged `@needs-decision` until the owner answers (they are bound in step 2 and stay out of the default run until the tag is removed): "The default says that a dead letter can be lost", "At least once says what it needs, what it changes and that it can be slow", "A queue that declares its own dead-letter exchange is pointed out".
- Unit tests, written first: `DeadLetterFormTest` (the four actions and their order for each target and strategy, `applyTo` by queue type, names per style, the follow-until-edited rule, the tick gate), `ExactPatternTest` additions (`nameOf` is the exact inverse and null for any other pattern), `PlanPreviewDeadLetterTest`. Compose: `DeadLetterFormUiTest`.
- Live: the dead-letter path is proven by the gate (`M9-13`): a message that expires in the source queue arrives in the planned dead-letter queue after the saved plan was applied by the real runner.

## Design notes
The recipe is a form that returns actions; after "Add to plan" nothing remembers that the four steps belong together (A1): each can be edited or removed. The policy comes last so that no dead letter is ever pointed at an exchange that does not exist yet, and a rerun after a failure repeats the earlier steps as no-ops (the existing runner). No broker in this task except through the spike's facts.

## UX
`docs/ux/Planner.dc.html`: "4." form Dead-lettering for a queue, "5. Summary", steps 4 to 7 of the main block, the dashed edge of the review graph.

## MCP
None (owner decision 2026-11-15).

## Open points
- Q12 (decided 2026-11-15: fanout exchange and quorum dead-letter queue by default; a stream target and at-least-once only if the spike confirms them; at-least-once never the default and only with an explicit tick): CONFIRMED by S-C7 (stream target) and S-C3 with S-C4 (at-least-once by policy, with the two keys); the default stays at most once. The "How sure" block is kept, with the owner's wording.
- S-C1 decided the summary sentence's content (all four causes are measured); the text is the owner's.
- S-F2 is measured (M9-00c, 2026-11-16): one policy per queue, the highest priority wins, an equal priority is unpredictable; the recipe's policy has priority 0 by default, so a broker policy of priority 0 on the same queue gives "one of the two applies" (the note of `M9-08`, wording decided 2026-11-16, Owner review list item 5 of `M9-00c`); the recipe never changes the priority by itself.
- Measured (S-F1b): `dead-letter-exchange`, `dead-letter-routing-key`, `overflow` take effect on quorum and classic queues; `dead-letter-strategy` on quorum only; a policy binds only if EVERY key suits the queue's type. So an at-least-once policy (`dead-letter-exchange`, `dead-letter-strategy`, `overflow`) has no effect on a classic queue: at-least-once is offered only for a known quorum queue (the brief already says so) and, when the source queue's type is unknown, it is not offered (Owner review list item 9: proposed behaviour); its `apply-to` is `quorum_queues`. The plain at-most-once policy (only `dead-letter-exchange`) uses `apply-to` by the source type, or `queues` when unknown (binds quorum and classic; whether `queues` reaches a stream is unmeasured, and a stream as source is out).

- Note (architect, M9-07 step 2.5, 2026-11-16): the outline "The dead-letter names are suggested with suffixes in the naming style" (feature 21) still starts with `Given the naming style is "<style>"`, which is a `@Then` assertion and cannot set the style. M9-07 fixed the same line of its own outline to `Given I chose the naming style "<style>" in the planner` (step in `PlannerDraftSteps`); the test developer of this task does the same here.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, M9-05b review, 2026-11-16): the check judges only the 20 arguments of `DeclareEquivalenceTable`. If this task offers an argument outside them, first add a live row to the table and the honoured sets in `HonouredArgumentRule` (see `M9-05b-check-argument-rule.md`, Notes for later tasks).

## What the spike settled (M9-00b, 2026-11-16; RabbitMQ 4.4.0; `docs/spec.md` "Measured facts of M9-00b")
This task MAY rely on, without measuring again:
- a `dead-letter-exchange` policy on a quorum or classic queue that exists dead-letters expired, rejected, delivery-limit and `max-length` messages (S-C1, S-C8); a fanout exchange keeps the routing key whatever it is (S-C9: a direct one keeps the publish key);
- a stream bound to the dead-letter exchange receives them, by at-most-once and at-least-once, with `x-exchange` = the dead-letter exchange, `x-routing-key` = the original key and the death information in the annotations (S-C7, fixture `s-c7-stream-message.json`);
- at-least-once by policy needs `dead-letter-strategy: at-least-once` AND `overflow: reject-publish` together, on a quorum queue; the policy `PUT` is 201 and `effective_policy_definition` shows all three keys (S-C3); WITHOUT `reject-publish` the broker accepts and shows the strategy and does not hold the message (S-C4);
- a held dead letter is counted in the source queue (`messages` 1, ready 0, unacknowledged 0) and delivered late, minutes after the target is repaired in the observations (S-C3: 175 s, one run);
- publishers are refused when the queue is full: a negative confirm or HTTP 400; a quorum queue holds N + 1 (S-C5);
- a policy naming a missing exchange is accepted silently; the four-step order with the policy last is right (S-C6);
- a classic queue gets no `dead-letter-strategy` (a policy with it binds to nothing, S-C8).
It MUST NOT:
- promise or imply a delivery time for a held dead letter, or print a number of minutes (a single observation);
- read `effective_policy_definition` to decide whether the recipe is "already there" or in effect (it shows the policy for a queue that ignores it, S-C2);
- offer a dead-letter exchange for a stream as the SOURCE (S-D1: refused; S-D2: a policy is accepted and never applies);
- assume more about a stream message than the fixture holds (the delivery-limit and `max-length` causes into a stream, and a direct exchange into a stream, were not measured);
- add a direct dead-letter exchange or `dead-letter-routing-key` (still out; S-C9 measured the keys only);
- show the raw `x-opt-deaths` anywhere: the Messages screen fix is `M9-00b-c-readable-death-annotations`, not this task.
The live proof stays the gate (`M9-13`): a message that expires in the source queue arrives in the planned dead-letter queue; the gate does not wait for an at-least-once retry.
