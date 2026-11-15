# M9-04: Review graph: the result of the plan as a read-only picture with the new parts marked

Milestone: M9    Depends on: M9-03 (screen), M9-01 (model)    Size: M

PLANNED 2026-11-15. Not started. Needs no spike.

## Goal
The review card of the planner draws what the broker will have after the plan: the plan's exchanges, queues, streams and bindings marked "new", and, when connected, the existing items those bindings touch, unmarked. It is a review only: nothing on it changes the plan.

## Why
Spec "Topology planner (M9)", "Review graph" and A12; owner decision O4 ("graph should be just a review").

## Scope
In:
- `core`, package `dev.caerbannog.core.planner`:

```kotlin
data class PlanGraph(
    val graph: BindingGraph,                 // built by the existing buildBindingGraph
    val newNodeIds: Set<String>,
    val newEdgeIds: Set<String>,
    val notInPlanNodeIds: Set<String>,       // referred to by a binding, not declared by the plan and not known on the broker
    val links: List<PlanLink> = emptyList(), // dead-letter and response-stream links; filled by M9-07 and M9-09
    val edgeLabels: Map<String, String> = emptyMap(),   // an MQTT route's topic filter; filled by M9-10
    val notice: PlanGraphNotice? = null,     // TooLarge | OnlyThePlan
    val notes: List<PlanGraphNote> = emptyList(),   // plain notes drawn between two nodes, never a step or an edge; filled by M9-07b
)
fun planPreview(plan: TopologyPlan, known: KnownTopology): PlanGraph
```

  The function turns the plan's actions into the rows `buildBindingGraph` takes (`ExchangeRow`, `QueueRow`, `StreamSummary`, `BindingRow`), adds the known rows of the ends of the plan's bindings, and returns the sets beside the graph. Existing bindings of the broker are not drawn. The node cap (`MAX_GRAPH_NODES`) and `isTooLarge` are the existing ones.
- `ui`: first, as its own commit and with no test edited and the screenshots unchanged, the drawing part of `BindingsGraphView` is moved into a composable that takes a `BindingGraph` and optional marks (`BindingGraphCanvas(graph, marks, onSelect...)`), and `BindingsGraphView` calls it. Then `PlanReviewGraph(state.graph)` draws it with the marks: thick outline and a "new" pill, a dotted outline and "not in this plan", the legend, the two notices, and the Graph and JSON switch of the review card.
- Accessible names: a node reads "<kind> <name>, new" or "<kind> <name>, already on the broker"; nodes are reachable by Tab as in the Bindings graph; no node is a button that changes anything.
- Test tags: `planner:reviewGraph`, `planGraphNode:<id>`, `planGraphNew:<id>`, `planner:graphNotice`, `planner:reviewViewSwitch`.

Out: the dead-letter edge (`M9-09`), the response-stream link (`M9-07`), the MQTT label (`M9-10`), the note about the owner's service (`M9-07b`): this task only carries the fields; drawing on the graph (never).

## Acceptance
- BDD: `19_topology_planner.feature` / "The review graph shows the result with the new parts marked" (@fake-broker @smoke)
- BDD: `19_topology_planner.feature` / "Nothing can be changed on the review graph" (@fake-broker @negative)
- BDD: `19_topology_planner.feature` / "The graph follows the plan" (@fake-broker)
- BDD: `19_topology_planner.feature` / "A plan that is too large to draw says so and keeps the step list" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Without a connection only the plan is drawn" (@fake-broker)
- BDD: `19_topology_planner.feature` / "Graph nodes can be reached by the keyboard and say what is new" (@fake-broker)
- Unit tests, written first: `PlanPreviewTest` (a plan alone; a plan plus known ends; an existing end is not new; a binding to something unknown gives `notInPlanNodeIds`; 61 nodes give the notice and no nodes; removing a step removes its edge; ids are the ones of `buildBindingGraph`, `e:<name>` and `q:<name>`).
- The pure-move gate: `BindingsGraphUiTest` and the Topology screenshots pass unedited after the extraction commit.
- Compose: `PlanReviewGraphUiTest` (marks, legend, notices, keyboard, no node or edge has a click action that reaches a planner event; a sensitivity check proves the test would see one).

## Design notes
`planPreview` is pure and cheap (a plan is small); it is recomputed on every change of the plan or of `KnownTopology`. Do not add a field to `GraphNode`: the marks travel beside the graph so the Topology screen is untouched. Where the broker is faked: `KnownTopology` from `FakeTopologyCatalog`; no real broker.

## UX
`docs/ux/Planner.dc.html`: the review card of the main block (layout, legend, the note that it is read only) and "8. Review graph: other cases".

## MCP
None (owner decision 2026-11-15).

## Open points
None that blocks. The dashed and dotted link styles are drawn in the mockup; they are built with `M9-07` and `M9-09`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
