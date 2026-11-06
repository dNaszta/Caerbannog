# Bindings graph in Topology screen

Milestone: M2    Depends on: M2-08c, M2-08f1, M2-08f2 (merged)    Size: M

## Goal

Provide a visual DAG graph representation of exchanges, queues, and bindings on the Topology Bindings tab alongside the existing table view, toggled by a Table / Graph switch.

## Why

Addresses decision T2 from M2-08 plan (`docs/tasks/M2-08-topology-read-only.md`). Routing topologies in RabbitMQ (fanout, topic routing, exchange-to-exchange bindings) are inherently graph-structured. Seeing connections visually clarifies message flows, while keeping the table view ensures accessibility, dense scanning, and text copying.

## Decisions

- **G1 (Drawing approach)**: Hand-drawn Compose components (nodes as focusable composables, edges on a Canvas behind them, own layered layout algorithm in core without third-party graph dependencies). Decided by owner 2026-11-05.
- **G2 (View toggle)**: Both table and graph views are kept, toggled via a Table / Graph toggle on the Bindings tab (Table by default). Decided by owner 2026-11-06.
- **U2 (Visual design)**: Architect extends `docs/ux/Topology.dc.html` with Bindings graph section using standard Material 3 tokens. Decided by owner 2026-11-06.

## Scope

In:
- `core`:
  - Graph models:
    - `BindingsViewMode`: `Table`, `Graph`.
    - `GraphNode`: `id: String`, `name: String`, `kind: GraphNodeKind` (`Exchange`, `Queue`), `detail: String` (type for exchange, ready count for queue), `bindingCount: Int`.
    - `GraphEdge`: `sourceId: String`, `targetId: String`, `routingKey: String`, `arguments: String`.
    - `PositionedNode`: `node: GraphNode`, `x: Float`, `y: Float`, `layer: Int`, `orderInLayer: Int`.
    - `PositionedEdge`: `edge: GraphEdge`, `fromX: Float`, `fromY: Float`, `toX: Float`, `toY: Float`.
    - `BindingGraph`: `nodes: List<PositionedNode>`, `edges: List<PositionedEdge>`, `totalBindings: Int`, `isTooLarge: Boolean`.
  - Pure layout algorithm in `core` (no Compose dependencies):
    - Left-to-right layered DAG: Root exchanges (layer 0) -> chained exchanges by depth -> terminal queues.
    - Deterministic, stable ordering within layers (sorted by name) so nodes do not jump across refreshes.
    - Scale cap: `MAX_GRAPH_NODES = 60`. If visible nodes exceed cap, `isTooLarge = true` and nodes/edges are empty (no partial graphs drawn).
    - Default exchange and its 1:1 queue bindings are excluded by default (honors `showDefaultExchange` switch).
  - `TopologyState`:
    - `bindingsView: BindingsViewMode = BindingsViewMode.Table`
    - `graph: BindingGraph? = null`
  - `TopologyViewModel`:
    - View mode toggle `setBindingsView(mode: BindingsViewMode)`
    - Computes `BindingGraph` when `tab == TopologyTab.Bindings` and `bindingsView == BindingsViewMode.Graph`.
- `ui`:
  - `TopologyControls`:
    - On `TopologyTab.Bindings`, add a `Table` / `Graph` segmented toggle button next to `DefaultExchangeSwitch`.
  - `BindingsGraph`:
    - Canvas drawing connecting bezier/lines with routing key labels.
    - Composables for nodes styled with rounded cards/chips (`onSurfaceVariant`, border, icon).
    - Full keyboard accessibility: nodes are focusable, Tab/arrow moves through nodes, `contentDescription` announces kind, name, and binding count.
    - Overflow notice when `isTooLarge`: "There are more bindings than can be drawn (N bindings). Choose an exchange or narrow the search filter."
- `tests/bdd`:
  - Un-skip the 5 `@needs-08d` scenarios in `09_topology_queues.feature`:
    1. `The bindings graph shows an exchange with its queues`
    2. `The bindings graph never draws the default exchange unless asked`
    3. `A big neighbourhood is not drawn partly`
    4. `The bindings can also be read as a table`
    5. `Graph nodes can be reached by the keyboard`

Out:
- No graph editing, moving, or drag-and-drop (read-only per M2 spec).
- No external heavy graph visualization libraries.
- No changes to exchange or queue declarations.

## MCP Evaluation

None. Headless `:mcp-server` already provides `caerbannog_list_bindings`, `caerbannog_list_queues`, and `caerbannog_list_exchanges` which expose full topology connectivity as JSON data. Graph layout and rendering is purely desktop visual presentation.

## Acceptance

- All 5 BDD scenarios in `09_topology_queues.feature` pass.
- Unit tests prove DAG layered layout, cycle handling, node capping at 60, and stability.
- Compose UI tests prove Table/Graph switching, node keyboard navigation, semantics announcements, and overflow banner.
- Existing tests across all modules continue to pass.

## Definition of done

- Step 1: Brief written, UX mockup extended, committed.
- Step 2: Tests first written (BDD scenarios wired, unit tests red).
- Step 3: Core graph layout and UI canvas implementation green.
- Step 4: Verification completed.
- Step 5: Architect review accepted.
- Status and README row updated.

## Status

- Step 1: Brief written by solution-architect (2026-11-06). Decisions G1, G2, U2 resolved. Mockup extended in `docs/ux/Topology.dc.html`.
- Step 2: Tests-first written by test-developer (2026-11-06). Added pure graph layout unit tests in `TopologyGraphLayoutTest.kt` (DAG layers, cycle fallback, scale cap at 60, default exchange handling). Un-skipped 5 BDD acceptance scenarios in `tests/bdd/features/09_topology_queues.feature` and wired step definitions in `TopologySteps.kt`.
- Step 3: Senior developer implementation (2026-11-06). Added `BindingGraph.kt` pure DAG layout in `core`, `TopologyViewModel` integration, `BindingsViewSwitch` control in `TopologyControls.kt`, `BindingsGraphView.kt` with bezier canvas curves and accessible node cards, adapter in `desktopApp`, and Compose UI tests in `BindingsGraphUiTest.kt`.
- Step 4: Verification completed by test-developer (2026-11-06).
  - All 40 BDD scenarios in `RunTopologyAcceptanceTest` pass.
  - All unit tests in `core:jvmTest` pass (including 5 DAG graph layout tests).
  - All UI tests in `ui:jvmTest` pass (including 5 Bindings graph UI tests).
  - Full repo test suite `./gradlew test` passes cleanly (all modules).
  - Screenshot test `./gradlew screenshotTest` passes, generating `ui/build/screenshots/topology-12-bindings-graph.png`.
- Step 5: Architect review accepted by solution-architect (2026-11-06).
  - Visual layout strictly aligns with `docs/ux/Topology.dc.html` (`tab-bindings-graph`).
  - No mutation or message read/purge paths exist; quorum queues safety preserved.
  - Accessible keyboard focus and screen reader descriptions verified.
  - Ready for merge into `main` pending product owner approval.

Branch: `m2-08d-bindings-graph`
Round: 1
Verdict: accepted
