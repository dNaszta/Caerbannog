# M9-10: MQTT route step: a topic filter to a queue or stream, saved as a binding on amq.topic

Milestone: M9    Depends on: M9-00a (S-A, S-B), M9-03, M9-04; "Edit steps" scenarios also need M9-12    Size: M

PLANNED 2026-11-15. Not started.

## Goal
The wizard kind "MQTT route" asks for a topic filter and a destination queue or stream and adds one `declareBinding` on `amq.topic`. The step, the review graph and the Migrations screen show it as an MQTT route with its topic filter, also when a file is opened again. After the migration is applied, "Send a test message" opens the existing Publish MQTT dialog with a topic that matches.

## Why
Owner decision O2 ("add MQTT ability too"). Spec "Topology planner (M9)": A10, "MQTT routes"; spec "MQTT support (M6)" for the existing dialog and the `amq.topic` rule.

## Scope
In:
- `core.planner` (or `core.mqtt`, next to the existing model): `MqttTopicTranslation.toBindingKey(filter): Translation` (`Key(text)` or `Refused(reason)`) and `toFilter(key): String?`, exact inverses of each other, built from the table S-A measures. Refusals, fixed texts: "Enter a topic filter", "# must be the last level", "+ must stand for a whole level", and "This filter cannot be translated safely" for whatever S-A3 shows cannot make the round trip.
- `MqttRouteForm(filter, destination, destinationKind)`; `actions`: `DeclareBinding(exchange = "amq.topic", queue = destination, routingKey = key)`; the form shows the key, a matching concrete topic (the existing `StreamInboundRoute.routingKeyToSuggestedMqttTopic`) and the sentence about `amq.topic`.
- Recognition (A10): `MqttRoutes.routeOf(action)`: a `DeclareBinding` on `amq.topic` to a queue (a stream is a queue for a binding), without arguments, whose key gives a filter that gives the same key again. Its words: "MQTT route <filter> to stream <name>", "to queue <name>" or, when the planner cannot tell the kind (not in the plan, not known on the broker), "to <name>"; its second line names the binding and the key.
- `planPreview`: `edgeLabels` for a route (the filter, and "MQTT route").
- Migrations screen: the action rows use the same words, and an MQTT route of an applied migration gets "Send a test message", which opens the existing Publish MQTT dialog (`PublishMqttViewModel` with `initialTopic`); disabled with the reason when the profile has no MQTT port or the existing publish gate refuses; not offered for a pending or modified file.
- `ui`: the kind card, `MqttRouteFormView`, the edge label, the button on the Migrations screen.

Out: MQTT subscriptions, QoS or retained settings of a route (a route is only a binding); another MQTT exchange than `amq.topic` (unless S-B1 shows it can be read: then the architect extends this brief); publishing from the planner itself.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "An MQTT route is saved as a binding on amq.topic" (@fake-broker @smoke @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "An MQTT route can lead to a queue or to a stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A topic filter that MQTT does not allow is refused in the form" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A topic filter that cannot be translated safely is refused" (@fake-broker @negative @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The form shows the binding it will write and a topic that matches" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A saved MQTT route is shown as an MQTT route again" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "An MQTT route to something the planner does not know is named without its kind" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A binding on amq.topic that is not a plain MQTT route stays a binding" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The review graph labels an MQTT route with its topic filter" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "After applying, a test message can be sent to the route" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The test message is not offered where publishing is not possible" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A route of a migration that is not applied here offers no test message" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A message published over MQTT to a planned route reaches a stream and a queue" (@broker @needs-spike)
- Unit tests, written first: `MqttTopicTranslationTest` (the measured table both ways; every refused filter with its text; a property-style table that `toFilter(toBindingKey(f)) == f` for every accepted filter and `toBindingKey(toFilter(k)) == k` for every recognised key), `MqttRouteFormTest`, `MqttRoutesTest` (recognised, and the three not-a-route cases of the outline), `PlanPreviewMqttTest`. Compose: `MqttRouteFormUiTest`, `MigrationsMqttRouteUiTest` (words, the button and its two disabled reasons, no button for a pending file, the dialog opens with the topic and publishes nothing by itself).
- Live: `RealBrokerPlannerMqttRouteTest` (tag `broker`) binds the `@broker` scenario by name: the routes of a saved plan are applied by the real runner, MQTT publishes arrive in the stream and in the quorum queue, a non-matching topic arrives in neither (positive control, bounded polling, counts only).

## Design notes
The translation is the one place that knows the rule; the existing display helper `StreamInboundRoute.routingKeyToMqttFilter` (it splits on `.` and `/` and drops empty parts) is left alone unless S-A shows it wrong for a key, which is then a small follow-up named in the report. The binding is applied by the existing executor: no new request. "Send a test message" publishes only through the existing `MqttMessagePublisher` behind the existing dialog and gate. Where the broker is faked: `FakeMqttMessagePublisher` and `FakeTopologyCatalog`; real in the live test.

## UX
`docs/ux/Planner.dc.html`: "4." form MQTT route, step 10 of the main block, the labelled edge of the review graph, "14. After applying".

## MCP
None for the planner (owner decision 2026-11-15). The existing tool `caerbannog_publish_mqtt` is unchanged and is not connected to plans.

## Open points
- S-A decides the table and what the form refuses; S-B decides whether the sentence about `amq.topic` stays an assumption.
- The wording of the disabled reason without write permission reuses the existing text of the publish gate.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
