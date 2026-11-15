# M9-07b: The optional MQTT routes of a stream pair (the owner's request and answer flow)

Milestone: M9    Depends on: M9-07 (the plain pair), M9-10 (the MQTT route form and its translation), M9-00a (S-A5 to S-A8, for the texts and the note), M9-12 (for the "recognised again" scenario only), and the owner's confirmation of the revised Q3    Size: S

PLANNED 2026-11-15. Not started. Cut out of `M9-07` on 2026-11-15 so that the plain stream pair can be built without the MQTT route step; this task adds two ticks that call the form of `M9-10`, and one note in the graph.

## Goal
In the stream form, with "Also create its response stream (DLN)" ticked, two more optional ticks, both off by default, each add one ordinary MQTT route: "Answers arrive over MQTT" into the response stream, and "Requests are also recorded from an MQTT topic" into the request stream. The wizard summary and the review graph say, in one short sentence, that the owner's own service turns a request into an MQTT message and that the planner does not plan that.

## Why
Owner decision O13 (Q3 revised). The owner's words: "i have several iot devices, i can request them through eg. started on stream, iot device gets mqtt message, answers with mqtt, which is processed through stream", and "header can contains destination id, devices answer on a fixed topic with their id in it, like devices/<id>/answer - correlation id". The flow: a request is a message in the request stream with the destination device id in a header; the owner's service (not planned by the app) turns it into an MQTT message for the device; the device answers on `devices/<id>/answer`; the answer must land in the response stream and be processed from there; request and answer are matched by a correlation id in the message, which the planner never touches. The first idea (routing on the request side by default) had the MQTT route on the wrong side. Spec "Topology planner (M9)", "Streams and the response stream", A14.

## Scope
In:
- `core.planner`: `StreamForm` (from `M9-07`) gains `answersTopic: String?` and `requestsTopic: String?`; null means the tick is off. The ticks exist only while `withResponse` is true; clearing the pair clears both. Each given filter is checked by the same function as the MQTT route form (`MqttTopicTranslation`, `M9-10`), with the same fixed texts. `actions`: the two streams first, then the answers route (`MqttRouteForm(answersTopic, <response stream name>, stream)` actions), then the requests route (`MqttRouteForm(requestsTopic, <stream name>, stream)` actions). Nothing else: no exchange, no policy, no marker (A1, A14).
- The text of the summary under the step list when an answers route is part of it, and of the graph note, is one constant: `PlannerTexts.SERVICE_NOT_PLANNED = "Your own service turns a request into an MQTT message; the planner does not plan that."`
- The note in the graph: `planGraphNotes(plan, known): List<PlanGraphNote>`, a pure function over the actions (A10, A11): for every MQTT route into a stream that is recognised as a response stream, one note between the request stream and `amq.topic` (once per request stream, however many routes). So the note is also drawn for a pair that was typed by hand or reopened from a file. It goes into the `notes` field that `M9-04` added to `PlanGraph`. A route into a stream that is not a response stream draws no note.
- `ui`: two checkboxes with a topic field each in `StreamFormView` (placeholder of the answers field `devices/+/answer`, the owner's example; the requests field has none), the note drawn by `PlanReviewGraph` as a dashed rounded box with no edge, no click and no focus action beyond reading it. Test tags: `planForm:answersOverMqtt`, `planForm:answersTopic`, `planForm:requestsOverMqtt`, `planForm:requestsTopic`, `planGraphNote:<index>`.

Out: any bridge from a stream to an MQTT message; reading or writing a correlation id (the test message of `M9-10` is the only place the planner pre-fills one); retention (`M9-08`); an exchange or a routing key of the owner's own choice for the pair; MQTT 5 settings of a route.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "The MQTT ticks are offered only with the response stream and are off until I tick them" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "Answers arrive over MQTT adds one route into the response stream" (@fake-broker @smoke @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Requests are also recorded from an MQTT topic adds one route into the request stream" (@fake-broker @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "Both ticks add two routes, the answers route first" (@fake-broker @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The wizard says what it does not plan" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "The routes of a pair are ordinary steps" (@fake-broker @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "The topic filter of a tick is checked like an MQTT route" (@fake-broker @negative @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "Without the response stream the MQTT ticks go away" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "The review graph shows a note where the owner's service sits" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "No note without an MQTT route into a response stream" (@fake-broker @needs-decision)
- BDD: `21_topology_planner_recipes.feature` / "A saved pair with its answers route is recognised again" (@fake-broker @needs-decision @needs-spike)
- BDD: `21_topology_planner_recipes.feature` / "A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it" (@broker @needs-decision @needs-spike)
- Unit tests, written first: `StreamFormMqttTicksTest` (actions and their order for each combination of ticks; the ticks are offered only with the pair and cleared when it is unticked; filter errors are the ones of the route form), `PlanGraphNotesTest` (one note for a pair with an answers route; none without a route; none for a route into a stream that is not a response stream; one note for two routes into the same pair; a hand-typed pair; a reopened pair), `PlanPreviewDeviceFlowTest` (the edge from `amq.topic` to the response stream is labelled with the filter, the note sits between the two nodes). Compose: `StreamFormMqttTicksUiTest`, `PlanReviewGraphNoteUiTest` (the note is drawn and read; it has no click action that reaches a planner event, with a sensitivity check that the test would see one).
- Live: `RealBrokerPlannerIotRouteTest` (tag `broker`) binds the `@broker` scenario by name: the saved plan is applied by the real runner; a publish over MQTT to `devices/d42/answer` with a correlation id as user property arrives in the response stream and not in the request stream (positive control, bounded polling, counts only); where the correlation id appears on the stream message is asserted as S-A6 measured it, and the assertion is written after the spike.

## Design notes
Everything here is built from parts that exist after `M9-07` and `M9-10`: two `declareStream` steps and ordinary `declareBinding` steps on `amq.topic`. After "Add to plan" nothing remembers that the steps belong together, so each can be edited or removed (A1); a reopened file shows the recognised pair (A11) and the recognised routes (A10) and the note follows from them. The note is not a step, not an edge and not stored. Where the broker is faked: `FakeTopologyCatalog` for `KnownTopology`; real in the live test. No MCP and no new request: the apply is the existing runner and executor.

## UX
`docs/ux/Planner.dc.html`: "4." form Stream (the two ticks under the response stream), "5. Summary" for the stream pair, "8. Review graph: other cases" (the graph of the request and answer flow with the note).

## MCP
None (owner decision 2026-11-15, "no MCP"). The existing tool `caerbannog_publish_mqtt` is unchanged and is not connected to plans.

## Open points
- Q3 (revised, decided by the architect from the owner's description, to be confirmed by the owner at the mockup review): this task does not start before the confirmation. Every scenario of this task carries `@needs-decision` until then.
- The two tick texts, "Answers arrive over MQTT" and "Requests are also recorded from an MQTT topic", and the sentence of the note are the architect's wording; the owner sees them in the mockup.
- S-A5 (nothing feeds an MQTT subscription from a stream, UNVERIFIED): if the spike finds a way, the owner is asked whether the planner should plan it; the revised Q3 plans no bridge.
- S-A6 and S-A7 (where the correlation id lands): they change the explaining sentence of the test message in `M9-10` and the last assertion of the live scenario, not the plan this task writes.
- S-A1 must show `devices/+/answer` as `devices.*.answer`; until then the scenarios that name the key are `@needs-spike`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
