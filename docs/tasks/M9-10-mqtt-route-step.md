# M9-10: MQTT route step: a topic filter to a queue or stream, saved as a binding on amq.topic

Milestone: M9    Depends on: M9-00a (S-A1 to S-A4 and S-B for the route; S-A6 and S-A7 only for the wording around the correlation-id header), M9-03, M9-04; "Edit steps" scenarios also need M9-12    Size: M (the largest of M9; if step 3 passes its third day, the sample-value dialog, which depends on nothing else in this task, is cut out as `M9-10b`)

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
- Migrations screen: the action rows use the same words, and an MQTT route of an applied migration gets "Send a test message" (A15); disabled with the reason when the profile has no MQTT port or the existing publish gate refuses; not offered for a pending or modified file.
- The test message with sample values (A15, an architect addition the owner sees at the mockup review): `MqttTopicSample.fill(filter, values): SampleResult` in `core.mqtt`, pure: one value for every `+` (the dialog starts the field as `item` and says "Value for the + in level N", with the hint "for example a device id") and one for a `#` (starts as `test`, "Value for the rest of the topic"); a filter without wildcards asks nothing. Fixed refusals: "Enter a value", "One level only: no /" (for a `+`), "No + or # in a value"; a refused value never opens the dialog. Then the existing Publish MQTT dialog opens with the concrete topic (the dialog accepts no wildcards) and one header, key `correlation-id` (constant, Q17), value from an injected `CorrelationIdSource` (a UUID in the composition root, a counter in tests), as an MQTT 5 user property in the dialog's own headers list, editable like any header. For that, `PublishMqttViewModel` gets one optional parameter `initialHeaders: List<MqttHeader> = emptyList()` beside `initialTopic` (additive; its existing tests stay unedited). Nothing is published until Publish; the MQTT port and the write permission rules of the dialog are unchanged. The owner can then play the device and watch the answer arrive in the response stream with the existing stream browser.
- `ui`: the kind card, `MqttRouteFormView`, the edge label, the button on the Migrations screen.

Out: MQTT subscriptions, QoS or retained settings of a route (a route is only a binding); another MQTT exchange than `amq.topic` (unless S-B1 shows it can be read: then the architect extends this brief); publishing from the planner itself; MQTT 5 correlation data and response topic in the dialog (the dialog has no such fields; only if S-A7 and the owner ask for them); the optional MQTT ticks of a stream pair (`M9-07b`).

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
- BDD: `21_topology_planner_recipes.feature` / "After applying, a test message asks for a sample value and opens the dialog with a concrete topic" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "A route without wildcards opens the dialog at once" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A hash asks for the rest of the topic" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A sample value that would not make a valid topic is refused" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "The dialog opens with a correlation-id header that is generated and can be changed" (@fake-broker @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The test message is not offered where publishing is not possible" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A route of a migration that is not applied here offers no test message" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "A message published over MQTT to a planned route reaches a stream and a queue" (@broker @needs-spike)
- Unit tests, written first: `MqttTopicTranslationTest` (the measured table both ways; every refused filter with its text; a property-style table that `toFilter(toBindingKey(f)) == f` for every accepted filter and `toBindingKey(toFilter(k)) == k` for every recognised key), `MqttRouteFormTest`, `MqttRoutesTest` (recognised, and the three not-a-route cases of the outline), `PlanPreviewMqttTest`, `MqttTopicSampleTest` (one, two and no wildcard; `#` takes several levels; the three refusals with their texts; hostile values: control characters, 10,000 characters, a NUL), `CorrelationIdHeaderTest` (the header is pre-filled from the injected source, two openings differ, the key is the constant), `PublishMqttViewModelInitialHeadersTest` (initial headers are in the state and are sent as user properties; the old tests are unchanged). Compose: `MqttRouteFormUiTest`, `MigrationsMqttRouteUiTest` (words, the button and its two disabled reasons, no button for a pending file), `MqttTestMessageDialogUiTest` (the sample-value dialog: one field per wildcard, the start values, the refusals, Enter, Esc; the Publish dialog opens with the topic and the header and publishes nothing by itself).
- Live: `RealBrokerPlannerMqttRouteTest` (tag `broker`) binds the `@broker` scenario by name: the routes of a saved plan are applied by the real runner, MQTT publishes arrive in the stream and in the quorum queue, a non-matching topic arrives in neither (positive control, bounded polling, counts only).

## Design notes
The translation is the one place that knows the rule; the existing display helper `StreamInboundRoute.routingKeyToMqttFilter` (it splits on `.` and `/` and drops empty parts) is left alone unless S-A shows it wrong for a key, which is then a small follow-up named in the report. The binding is applied by the existing executor: no new request. "Send a test message" publishes only through the existing `MqttMessagePublisher` behind the existing dialog and gate; the sample-value dialog is a small dialog of the Migrations screen with no broker access. Where the broker is faked: `FakeMqttMessagePublisher` and `FakeTopologyCatalog`; real in the live test.

## UX
`docs/ux/Planner.dc.html`: "4." form MQTT route, step 10 of the main block, the labelled edge of the review graph, "14. After applying" (the sample-value dialog and the pre-filled header).

## MCP
None for the planner (owner decision 2026-11-15). The existing tool `caerbannog_publish_mqtt` is unchanged and is not connected to plans.

## Open points
- S-A decides the table and what the form refuses; S-B decides whether the sentence about `amq.topic` stays an assumption.
- The wording of the disabled reason without write permission reuses the existing text of the publish gate.
- Q17 (open, recommended `correlation-id`, a user property only, editable): the header key of the test message. The sample-value dialog and the dialog's other parts do not wait for the answer; only the one scenario tagged `@needs-decision` does.
- S-A6 and S-A7 decide whether a short sentence under the header explains where the correlation id arrives on the stream message; until then the dialog shows no such sentence.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
