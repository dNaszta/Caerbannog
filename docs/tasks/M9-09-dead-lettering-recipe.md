# M9-09: Dead-lettering for a queue: one wizard kind that adds the exchange, the dead-letter queue, the binding and the policy

Milestone: M9    Depends on: M9-00b (S-C), M9-00c (S-F), M9-08, M9-04    Size: M

PLANNED 2026-11-15. Not started.

## Goal
The wizard kind "Dead-lettering for a queue" asks for the source queue and adds four ordinary steps in a safe order: the dead-letter exchange, the dead-letter queue (or stream), the binding between them, and last a policy on exactly the source queue. The source queue is not declared or changed. The review graph shows the dead-letter path.

## Why
Owner decision O8 ("DLX"). Spec "Topology planner (M9)": A4 (by policy, with the reasons), "Dead-lettering for a queue".

## Scope
In:
- `core.planner`: `DeadLetterForm(queue, target = queue, exchangeName, targetName, strategy = at most once, publishersRefusedAccepted = false)`. Names are suggested with `PlanNaming.derived` (`dlx`, `dlq`) and follow the queue name until edited. `actions`, in this order: `DeclareExchange(exchangeName, fanout)`, `DeclareQueue(targetName, quorum)` or `DeclareStream(targetName)`, `DeclareBinding(exchangeName, targetName)`, `DeclarePolicy(name = exchangeName, pattern = ExactPattern.of(queue), applyTo by the source queue's type (quorum queues, classic queues; queues when the type is not known), definition dead-letter-exchange = exchangeName)`. At least once (only when S-C confirms it by policy, only for a quorum queue, only with the tick): the definition also holds `dead-letter-strategy` and `overflow` as measured.
- The source queue is picked from the plan or from `KnownTopology`, or typed; its type decides `applyTo` and whether "At least once" is offered.
- Placement: the four steps are appended; when the plan declares the source queue they therefore come after it.
- The summary sentence of the wizard, fixed in `PlannerTexts`, worded from S-C1 (which causes really dead-letter).
- The warning of `M9-08` when the broker already applies another policy to the source queue, shown in the form before the steps are added.
- `planPreview`: a `PlanLink(DEAD_LETTER, queue, exchange)` when a policy of the plan holds `dead-letter-exchange` and `ExactPattern.nameOf(pattern)` gives exactly one name, or a `DeclareQueue` of the plan carries the argument `x-dead-letter-exchange` (a hand-written file); the source queue is drawn as existing when the broker has it.
- `ui`: the kind card, `DeadLetterFormView`, the dashed edge.

Out: a direct dead-letter exchange with a dead-letter routing key (later); dead-lettering by queue arguments in the wizard (A4); dead-lettering for a stream as source (the broker has no such thing as far as is known, S-D); delivery limit and TTL settings (they are policy settings of `M9-08`).

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "Dead-lettering for a queue adds four steps, the policy last" (@fake-broker @smoke @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The queue itself is not declared or changed" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The policy matches exactly the chosen queue" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The dead-letter names are suggested with suffixes in the naming style" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Dead-lettering for a queue of the same plan comes after the queue" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Dead letters can go to a stream" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "At least once needs my tick and says what it changes" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "At most once is the default and adds no strategy setting" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "At least once is not offered for a classic queue" (@fake-broker @negative @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A queue that already has a policy is pointed out before the recipe is added" (@fake-broker @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The review graph shows the dead-letter path" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The four steps are ordinary steps" (@fake-broker)
- Unit tests, written first: `DeadLetterFormTest` (the four actions and their order for each target and strategy, `applyTo` by queue type, names per style, the follow-until-edited rule, the tick gate), `ExactPatternTest` additions (`nameOf` is the exact inverse and null for any other pattern), `PlanPreviewDeadLetterTest`. Compose: `DeadLetterFormUiTest`.
- Live: the dead-letter path is proven by the gate (`M9-13`): a message that expires in the source queue arrives in the planned dead-letter queue after the saved plan was applied by the real runner.

## Design notes
The recipe is a form that returns actions; after "Add to plan" nothing remembers that the four steps belong together (A1): each can be edited or removed. The policy comes last so that no dead letter is ever pointed at an exchange that does not exist yet, and a rerun after a failure repeats the earlier steps as no-ops (the existing runner). No broker in this task except through the spike's facts.

## UX
`docs/ux/Planner.dc.html`: "4." form Dead-lettering for a queue, "5. Summary", steps 4 to 7 of the main block, the dashed edge of the review graph.

## MCP
None (owner decision 2026-11-15).

## Open points
- Q12 (decided 2026-11-15: fanout exchange and quorum dead-letter queue by default; a stream target and at-least-once only if the spike confirms them; at-least-once never the default and only with an explicit tick): the stream target and "At least once" are built only if S-C7 and S-C3 confirm them; the default is always at most once. If S-C3 fails, the "How sure" block is not drawn and three scenarios are removed by the architect.
- S-C1 decides the summary sentence; until then it is not final wording.
- S-F2 decides the policy warning.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
