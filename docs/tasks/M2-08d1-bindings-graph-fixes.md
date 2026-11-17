# Bindings graph fixes: HiDPI canvas scaling, crossing-edge label placement, stream destinations, and graph footer/search

Milestone: M2    Depends on: M2-08d    Size: S

## Goal
The Topology Bindings graph renders aligned bezier curves on HiDPI / scaled displays (such as Fedora Wayland/XWayland), centers routing-key pills without overlapping when edges cross, omits fake `(0 ready)` text on stream partition destinations, hides the table pagination footer while in Graph view, and narrows the graph when the user types in the Bindings search box.

## Why
Hand testing on Fedora 45 surfaced four visual and behavioral defects in the M2-08d Bindings graph:
1. **HiDPI `dp` vs `px` mismatch (`BindingsGraphView.kt`):** Node cards and routing-key pills use `.offset(x.dp, y.dp)` (scaled by display density), whereas the `Canvas` bezier paths passed raw `dp` floats directly to `Path.moveTo` / `cubicTo` as pixels (`px`). On any display with `density != 1.0f`, the bezier curves shrink toward the top-left corner and detach from the nodes.
2. **Crossing-edge routing-key pill collisions & off-center shift (`BindingGraph.kt`, `BindingsGraphView.kt`):** All routing-key pills were placed at the edge midpoint `(midX, midY)` with a hardcoded `(-30.dp, -10.dp)` offset. When two edges between the same layers cross (such as `dev.commands -> dev-mail` and `dev.events -> dev-jobs` on the local dev broker), both share the exact same midpoint and their pills render on top of each other.
3. **Stream partition destinations show fake `(0 ready)` (`BindingGraph.kt`):** Bindings from a super stream exchange (e.g. `orders -> orders-0..2`) have `destinationType = QUEUE`, while `TopologyCatalog.queues()` excludes streams. `BindingGraph` defaulted missing `queueMap[name]?.readyMessages` to `0`, labeling stream partitions as `(0 ready)`.
4. **Table pager footer shown under Graph & search ignored (`TopologyScreen.kt`, `TopologyViewModel.kt`):** The table pagination footer (`1 to 4 of 4 < >`) was rendered below the unpaginated graph, and typing in "Search bindings" did not filter the bindings passed to `buildBindingGraph`.

## Scope
In:
- `core` (`BindingGraph.kt`, `TopologyViewModel.kt`):
  - **R2 (Crossing-edge label coordinates):** Add `labelX: Float` and `labelY: Float` to `PositionedEdge` (defaulting to `(fromX + toX) / 2f` and `(fromY + toY) / 2f`). In `buildBindingGraph`, evaluate label positions along the cubic bezier curve $B(t)$ and stagger $t$ (e.g., $t = 0.32\text{f}$ vs $0.68\text{f}$, or vertical offset for parallel edges) whenever two non-empty routing-key labels between layers would collide ($|\Delta x| < 60\text{f}$ and $|\Delta y| < 18\text{f}$).
  - **R3 (Destination detail text):** In `buildBindingGraph`, when `queueMap[name] == null`, set `GraphNode.detail = ""` instead of `"(0 ready)"`. When `queue != null`, format `readyMessages` as `"(${groupDigits(ready)} ready)"` (or `"(${TopologyTexts.NOT_AVAILABLE})"` when `null`).
  - **R4b (Search filter in Graph view):** In `TopologyViewModel.render()`, filter the bindings passed to `buildBindingGraph` using the current Bindings search query (`matches(src) || matches(it.destination) || matches(it.routingKey)`).
- `ui` (`BindingsGraphView.kt`, `TopologyScreen.kt`):
  - **R1 (HiDPI `dp.toPx()` canvas scaling):** Convert `edge.fromX.dp.toPx()`, `edge.fromY.dp.toPx()`, `edge.toX.dp.toPx()`, and `edge.toY.dp.toPx()` inside `Canvas` in `BindingsGraphView.kt`.
  - **R2 (Centered pills):** Position each routing-key pill at `(edge.labelX.dp, edge.labelY.dp)` and center it over that point using a `.layout` modifier (`place(-placeable.width / 2, -placeable.height / 2)`), removing the hardcoded `- 30f` / `- 10f` offset.
  - **R3 (Omit blank detail line):** In `GraphNodeCard`, only render the secondary `detail` `Text` when `node.detail.isNotEmpty()`, with `Arrangement.Center` and `vertical = 4.dp` padding so two-line cards do not clip on Linux fonts.
  - **R4a (Hide table footer in Graph view):** In `TopologyScreen.kt` (`TabbedBody`), omit `TopologyFooter(state, events)` when `state.tab == TopologyTab.Bindings && state.bindingsView == BindingsViewMode.Graph`.

Out:
- Any changes to broker calls, `data-rabbit`, or `:mcp-server`.
- Interactive graph dragging or zooming.

## Acceptance
- BDD: Existing 5 graph scenarios in `tests/bdd/features/09_topology_queues.feature` continue to pass.
- Unit tests (`TopologyGraphLayoutTest.kt`, `TopologyViewModelTest.kt`):
  - Crossing edges between the same layers (e.g. `(layer 0, row 0) -> (layer 1, row 1)` and `(layer 0, row 1) -> (layer 1, row 0)`) produce distinct, non-overlapping `(labelX, labelY)` coordinates.
  - A binding to a destination absent from `queues` (e.g. a stream partition) produces a `GraphNode` with `detail == ""`, whereas a queue present in `queues` with `readyMessages = 1240` produces `detail == "(1,240 ready)"`.
  - Searching on the Bindings tab while in Graph view filters the nodes and edges in `state.graph`.
- UI tests (`BindingsGraphUiTest.kt`) & Screenshots (`TopologyScreenshotTest.kt`):
  - Under `Density(2f)` (HiDPI scaling), routing-key pills and nodes align with the scaled graph layout.
  - In Graph view, `TopologyFooter` (`pageRange`, `prevPage`, `nextPage`) is not rendered; switching back to Table view renders `TopologyFooter`.
  - `./gradlew :ui:screenshotTest` updates `ui/build/screenshots/topology-12-bindings-graph.png` cleanly.

## Design notes
- Cubic bezier formula at parameter $t \in [0, 1]$ with horizontal control points $P_1 = (\text{fromX} + dx, \text{fromY})$ and $P_2 = (\text{toX} - dx, \text{toY})$ where $dx = (\text{toX} - \text{fromX}) / 2$:
  - Because $P_{0,x} = \text{fromX}$, $P_{1,x} = \frac{\text{fromX} + \text{toX}}{2}$, $P_{2,x} = \frac{\text{fromX} + \text{toX}}{2}$, $P_{3,x} = \text{toX}$:
    $$B_x(t) = (1-t)^3 \text{fromX} + 3(1-t)t \frac{\text{fromX} + \text{toX}}{2} + t^3 \text{toX}$$
  - And because $P_{0,y} = P_{1,y} = \text{fromY}$ and $P_{2,y} = P_{3,y} = \text{toY}$:
    $$B_y(t) = \big((1-t)^3 + 3(1-t)^2 t\big)\text{fromY} + \big(3(1-t)t^2 + t^3\big)\text{toY}$$
  - Evaluating $(B_x(t), B_y(t))$ guarantees that for any $t \in (0, 1)$, the label anchor lies **exact on the drawn cubic bezier curve**!

## UX
`docs/ux/Topology.dc.html` (`#tab-bindings-graph`): no pager footer under the graph; centered routing-key pills on the curves.

## MCP
None (visual graph layout and Compose Desktop rendering only).

## Open points
None.

## Definition of done
Unit and UI tests written first (red), then implementation in `core` and `ui` (green), screenshot regenerated and inspected, full test suite green.

## Status
- Reconciled 2026-11-17: this task is merged into `main` (d7faf81, 2026-11-06); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.
- **Step 1 (Brief, solution-architect, 2026-11-06):** Brief written (`docs/tasks/M2-08d1-bindings-graph-fixes.md`), branch `m2-08d1-bindings-graph-fixes`. Rulings R1–R4 specified. Next: Step 2 (`test-developer`) to write red unit and UI tests for R1–R4.
- **Step 2 (Tests first, test-developer, 2026-11-06):** Added 6 red tests across `TopologyGraphLayoutTest.kt`, `TopologyViewModelTabsTest.kt`, and `BindingsGraphUiTest.kt` (plus compile stub `labelX`/`labelY` on `PositionedEdge`). Verified all 6 fail for the right reasons.
- **Step 3 (Build, senior-developer, 2026-11-06):** Implemented R1 (`dp.toPx()` in `BindingsGraphView.kt` `Canvas`), R2 (`resolveEdgeLabelPositions` in `BindingGraph.kt` + centered pills via `.layout` in `BindingsGraphView.kt`), R3 (empty detail for non-queue/stream destinations + digit grouping + single-line card centering), and R4 (hide `TopologyFooter` in Graph view in `TopologyScreen.kt` + filter graph bindings by search in `TopologyViewModel.kt`).
- **Step 4 (Verify, test-developer, 2026-11-06):** Verdict: **pass** (0 findings). All unit, UI, BDD, and screenshot tests pass (`./gradlew test :ui:screenshotTest`), and `ui/build/screenshots/topology-12-bindings-graph.png` regenerated and visually verified.
- **Step 5 (Review, solution-architect, 2026-11-06):** Verdict: **Accepted** (round 0, 0 findings). Verified `BindingGraph.kt`, `TopologyViewModel.kt`, `BindingsGraphView.kt`, `TopologyScreen.kt`, unit/UI tests, and regenerated `ui/build/screenshots/topology-12-bindings-graph.png`. Ready to merge into local `main`.
