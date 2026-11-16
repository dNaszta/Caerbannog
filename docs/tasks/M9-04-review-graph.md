# M9-04: Review graph: the result of the plan as a read-only picture with the new parts marked

Milestone: M9    Depends on: M9-03 (screen, merged), M9-01 (model)    Size: L (three days; the two cut lines are named in Design notes)

PLANNED 2026-11-15, checked against the real code 2026-11-16 (step 1, round 0). Needs no spike.

## Goal
The review card of the planner has a Graph and JSON switch. Graph, shown first, draws what the broker will have after the plan: the plan's exchanges, queues, streams and bindings marked "new", and, when the broker could be read, the existing items those bindings touch, unmarked. JSON is the text that is there today. The graph is a review only: nothing on it changes the plan.

## Why
Spec "Topology planner (M9)", "Review graph" (including the paragraph "Which existing items are drawn", added at step 1) and A12; owner decision O4 ("graph should be just a review"); the owner accepted at the M9-03 review that the Graph/JSON switch arrives here and that the JSON text carries the version "draft".

## Scope
In:
- `core`, package `dev.caerbannog.core.planner`:

```kotlin
data class PlanGraph(
    val graph: BindingGraph,                 // built by the existing buildBindingGraph
    val newNodeIds: Set<String>,
    val newEdgeIds: Set<String>,
    val notInPlanNodeIds: Set<String>,       // referred to by a binding, not declared by the plan and not on the broker
    val links: List<PlanLink> = emptyList(), // dead-letter and response-stream links; filled by M9-07 and M9-09
    val edgeLabels: Map<String, String> = emptyMap(),   // an MQTT route's topic filter; filled by M9-10
    val notice: PlanGraphNotice? = null,     // TooLarge | OnlyThePlan(NotConnected | BrokerNotRead)
    val notes: List<PlanGraphNote> = emptyList(),   // plain notes drawn between two nodes, never a step or an edge; filled by M9-07b
)
fun planPreview(plan: TopologyPlan, known: KnownTopology?): PlanGraph   // known == null: only the plan is drawn
```

  `planPreview` turns the plan's actions into the rows `buildBindingGraph` takes (`ExchangeRow`, `QueueRow`, `StreamSummary`, `BindingRow`), adds the known rows of the ends of the plan's bindings, and returns the sets beside the graph. Existing bindings of the broker are not drawn. A name that the plan declares and `known` also holds is drawn as existing and is not in `newNodeIds` (spec paragraph "Which existing items are drawn"). `KnownTopology` is the existing class of `FormContext.kt` (exchange name to type, queue names, stream names). The node cap (`MAX_GRAPH_NODES`) and `isTooLarge` are the existing ones; edge ids are the existing `edge-<i>` in the order of the rows `planPreview` passes (the plan's bindings in step order), so `newEdgeIds` is computed from that order and a test fixes it.
- `core`, the read of the broker's names (new in this brief, found at step 1): the real code never fills `PlannerContext.known` (`plannerContextOf` leaves it empty, finding F5 of the M9-03 review), so without a read a connected user would see "not in this plan" on every existing exchange, which is false. Add a port in `core.planner`:

```kotlin
fun interface KnownTopologyReader { suspend fun read(vhost: String): KnownTopology? }   // null: the broker could not be read
```

  `PlannerViewModel` takes `reader: KnownTopologyReader?` (null: not connected, or a connection that gets no planner), reads once when it starts and again when the plan's virtual host changes (a late answer for an old virtual host is dropped), never polls, and keeps the result in `PlannerState` (`known: KnownTopology?`, `graph: PlanGraph`, `graphView: ReviewView { Graph, Json }`, `onSelectReviewView`). The same `known` feeds `FormContext`, so the destination lists also show the broker's items (this closes F5 for free; the lists already know how to show them, `SuggestionSource.BROKER`). `PlannerState.graph` is derived from the plan and `known` on every change, so removing a step removes its edge.
- `desktopApp`: the adapter of the port over the existing `TopologyCatalog` (`exchanges(vhost)`, `queues(vhost, page)` for all pages) and the stream catalog (stream names), in `PlannerWiring.kt`, `createPlannerViewModel` passes it; not connected, or `unavailableReason` set (3.x, no executor): reader null, so no request of any kind is sent (spec "Guardrails that stay as they are"). Only list calls; the adapter has no path under a queue's name.
- `core`, pure and unit-tested: the texts of the two notices (fixed constants in `PlannerTexts`), the accessible name of a node (`"<kind> <name>, new"` / `"<kind> <name>, already on the broker"` / `"<kind> <name>, not in this plan"`), and the legend entries.
- `ui`: first, as its own commit with no test edited and the screenshots unchanged, the drawing part of `BindingsGraphView` (the canvas of edges, the routing key pills and the node cards, inside the scroll container) moves into an `internal` composable that takes a `BindingGraph` and optional marks (`BindingGraphCanvas(graph, marks, onSelect...)`); `BindingsGraphView` keeps its overflow notice, loading box and empty message and calls it. Then `PlanReviewGraph(state.graph)` draws it with the marks: thick outline and a "new" pill, a dotted outline and "not in this plan", the legend, the notices, and the Graph and JSON switch in `PlanReviewCard` (`PlannerScreen.kt`, whose doc comment "Until the review graph (M9-04)" is replaced). The JSON view stays as it is (text, Copy, scrollbar).
- Accessible names and keyboard: nodes are reachable by Tab as in the Bindings graph; no node is a button that changes anything.
- Test tags: `planner:reviewGraph`, `planGraphNode:<id>`, `planGraphNew:<id>`, `planner:graphNotice`, `planner:reviewViewSwitch`; the existing `planner:reviewCard`, `planner:jsonView`, `planner:jsonCopyBtn` stay.
- Hand-over fix from the M9-03 review (R1, R2), its own last commit, tests first: one planner scope per window, `old.flush()` before the new view model is created, in `PlannerWindowContent` (`desktopApp/Main.kt`); `PlannerViewModelHandoverTest.raceHandOver` changes to expect the text and `orderedHandOver` stays. The wizard that is open when the context changes may stay dropped (R2, accepted); carrying it across is not asked.

Out: the dead-letter edge (`M9-09`), the response-stream link (`M9-07`), the MQTT label (`M9-10`), the note about the owner's service (`M9-07b`): this task only carries the fields; drawing on the graph (never); polling or a Refresh of the known names; drawing the broker's own bindings.

## Acceptance
- BDD (all `19_topology_planner.feature`, tag `@m9-04`; 9 scenarios, three added at step 1, marked +):
  - "The review graph shows the result with the new parts marked" (@fake-broker @smoke)
  - "Nothing can be changed on the review graph" (@fake-broker @negative)
  - "The graph follows the plan" (@fake-broker)
  - "A plan that is too large to draw says so and keeps the step list" (@fake-broker)
  - "Without a connection only the plan is drawn" (@fake-broker)
  - "Graph nodes can be reached by the keyboard and say what is new" (@fake-broker)
  - + "When the broker cannot be read only the plan is drawn" (@fake-broker)
  - + "Something the plan declares and the broker already has is not marked new" (@fake-broker)
  - + "The review card shows the graph first and the JSON text on request" (@fake-broker @smoke)
- Unit tests, written first: `PlanPreviewTest` (a plan alone; a plan plus known ends; an existing end is not new; a declared name that the broker has is not new; a binding to something unknown gives `notInPlanNodeIds`; `known == null` gives `OnlyThePlan` and everything from the plan; 61 nodes give `TooLarge` and no nodes; removing a step removes its edge; ids are `e:<name>` and `q:<name>`; edge ids follow the binding order); view model tests with a fake `KnownTopologyReader` (read at start, re-read on a vhost change, late answer dropped, null reader reads nothing, null answer gives the "broker not read" notice, `known` reaches the destination lists, the view switch changes nothing in the plan or the draft); `PlannerTextsGuardTest` and `PlannerArchitectureGuardTest` stay green (no `java.*`, RabbitMQ or Paho import in `core.planner`).
- Pure-move gate: `BindingsGraphUiTest` and the Topology screenshots pass unedited after the extraction commit.
- Compose: `PlanReviewGraphUiTest` (marks, legend, notices, keyboard order and names, the switch, no node or edge has a click action that reaches a planner event; a sensitivity check proves the test would see one).
- desktopApp: a wiring test that the reader is null for no connection and for a 3.x connection, and that a counting fake catalog sees no call then; the adapter calls only `exchanges`, `queues` and the stream list (a guard on the interface it receives, as `TopologyCatalog` has no message call).
- Hand-over: `PlannerViewModelHandoverTest.raceHandOver` expects the text.

## Design notes
`planPreview` is pure and cheap (a plan is small); it is recomputed on every change of the plan or of `known`. Do not add a field to `GraphNode`: the marks travel beside the graph so the Topology screen is untouched. Composables stay thin: every text, name and mark comes from `PlannerState`. The scroll pitfall of CLAUDE.md applies: `BindingGraphCanvas` keeps its own horizontal and vertical scroll container with the existing fixed-size inner box (that is where the content truly overflows); the JSON view keeps its scroll and scrollbar; do not put the graph inside the card's other scroll. Where the broker is faked: the reader from a fake, `KnownTopology` built in the test rig (`PlannerRig`); no real broker, no `@broker` scenario. The graph never reaches the broker; the only broker contact of this task is the one name read, GET list calls, 4.x only, through the existing catalogs.

Cut lines if the task runs over three days, each already its own commit: (1) the hand-over fix goes back to a note for the next planner task that edits `PlannerWindowContent`; (2) the destination lists showing broker items (a consequence of passing `known` to `FormContext`) can be left to a later task by passing `KnownTopology.None` there, but then the state must carry both values. Do not cut the reader: without it the graph says something false when connected.

Decision on the hand-over fix: included, not kept as a note. Reason: this task adds the reader to `createPlannerViewModel`, and the reader makes the view model do more at start and on rebuild, so the "rebuild on context change" path is the one the new code relies on; the fix is about ten lines in one function plus one test flip, it was specified exactly at the M9-03 review, and it is independent of the graph, so it is a separate commit that can be dropped. If it is dropped, `PlannerWindowContent` stays untouched.

## UX
`docs/ux/Planner.dc.html`: the review card of the main block (layout, legend, the Graph/JSON switch, the note that it is read only) and "8. Review graph: other cases" (the two banners). The mockup shows the not-connected text; the broker-not-read variant is the only wording not drawn there (see Open points). States to build: empty plan (existing `planner:reviewEmpty`), graph, too large, only the plan (two causes), JSON.

## MCP
None (owner decision 2026-11-15, MCP evaluation in the spec): a planning aid for a person, nothing is created or changed, the graph is derived from an in-app plan; the one broker read (names of exchanges, queues and streams) is the Topology screen's own read and the MCP server already lists topology.

## Open points
- No product decision blocks this task. Two defaults the owner may veto at his review of the screen, both one-line changes: (a) wording of the second "Only the plan is drawn" line when the broker could not be read, recommended "The broker could not be read: what already exists is not shown." (the mockup has only the not-connected line "Not connected: what already exists on a broker is not shown."); (b) a name the plan declares that the broker already has is drawn as existing, not "new" (recommended: the plan adds nothing there; the alternative, marking it "new" anyway, would say something the broker will not do). Both are in the spec paragraph and in scenarios.
- The reader's virtual host is the plan's (`plan.vhost`), which the planner lets the user change; the read follows it. If the profile's user cannot see that virtual host, the adapter answers null and the notice says the broker could not be read.
- Whether the stream catalog is reachable from the planner's inputs (`MigrationsInputs` holds only `topology`): the adapter must take it from the same `StreamSource` as `topology`; check at step 2.5, add one field to `MigrationsInputs` if needed (a value comparable by identity, like `topology`).
- The wizard open at a rebuild stays dropped (R2, accepted at the M9-03 review).
- The dashed and dotted link styles drawn in the mockup are built with `M9-07` and `M9-09`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-04-review-graph`, base main `a583488`): brief checked against the spec (A12, O4, "Review graph", guardrails), the scenarios, the mockup and the real code (`buildBindingGraph` and its `edge-<i>` and `e:`/`q:` ids, `BindingsGraphView` and its inline drawing, `PlannerState.migrationText`, `PlanReviewCard` with only the JSON text, `KnownTopology`, `plannerContextOf`, `MigrationsInputs`, `PlannerWindowContent`). Brief changed: (1) found that `PlannerContext.known` is never filled in the real app, so the connected graph would mark every existing exchange "not in this plan"; added the `KnownTopologyReader` port, its adapter over the existing catalogs (list calls, 4.x only, nothing for no connection or 3.x) and the "broker not read" case that the mockup already names; `planPreview` takes `KnownTopology?`; (2) the Graph/JSON switch and "Graph first" are now explicit (owner accepted at M9-03); (3) a declared name the broker already has is not marked new (spec paragraph added); (4) the view-model hand-over fix is IN scope as the last, separate commit (reason in Design notes); (5) size M to L with two cut lines. Scenarios: 3 added to `19` (broker not read, declared and already there, Graph/JSON switch), 6 became 9; `tests/bdd/README.md` counts updated (161). Spec: paragraph "Which existing items are drawn" added under "Review graph". MCP: none, recorded. Open product question: none blocking; two defaults listed in Open points. Next: step 2 (test developer, tests first, red).
