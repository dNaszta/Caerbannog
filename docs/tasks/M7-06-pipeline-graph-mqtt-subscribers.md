# M7-06: End-to-end live data pipeline graph, MQTT subscriber nodes and the Inbound routes bar

Milestone: M7    Depends on: M7-04, M7-05    Size: L    Commit: 0fe7b1b (number assigned in commit order by the product owner)

## Goal
The stream screen shows a three-layer graph (ingress, stream, consumers) that can be hidden, with the inbound routes listed in a bar above it; the Topology Bindings graph also draws MQTT clients that hold subscriptions.

## Why
Spec "Monitoring views (M7)", bullets "Pipeline graph" and "Bindings graph".

## Scope (what shipped)
In:
- `core/streams/StreamPipelineGraph.kt` (`buildStreamPipelineGraph`): layer 0 = MQTT routes (`MqttRoute`, subtitle "MQTT: filter"), other exchange routes (`ExchangeRoute`, "key: k") and stream-protocol publishers ("N published"); layer 1 = hub node with "head @offset" (or "stream" when unknown); layer 2 = consumers with "@offset · lag N" and health Healthy (caught up), Lagging (behind), Standby (not active), Neutral (updating). Node 188 x 44, layer gap 116, vertical gap 12 (dp). An empty routing key is shown as "*".
- `ui/streams/StreamPipelineGraphView.kt` (`StreamPipelineGraphCard`), horizontally scrollable; click on an MQTT route node opens the Publish MQTT dialog with its suggested topic, click on a consumer node seeks the browser to the consumer offset. `StreamScreen` has a "Hide pipeline" or "Show pipeline" toggle (default shown) and the Inbound routes bar (`StreamInboundRoutesBar`: `exchange ──[key]──> stream`, an "MQTT: filter" pill and a "Publish MQTT" button per MQTT route).
- `BindingGraph`: `GraphNodeKind.MqttClient`; clients with at least one subscription become nodes "(mqtt · N sub)" or "(mqtt · offline)", placed one layer right of `amq.topic`, with an edge per subscription labelled "topic (QoS n)"; `amq.topic` is added (type topic) when needed; queue nodes gain `memberOffline` and `unackedCount`. `TopologyViewModel` lists MQTT clients only on the Bindings Graph view and only when the profile has MQTT.
Out: scrollbar of the routes bar (M7-11); an MCP tool.

## Acceptance
- `core/.../topology/TopologyGraphLayoutTest`: "buildBindingGraph includes MQTT client subscriber nodes and queue health flags"; "buildStreamPipelineGraph organizes ingress routes, publishers, stream hub, and consumers across three layers".
- `ui/.../topology/BindingsGraphUiTest`: "BindingsGraphView renders MQTT client subscriber nodes and offline queue health indicators".
- `ui/.../publish/PublishMqttDialogUiTest`: "StreamScreen displays inbound MQTT route banner and pre-fills suggested publish topic on click" (also asserts the Inbound routes bar, the pipeline graph nodes and the hide toggle).
Not covered by a test: consumer node seek click, health colours, graph size limits, the unacked flag display.

## Design notes
Pure builders in `core`, drawn in `ui`; inputs are state the stream screen already holds (routes, publishers, consumers). No new broker call except the MQTT client list on the graph view.

## UX
No file in `docs/ux/`.

## MCP
None (UI-only): same information is available through existing list tools.

## Non-goals
Dragging nodes, editing bindings from the graph.

## Open points
No cap on node count in the pipeline graph (the Bindings graph has one, M2-08d); many consumers make a tall graph.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
