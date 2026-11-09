# M7-04: End-to-end MQTT-to-stream workflow and stream nodes in the Bindings graph

Milestone: M7    Depends on: M6-05, M2-08d (bindings graph), M7-02    Size: M    Commit: 6fb91e3 (number assigned in commit order by the product owner)

## Goal
From a stream, the user sees the routes that feed it and can open the Publish MQTT dialog with a topic that matches a route; in the Topology Bindings graph, stream destinations are drawn as stream nodes and open the stream screen on click.

## Why
Lets the owner test the path "MQTT topic, amq.topic binding, stream" in two clicks. Spec: "MQTT support (M6)", bullet "From a stream".

## Scope (what shipped)
In:
- `core/mqtt/MqttModel.kt`: `StreamInboundRoute(exchange, routingKey, arguments)`; `isMqttTopicExchange` is true only for `amq.topic`; `mqttTopicFilter` turns `sensors.#` into `sensors/#` and `*` into `+`; `suggestedPublishTopic` makes a concrete topic (`#` becomes `test`, `*` and `+` become `item`, empty becomes `test`).
- `BindingGraph`: `GraphNodeKind.Stream`; a destination whose name matches a stream is a stream node with detail "(stream · N msgs)". `TopologyViewModel` loads streams (page size 500, one page) only on the Bindings tab in Graph view.
- `BindingsGraphView`: stream nodes have a primary border and click to `onOpenStream`; `Main.kt` opens the Streams destination and the stream.
- `Main.kt` collects the stream's inbound routes from the topology bindings (destination equals the stream, non-empty source); `StreamScreen` shows a "Publish MQTT" button and a route-level button that pre-fills the suggested topic.
Out: the pipeline graph (M7-06); the Inbound routes bar styling follow-ups (M7-11).

## Acceptance
- `core/.../topology/TopologyGraphLayoutTest`: "stream destinations matching known streams receive GraphNodeKind Stream and formatted message counts"; "StreamInboundRoute translates amq topic routing keys into MQTT topic filters and concrete suggested publish topics".
- `ui/.../topology/BindingsGraphUiTest`: "stream nodes in BindingsGraphView display stream detail and invoke onOpenStream when clicked".
- `ui/.../publish/PublishMqttDialogUiTest`: "StreamScreen displays inbound MQTT route banner and pre-fills suggested publish topic on click".
Live proof of the path: `RealBrokerM5GateTest` and `RealBrokerMqttHeadersTest` (the latter from M7-10).
Not covered by a test: the 500-stream cap of the graph lookup; the `hasMqttOnStream` condition in `Main.kt`.

## Design notes
Route lookup reuses the topology gateway; no new broker call. The suggested topic is only a suggestion, the user can edit it.

## UX
Graph styling per M2-08d; no new screen file.

## MCP
None: the MCP server already has `caerbannog_publish_mqtt` (M6-06).

## Non-goals
Subscribing from the app; routes through non-`amq.topic` exchanges get no MQTT button.

## Open points
`hasMqttOnStream = source?.mqttPublisher != null || ...` is true whenever a connection is held, because the publisher is a non-null type (defaults to `UnavailableMqttMessagePublisher`). The button probably shows for profiles without an MQTT port; to confirm, see the architect's reply.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
